package com.phonetag.core

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.fail

class AntiCheatTest {
    @Test
    fun strikesDecaySoOnlyRepeatedViolationsTrigger() {
        val m = CheatMonitor(threshold = 5.0, halfLifeMs = 60_000)
        var t = 0L
        // One FIRE_RATE strike a minute never adds up (decays to < 1.0 steady state).
        repeat(20) { assertFalse(m.report(Violation.FIRE_RATE, t)); t += 60_000 }
        assertTrue(m.currentScore < 1.0)
        // Ten in quick succession do; the trigger fires exactly once.
        val fired = (1..12).count { m.report(Violation.FIRE_RATE, t + it) }
        assertEquals(1, fired)
        assertEquals("firing faster than the weapon allows", m.reasons())
    }

    @Test
    fun lockoutsExpire() {
        var now = 0L
        val l = Lockouts { now }
        l.lock(listOf(Lockouts.ipKey("1.2.3.4"), Lockouts.deviceKey("abc")), 15 * 60_000L)
        assertEquals(15 * 60_000L, l.remainingMs(listOf(Lockouts.deviceKey("abc"))))
        assertTrue(l.remainingMs(listOf(Lockouts.ipKey("1.2.3.4"), Lockouts.deviceKey("other"))) > 0)
        assertEquals(0L, l.remainingMs(listOf(Lockouts.ipKey("5.6.7.8"))))
        now += 15 * 60_000L
        assertEquals(0L, l.remainingMs(listOf(Lockouts.deviceKey("abc"))))
    }

    // ---------------------------------------------------------------- lobby-level detection

    private var now = 1_000_000L
    private val violations = mutableListOf<Pair<Int, Violation>>()
    private val lobby = Lobby("CHEAT", "c", { now }) { id, v -> violations += id to v }
    private val inbox = mutableMapOf<Int, MutableList<ServerMessage>>()

    private fun join(name: String) = mutableListOf<ServerMessage>().let { box ->
        (lobby.join(name, arTracking = true) { box += it } as Lobby.JoinResult.Joined).playerId.also { inbox[it] = box }
    }

