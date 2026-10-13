package com.phonetag.core

import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.SecureRandom
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * TCP server hosting one or more [Lobby]s.
 *
 * - On a phone acting as Wi-Fi Direct group owner it runs with [singleLobby] = true: the host
 *   creates the lobby by connecting to itself over loopback and everyone else just joins it.
 * - As a dedicated internet server it runs with [singleLobby] = false and hosts many lobbies,
 *   each identified by a short join code and each capped at 16 players.
 */
class GameServer(
    private val port: Int = Protocol.DEFAULT_PORT,
    private val singleLobby: Boolean,
    private val maxLobbies: Int = 500,
    private val maxConnections: Int = 4000,
    private val bindAddress: InetAddress? = null,
    private val tickIntervalMs: Long = 200,
    private val log: (String) -> Unit = {},
) {
    private val lobbies = ConcurrentHashMap<String, Lobby>()
    private val connections = ConcurrentHashMap.newKeySet<Connection>()
    private val connectionCount = AtomicInteger()
    private var serverSocket: ServerSocket? = null
    private var ticker: ScheduledExecutorService? = null
    private val random = SecureRandom()

    @Volatile var isRunning = false
        private set

    /** Actual bound port (useful when started with port 0 in tests). */
    val localPort: Int get() = serverSocket?.localPort ?: port

    val lobbyCount: Int get() = lobbies.size

    fun start() {
        val ss = ServerSocket()
        ss.reuseAddress = true
        ss.bind(InetSocketAddress(bindAddress, port))
        serverSocket = ss
        isRunning = true
        Thread(::acceptLoop, "phonetag-accept").apply { isDaemon = true }.start()
        ticker = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "phonetag-tick").apply { isDaemon = true } }
            .also { it.scheduleAtFixedRate(::tickAll, tickIntervalMs, tickIntervalMs, TimeUnit.MILLISECONDS) }
        log("PhoneTag server listening on ${ss.localSocketAddress} (singleLobby=$singleLobby)")
    }

    fun stop() {
        isRunning = false
        ticker?.shutdownNow()
        runCatching { serverSocket?.close() }
        connections.toList().forEach { it.close() }
        lobbies.clear()
    }

    private fun tickAll() {
        for (lobby in lobbies.values) {
            try {
                lobby.tick()
            } catch (t: Throwable) {
                log("tick failed for ${lobby.code}: $t")
            }
        }
    }

    private fun acceptLoop() {
        val ss = serverSocket ?: return
        while (isRunning) {
            val socket = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            if (connectionCount.get() >= maxConnections) {
                runCatching { socket.close() }
                continue
            }
            ClientSession(socket).start()
        }
    }

    /** Server-side state for one TCP connection. */
    private inner class ClientSession(socket: Socket) {
        private val conn = Connection(socket, ::onLine, ::onClosed, name = "client")
        @Volatile private var lobby: Lobby? = null
        @Volatile private var playerId = 0
        private val limiter = RateLimiter(perSecond = 40.0, burst = 80.0)

        fun start() {
            connectionCount.incrementAndGet()
            connections += conn
            conn.start()
        }

        private fun send(msg: ServerMessage) = conn.send(Protocol.encode(msg))

        private fun onLine(line: String) {
            if (!limiter.tryAcquire()) return
            val msg = try {
                Protocol.decodeClient(line)
            } catch (e: Exception) {
                send(ServerMessage.Error(ErrorCode.BAD_REQUEST, "Malformed message"))
                return
            }
            val current = lobby
            if (current != null) {
                if (msg is ClientMessage.Leave) conn.close() else current.handle(playerId, msg)
                return
            }
            when (msg) {
                is ClientMessage.Hello -> hello(msg)
                is ClientMessage.ListLobbies -> send(
                    ServerMessage.LobbyList(lobbies.values.map { it.summary() }.filter { it.phase != Phase.RUNNING }.take(100))
                )
                is ClientMessage.Ping -> send(ServerMessage.Pong(msg.clientTimeMs))
                is ClientMessage.Leave -> conn.close()
                else -> send(ServerMessage.Error(ErrorCode.BAD_REQUEST, "Say hello first"))
            }
        }

        private fun hello(msg: ClientMessage.Hello) {
            if (msg.protocol != Protocol.VERSION) {
                send(ServerMessage.Error(ErrorCode.BAD_VERSION, "Server speaks protocol ${Protocol.VERSION}, app sent ${msg.protocol}"))
                return
            }
            val target: Lobby = synchronized(lobbies) {
                when {
                    singleLobby -> lobbies.values.firstOrNull()
                        ?: if (msg.create) createLobby(msg.lobbyName ?: "${msg.name}'s game") else null
                    msg.create -> if (lobbies.size >= maxLobbies) null else createLobby(msg.lobbyName ?: "${msg.name}'s game")
                    else -> msg.lobbyCode?.uppercase()?.let { lobbies[it] }
                }
            } ?: run {
                val err = if (msg.create) {
                    ServerMessage.Error(ErrorCode.SERVER_FULL, "Server has no room for more lobbies")
                } else {
                    ServerMessage.Error(ErrorCode.LOBBY_NOT_FOUND, "No lobby with that code")
                }
                send(err)
                return
            }
            when (val r = target.join(msg.name, ::send)) {
                is Lobby.JoinResult.Joined -> {
                    playerId = r.playerId
                    lobby = target
                    // The socket may have died while we were joining; don't leak a ghost player.
                    if (!conn.isOpen) onClosed()
                    log("${msg.name} joined ${target.code} as #${r.playerId} from ${conn.remoteAddress}")
                }
                is Lobby.JoinResult.Rejected -> {
                    send(ServerMessage.Error(r.code, r.message))
                    removeIfEmpty(target)
                }
            }
        }

        private fun onClosed() {
            connections -= conn
            connectionCount.decrementAndGet()
            val l = lobby ?: return
            lobby = null
            l.leave(playerId)
            removeIfEmpty(l)
        }
    }

    private fun createLobby(name: String): Lobby {
        var code: String
        do {
            code = (1..5).map { CODE_ALPHABET[random.nextInt(CODE_ALPHABET.length)] }.joinToString("")
        } while (lobbies.containsKey(code))
        return Lobby(code, name.filter { !it.isISOControl() }.take(32)).also {
            lobbies[code] = it
            log("Lobby $code created (\"${it.name}\")")
        }
    }

    private fun removeIfEmpty(lobby: Lobby) {
        synchronized(lobbies) {
            if (lobby.playerCount == 0 && lobbies.remove(lobby.code, lobby)) log("Lobby ${lobby.code} closed")
        }
    }

    private class RateLimiter(private val perSecond: Double, private val burst: Double) {
        private var tokens = burst
        private var last = System.nanoTime()

        @Synchronized
        fun tryAcquire(): Boolean {
            val now = System.nanoTime()
            tokens = (tokens + (now - last) / 1e9 * perSecond).coerceAtMost(burst)
            last = now
            if (tokens < 1.0) return false
            tokens -= 1.0
            return true
        }
    }

    private companion object {
        const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
    }
}
