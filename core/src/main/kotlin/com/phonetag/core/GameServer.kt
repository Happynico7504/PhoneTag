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
 *
 * Anti-cheat: each connection collects strikes for things only a modified client does (see
 * [Violation]); at the threshold the player is kicked and their IP and device ID are locked out
 * for [lockoutMs]. Loopback connections (the Wi-Fi Direct host's own client) are exempt.
 */
class GameServer(
    private val port: Int = Protocol.DEFAULT_PORT,
    private val singleLobby: Boolean,
    private val maxLobbies: Int = 500,
    private val maxConnections: Int = 4000,
    private val bindAddress: InetAddress? = null,
    private val tickIntervalMs: Long = 200,
    private val log: (String) -> Unit = {},
    private val lockoutMs: Long = DEFAULT_LOCKOUT_MS,
    /** Also lock the IP address, not just the device. Shared IPs (CGNAT, one Wi-Fi) can make this hit bystanders. */
    private val ipLockout: Boolean = true,
    private val exemptLoopback: Boolean = true,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private val lockouts = Lockouts(clock)
    /** Joined sessions by "lobbyCode#playerId", so lobby violations reach the right connection. */
    private val sessions = ConcurrentHashMap<String, ClientSession>()
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
        // Iterate the concurrent set directly: closing removes entries, which its iterator tolerates.
        // (toList() races: it reads size() and then iterator().next(), which throws if the last
        // connection closed in between.)
        for (c in connections) c.close()
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
        // Voice adds 25 frames/s on top of poses, pings and shots.
        private val limiter = RateLimiter(perSecond = 60.0, burst = 120.0)
        private val ip: String = socket.inetAddress?.hostAddress ?: "?"
        private val exempt = exemptLoopback && socket.inetAddress?.isLoopbackAddress == true
        private val monitor = CheatMonitor()
        @Volatile private var deviceId: String? = null
        @Volatile private var kicked = false

        private fun lockKeys() = listOfNotNull(
            Lockouts.ipKey(ip).takeIf { ipLockout && !exempt },
            deviceId?.let(Lockouts::deviceKey),
        )

        fun start() {
            connectionCount.incrementAndGet()
            connections += conn
            conn.start()
            if (!exempt) refuseIfLockedOut()
        }

        /** Sends LOCKED_OUT and hangs up if this IP/device is serving a lockout. */
        private fun refuseIfLockedOut(): Boolean {
            val left = lockouts.remainingMs(lockKeys())
            if (left <= 0) return false
            conn.sendAndClose(Protocol.encode(ServerMessage.Error(ErrorCode.LOCKED_OUT, "Locked out for cheating for another ${minutes(left)}")))
            return true
        }

        /** Called for each violation (from this connection or its lobby); kicks at the threshold. */
        fun violation(v: Violation) {
            if (exempt || kicked) return
            if (!monitor.report(v, clock())) return
            kicked = true
            // Kick outside the lobby's lock (we may be inside Lobby.handle right now).
            val t = ticker
            if (t == null || runCatching { t.execute(::kick) }.isFailure) kick()
        }

        private fun kick() {
            val reason = monitor.reasons()
            lockouts.lock(lockKeys(), lockoutMs)
            lobby?.let { l ->
                l.kick(playerId, reason)
                removeIfEmpty(l)
            }
            log("Kicked ${conn.remoteAddress} (device ${deviceId?.take(8) ?: "?"}) for ${minutes(lockoutMs)}: $reason")
            conn.sendAndClose(
                Protocol.encode(ServerMessage.Error(ErrorCode.LOCKED_OUT, "Removed for cheating ($reason). Locked out for ${minutes(lockoutMs)}."))
            )
        }

        private fun send(msg: ServerMessage) {
            if (msg is ServerMessage.Voice) conn.sendDroppable(Protocol.encode(msg)) else conn.send(Protocol.encode(msg))
        }

        private fun onLine(line: String) {
            if (kicked) return
            if (!limiter.tryAcquire()) return violation(Violation.FLOOD)
            val msg = try {
                Protocol.decodeClient(line)
            } catch (e: Exception) {
                violation(Violation.MALFORMED)
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
            val device = msg.deviceId
            if (device == null || !DEVICE_ID.matches(device)) {
                send(ServerMessage.Error(ErrorCode.BAD_REQUEST, "Missing device ID; please update the app"))
                return
            }
            deviceId = device
            if (!exempt && refuseIfLockedOut()) return
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
            when (val r = target.join(msg.name, msg.arTracking, ::send)) {
                is Lobby.JoinResult.Joined -> {
                    playerId = r.playerId
                    lobby = target
                    sessions[sessionKey(target.code, r.playerId)] = this
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
            sessions.remove(sessionKey(l.code, playerId), this)
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
        val onViolation = { playerId: Int, v: Violation -> sessions[sessionKey(code, playerId)]?.violation(v) ?: Unit }
        return Lobby(code, name.filter { !it.isISOControl() }.take(32), clock, onViolation).also {
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

    companion object {
        const val DEFAULT_LOCKOUT_MS = 15 * 60_000L
        private const val CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789"
        private val DEVICE_ID = Regex("[A-Za-z0-9_-]{16,128}")

        private fun sessionKey(code: String, playerId: Int) = "$code#$playerId"

        private fun minutes(ms: Long): String {
            val m = ((ms + 59_999) / 60_000).coerceAtLeast(1)
            return if (m == 1L) "1 minute" else "$m minutes"
        }
    }
}