    private fun startMatch(): Pair<Int, Int> {
        val a = join("a")
        val b = join("b")
        lobby.handle(a, ClientMessage.PoseUpdate(trackedPose(0.0, 0.0)))
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0)))
        lobby.handle(a, ClientMessage.StartMatch)
        now += Lobby.SPAWN_PROTECTION_MS
        violations.clear()
        return a to b
    }

    @Test
    fun honestPlayIsNeverFlagged() {
        val (a, b) = startMatch()
        // Walk 1.2 m/s, fire at the allowed rate, for a minute.
        repeat(300) { i ->
            now += 200
            lobby.handle(a, ClientMessage.PoseUpdate(trackedPose(0.0, i * 0.24)))
            lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0 + i * 0.1)))
            if (i % 2 == 0) lobby.handle(a, ClientMessage.Shoot(i, trackedPose(0.0, i * 0.24)))
            lobby.tick()
        }
        assertEquals(emptyList(), violations)
    }

    @Test
    fun rapidFireSpeedAndTeleportsAreReported() {
        val (a, b) = startMatch()
        lobby.handle(a, ClientMessage.Shoot(1, trackedPose(0.0, 0.0)))
        now += 50 // well under half the 350 ms cooldown
        lobby.handle(a, ClientMessage.Shoot(2, trackedPose(0.0, 0.0)))
        now += 250 // under the cooldown but plausibly jitter: ignored, not reported
        lobby.handle(a, ClientMessage.Shoot(3, trackedPose(0.0, 0.0)))
        assertEquals(listOf(a to Violation.FIRE_RATE), violations)
        violations.clear()

        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0)))
        now += 1000
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 35.0))) // 20 m in 1 s
        now += 500
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(90.0, 60.0))) // ~65 m in 0.5 s
        assertEquals(listOf(b to Violation.SPEED, b to Violation.TELEPORT), violations)
        violations.clear()

        // A long tracking gap followed by a big but walkable move isn't a teleport.
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(90.0, 60.0).copy(arena = null)))
        now += 30_000
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(90.0, 20.0)))
        assertEquals(emptyList(), violations)
    }

    @Test
    fun untrackedShotsAndImpossibleDataAreReported() {
        val (a, _) = startMatch()
        lobby.handle(a, ClientMessage.Shoot(1, trackedPose(0.0, 0.0).copy(arena = null)))
        now += 1000
        lobby.handle(a, ClientMessage.PoseUpdate(Pose(Double.NaN, 0.0, 3.0, 0.0)))
        lobby.handle(a, ClientMessage.Voice(1, "A".repeat(Protocol.MAX_VOICE_CHARS + 1)))
        assertEquals(listOf(a to Violation.UNTRACKED_SHOT, a to Violation.BAD_DATA, a to Violation.BAD_DATA), violations)
    }

    @Test
    fun kickRemovesThePlayerAndTellsEveryone() {
        val (a, b) = startMatch()
        lobby.kick(a, "teleporting")
        assertEquals(listOf(ServerMessage.PlayerKicked(a, "a", "teleporting")), inbox.getValue(b).filterIsInstance<ServerMessage.PlayerKicked>())
        assertEquals(1, lobby.playerCount)
    }

    // ---------------------------------------------------------------- end to end over sockets

    private val servers = mutableListOf<GameServer>()
    private val clients = mutableListOf<GameClient>()

    @AfterTest
    fun tearDown() {
        clients.forEach { it.disconnect() }
        servers.forEach { it.stop() }
    }

    private class Bot(val inbox: CopyOnWriteArrayList<ServerMessage> = CopyOnWriteArrayList(), @Volatile var closed: Boolean = false)

    private fun connect(server: GameServer): Pair<GameClient, Bot> {
        val bot = Bot()
        val c = GameClient({ bot.inbox += it }, { bot.closed = true })
        c.connect("127.0.0.1", server.localPort)
        clients += c
        return c to bot
    }

    private inline fun <reified T : ServerMessage> Bot.await(pred: (T) -> Boolean = { true }): T {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (System.nanoTime() < deadline) {
            inbox.filterIsInstance<T>().firstOrNull(pred)?.let { return it }
            Thread.sleep(10)
        }
        fail("Timed out waiting for ${T::class.simpleName}; got $inbox")
    }

    private fun Bot.awaitClosed() {
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (!closed && System.nanoTime() < deadline) Thread.sleep(10)
        assertTrue(closed, "connection should have been closed")
    }

    @Test
    fun cheaterIsKickedAndLockedOutByDeviceAndIpForFifteenMinutes() {
        var clock = 5_000_000L
        // Loopback isn't exempt here so the test client can be treated like a remote phone.
        val s = GameServer(port = 0, singleLobby = false, tickIntervalMs = 50, exemptLoopback = false, clock = { clock })
            .also { it.start(); servers += it }
        val (honest, honestBot) = connect(s)
        honest.send(ClientMessage.Hello(Protocol.VERSION, "honest", create = true, arTracking = true, deviceId = "honest-device-0001"))
        val code = honestBot.await<ServerMessage.Welcome>().lobbyCode

        val (cheat, cheatBot) = connect(s)
        cheat.send(ClientMessage.Hello(Protocol.VERSION, "cheater", lobbyCode = code, arTracking = true, deviceId = "cheat-device-00001"))
        cheatBot.await<ServerMessage.Welcome>()
        // Teleport back and forth: 2.5 strikes each (fading slightly over time), threshold 5,
        // so the third jump triggers the kick.
        repeat(4) { i ->
            clock += 200
            cheat.send(ClientMessage.PoseUpdate(trackedPose(0.0, (i % 2) * 100.0)))
            Thread.sleep(150) // let the server see each pose at its own (fake) time
        }
        val err = cheatBot.await<ServerMessage.Error>()
        assertEquals(ErrorCode.LOCKED_OUT, err.code)
        assertTrue("teleporting" in err.message && "15 minutes" in err.message, err.message)
        cheatBot.awaitClosed()
        assertTrue(honestBot.await<ServerMessage.PlayerKicked>().reason.contains("teleporting"))

        // Reconnecting with the same device is refused at hello...
        val (again, againBot) = connect(s)
        assertEquals(ErrorCode.LOCKED_OUT, againBot.await<ServerMessage.Error>().code) // ...in fact already at connect (same IP)
        againBot.awaitClosed()
        again.disconnect()

        // ...until 15 minutes have passed.
        clock += GameServer.DEFAULT_LOCKOUT_MS
        val (later, laterBot) = connect(s)
        later.send(ClientMessage.Hello(Protocol.VERSION, "cheater", lobbyCode = code, arTracking = true, deviceId = "cheat-device-00001"))
        laterBot.await<ServerMessage.Welcome>()
    }

    @Test
    fun deviceLockoutAppliesEvenFromAnotherIpAndHostIsExempt() {
        var clock = 0L
        // ipLockout off: only the device is locked, so the same loopback IP can still connect.
        val s = GameServer(port = 0, singleLobby = true, tickIntervalMs = 50, exemptLoopback = false, ipLockout = false, clock = { clock })
            .also { it.start(); servers += it }
        val (host, hostBot) = connect(s)
        host.send(ClientMessage.Hello(Protocol.VERSION, "host", create = true, arTracking = true, deviceId = "host-device-000001"))
        hostBot.await<ServerMessage.Welcome>()
        val (cheat, cheatBot) = connect(s)
        cheat.send(ClientMessage.Hello(Protocol.VERSION, "spammer", arTracking = true, deviceId = "spam-device-000001"))
        cheatBot.await<ServerMessage.Welcome>()
        repeat(12) { cheat.send(ClientMessage.Voice(it, "A".repeat(Protocol.MAX_VOICE_CHARS + 1))) } // 0.5 each
        assertEquals(ErrorCode.LOCKED_OUT, cheatBot.await<ServerMessage.Error>().code)

        val (other, otherBot) = connect(s)
        other.send(ClientMessage.Hello(Protocol.VERSION, "spammer", arTracking = true, deviceId = "spam-device-000001"))
        assertEquals(ErrorCode.LOCKED_OUT, otherBot.await<ServerMessage.Error>().code)
        val (fresh, freshBot) = connect(s)
        fresh.send(ClientMessage.Hello(Protocol.VERSION, "new phone", arTracking = true, deviceId = "fresh-device-00001"))
        freshBot.await<ServerMessage.Welcome>()

        // With the default exemption, the Wi-Fi Direct host's own loopback client is never kicked.
        val hostServer = GameServer(port = 0, singleLobby = true, tickIntervalMs = 50).also { it.start(); servers += it }
        val (me, meBot) = connect(hostServer)
        me.send(ClientMessage.Hello(Protocol.VERSION, "me", create = true, arTracking = true, deviceId = "my-own-device-0001"))
        meBot.await<ServerMessage.Welcome>()
        repeat(20) { me.send(ClientMessage.Voice(it, "A".repeat(Protocol.MAX_VOICE_CHARS + 1))) }
        me.send(ClientMessage.Ping(42))
        meBot.await<ServerMessage.Pong>()
        assertTrue(meBot.inbox.none { it is ServerMessage.Error })
    }

    @Test
    fun helloWithoutDeviceIdIsRejected() {
        val s = GameServer(port = 0, singleLobby = true, tickIntervalMs = 50).also { it.start(); servers += it }
        val (c, bot) = connect(s)
        c.send(ClientMessage.Hello(Protocol.VERSION, "old app", create = true, arTracking = true))
        assertEquals(ErrorCode.BAD_REQUEST, bot.await<ServerMessage.Error>().code)
    }
}
