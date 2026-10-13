package com.phonetag.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/** End-to-end tests over real loopback sockets. */
class GameServerTest {
    private val servers = mutableListOf<GameServer>()
    private val clients = mutableListOf<GameClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.disconnect() }
        servers.forEach { it.stop() }
    }

    private fun server(singleLobby: Boolean) =
        GameServer(port = 0, singleLobby = singleLobby, tickIntervalMs = 50).also { it.start(); servers += it }

    private class Bot(val inbox: CopyOnWriteArrayList<ServerMessage> = CopyOnWriteArrayList())

    private fun client(server: GameServer): Pair<GameClient, Bot> {
        val bot = Bot()
        val c = GameClient({ bot.inbox += it }, {})
        c.connect("127.0.0.1", server.localPort)
        clients += c
        return c to bot
    }

    private inline fun <reified T : ServerMessage> Bot.await(timeoutMs: Long = 3000, pred: (T) -> Boolean = { true }): T {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        while (System.nanoTime() < deadline) {
            inbox.filterIsInstance<T>().firstOrNull(pred)?.let { return it }
            Thread.sleep(10)
        }
        fail("Timed out waiting for ${T::class.simpleName}; got $inbox")
    }

    @Test
    fun wifiDirectModeSeventeenthPlayerIsRejected() {
        val s = server(singleLobby = true)
        val (host, hostBot) = client(s)
        host.send(ClientMessage.Hello(Protocol.VERSION, "host", create = true, arTracking = true))
        hostBot.await<ServerMessage.Welcome>()
        repeat(15) { i ->
            val (c, b) = client(s)
            c.send(ClientMessage.Hello(Protocol.VERSION, "p$i", arTracking = true))
            b.await<ServerMessage.Welcome>()
        }
        hostBot.await<ServerMessage.LobbyState> { it.players.size == 16 }

        val (late, lateBot) = client(s)
        late.send(ClientMessage.Hello(Protocol.VERSION, "late", arTracking = true))
        val err = lateBot.await<ServerMessage.Error>()
        assertEquals(ErrorCode.LOBBY_FULL, err.code)
        assertEquals(1, s.lobbyCount)
    }

    @Test
    fun dedicatedServerHostsSeparateLobbiesByCode() {
        val s = server(singleLobby = false)
        val (a, aBot) = client(s)
        a.send(ClientMessage.Hello(Protocol.VERSION, "alice", create = true, lobbyName = "Park", arTracking = true))
        val code = aBot.await<ServerMessage.Welcome>().lobbyCode

        val (b, bBot) = client(s)
        b.send(ClientMessage.Hello(Protocol.VERSION, "bob", create = true, arTracking = true))
        val otherCode = bBot.await<ServerMessage.Welcome>().lobbyCode
        assertTrue(code != otherCode)

        val (c, cBot) = client(s)
        c.send(ClientMessage.ListLobbies)
        val list = cBot.await<ServerMessage.LobbyList>()
        assertEquals(setOf(code, otherCode), list.lobbies.map { it.code }.toSet())
        c.send(ClientMessage.Hello(Protocol.VERSION, "carol", lobbyCode = code.lowercase(), arTracking = true))
        assertNotNull(cBot.await<ServerMessage.Welcome>())
        aBot.await<ServerMessage.LobbyState> { it.players.map { p -> p.name } == listOf("alice", "carol") }

        val (d, dBot) = client(s)
        d.send(ClientMessage.Hello(Protocol.VERSION, "dave", lobbyCode = "ZZZZZ", arTracking = true))
        assertEquals(ErrorCode.LOBBY_NOT_FOUND, dBot.await<ServerMessage.Error>().code)
    }

    @Test
    fun fullMatchOverSockets() {
        val s = server(singleLobby = true)
        val (a, aBot) = client(s)
        a.send(ClientMessage.Hello(Protocol.VERSION, "a", create = true, arTracking = true))
        val aId = aBot.await<ServerMessage.Welcome>().playerId
        val (b, bBot) = client(s)
        b.send(ClientMessage.Hello(Protocol.VERSION, "b", arTracking = true))
        val bId = bBot.await<ServerMessage.Welcome>().playerId

        // Both phones are synced to the arena: a at the origin facing west, b 12 m west of it.
        val origin = trackedPose(0.0, 0.0, azimuth = 270.0)
        val bPose = trackedPose(270.0, 12.0, azimuth = 90.0)
        a.send(ClientMessage.UpdateSettings(GameSettings(fireCooldownMs = 100)))
        a.send(ClientMessage.PoseUpdate(origin))
        b.send(ClientMessage.PoseUpdate(bPose))
        // Both players see each other's tracked position in snapshots (they flow in the lobby too).
        aBot.await<ServerMessage.Snapshot> { snap -> snap.players.any { it.id == bId && it.pose?.arena != null } }
        a.send(ClientMessage.StartMatch)
        aBot.await<ServerMessage.LobbyState> { it.phase == Phase.RUNNING }

        Thread.sleep(Lobby.SPAWN_PROTECTION_MS)
        var seq = 0
        val deadline = System.currentTimeMillis() + 5000
        while (aBot.inbox.none { it is ServerMessage.Tagged } && System.currentTimeMillis() < deadline) {
            b.send(ClientMessage.PoseUpdate(bPose))
            a.send(ClientMessage.Shoot(seq++, origin))
            Thread.sleep(150)
        }
        val tag = aBot.await<ServerMessage.Tagged>()
        assertEquals(aId, tag.shooterId)
        assertEquals(bId, tag.victimId)
        bBot.await<ServerMessage.HitBy> { it.eliminated }

        // When a player disconnects mid-match leaving one player, the match ends.
        b.disconnect()
        aBot.await<ServerMessage.MatchEnded>()
    }

    @Test
    fun phonesWithoutArAreTurnedAway() {
        val s = server(singleLobby = true)
        val (host, hostBot) = client(s)
        host.send(ClientMessage.Hello(Protocol.VERSION, "host", create = true, arTracking = true))
        hostBot.await<ServerMessage.Welcome>()
        val (c, bot) = client(s)
        c.send(ClientMessage.Hello(Protocol.VERSION, "no-ar"))
        assertEquals(ErrorCode.AR_REQUIRED, bot.await<ServerMessage.Error>().code)
    }

    @Test
    fun rejectsWrongProtocolVersionAndGarbage() {
        val s = server(singleLobby = false)
        val (c, bot) = client(s)
        c.send(ClientMessage.Hello(999, "x", create = true))
        assertEquals(ErrorCode.BAD_VERSION, bot.await<ServerMessage.Error>().code)
        assertEquals(0, s.lobbyCount)
    }
}
