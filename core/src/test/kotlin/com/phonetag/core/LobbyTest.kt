package com.phonetag.core

import kotlin.math.cos
import kotlin.math.sin
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * A pose that is both a GPS fix and an arena pose (epoch 1) describing the same spot: [dist]
 * metres from a fixed origin along [bearing], facing [azimuth]. Arena +X = east, -Z = north.
 */
fun trackedPose(bearing: Double, dist: Double, azimuth: Double = 0.0, epoch: Int = 1): Pose {
    val (la, lo) = Geo.offset(48.137, 11.575, bearing, dist)
    val rb = Math.toRadians(bearing)
    val ra = Math.toRadians(azimuth)
    return Pose(
        la, lo, 3.0, azimuth,
        arena = ArenaPose(epoch, Vec3(sin(rb) * dist, 1.5, -cos(rb) * dist), Vec3(sin(ra), 0.0, -cos(ra))),
    )
}

class LobbyTest {
    private var now = 1_000_000L
    private val lobby = Lobby("ABCDE", "test") { now }
    private val inbox = mutableMapOf<String, MutableList<ServerMessage>>()

    private fun join(name: String): Int {
        val box = mutableListOf<ServerMessage>().also { inbox[name] = it }
        val r = lobby.join(name, arTracking = true) { box += it }
        return (r as Lobby.JoinResult.Joined).playerId
    }

    private val origin = trackedPose(0.0, 0.0)

    /** Everyone reports a tracked pose (so the match may start); [a] at the origin, others 15 m north. */
    private fun syncAndStart(host: Int, vararg others: Int) {
        lobby.handle(host, ClientMessage.PoseUpdate(origin))
        others.forEach { lobby.handle(it, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0))) }
        lobby.handle(host, ClientMessage.StartMatch)
        assertEquals(Phase.RUNNING, lobby.phase)
        now += Lobby.SPAWN_PROTECTION_MS
    }

    @Test
    fun lobbyIsCappedAtSixteenPlayers() {
        repeat(16) { join("p$it") }
        val r = lobby.join("seventeenth", arTracking = true) {}
        assertIs<Lobby.JoinResult.Rejected>(r)
        assertEquals(ErrorCode.LOBBY_FULL, r.code)
        assertEquals(16, lobby.playerCount)
    }

    @Test
    fun phonesWithoutArTrackingCantJoin() {
        val r = lobby.join("no-ar", arTracking = false) {}
        assertIs<Lobby.JoinResult.Rejected>(r)
        assertEquals(ErrorCode.AR_REQUIRED, r.code)
        assertEquals(0, lobby.playerCount)
    }

    @Test
    fun hostCannotRaiseCapAboveSixteen() {
        val host = join("host")
        lobby.handle(host, ClientMessage.UpdateSettings(GameSettings(maxPlayers = 64)))
        assertEquals(16, lobby.settings.maxPlayers)
    }

    @Test
    fun hostCanLowerCapButNotBelowCurrentCount() {
        val host = join("host")
        join("b"); join("c")
        lobby.handle(host, ClientMessage.UpdateSettings(GameSettings(maxPlayers = 2)))
        assertEquals(3, lobby.settings.maxPlayers)
        assertIs<Lobby.JoinResult.Rejected>(lobby.join("d", arTracking = true) {})
    }

    @Test
    fun onlyHostCanStart() {
        join("host")
        val guest = join("guest")
        lobby.handle(guest, ClientMessage.StartMatch)
        assertEquals(Phase.LOBBY, lobby.phase)
        assertTrue(inbox.getValue("guest").any { it is ServerMessage.Error && it.code == ErrorCode.NOT_HOST })
    }

    @Test
    fun matchWaitsUntilEveryoneIsSynced() {
        val host = join("host")
        val b = join("b")
        lobby.handle(host, ClientMessage.PoseUpdate(origin))
        lobby.handle(b, ClientMessage.PoseUpdate(origin.copy(arena = null))) // GPS only: not synced
        lobby.handle(host, ClientMessage.StartMatch)
        assertEquals(Phase.LOBBY, lobby.phase)
        val err = inbox.getValue("host").filterIsInstance<ServerMessage.Error>().last()
        assertEquals(ErrorCode.NOT_SYNCED, err.code)
        assertTrue("b" in err.message)

        // A stale arena pose doesn't count either.
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0)))
        now += Lobby.STALE_POSE_MS + 1
        lobby.handle(host, ClientMessage.PoseUpdate(origin))
        lobby.handle(host, ClientMessage.StartMatch)
        assertEquals(Phase.LOBBY, lobby.phase)

        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0)))
        lobby.handle(host, ClientMessage.StartMatch)
        assertEquals(Phase.RUNNING, lobby.phase)
    }

    @Test
    fun fourHitsTagOutAndPlayerRespawns() {
        val a = join("a")
        val b = join("b")
        syncAndStart(a, b)
        repeat(4) { i ->
            now += 1000
            lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0)))
            lobby.handle(a, ClientMessage.Shoot(i, origin))
        }
        val tags = inbox.getValue("a").filterIsInstance<ServerMessage.Tagged>()
        assertEquals(listOf(ServerMessage.Tagged(a, b)), tags)
        val hits = inbox.getValue("b").filterIsInstance<ServerMessage.HitBy>()
        assertEquals(listOf(75, 50, 25, 0), hits.map { it.health })
        assertTrue(inbox.getValue("a").filterIsInstance<ServerMessage.ShotResult>().all { it.method == HitMethod.ARENA })

        // Dead players can't shoot.
        lobby.handle(b, ClientMessage.Shoot(99, trackedPose(0.0, 15.0, azimuth = 180.0)))
        assertTrue(inbox.getValue("b").filterIsInstance<ServerMessage.ShotResult>().isEmpty())

        now += lobby.settings.respawnSec * 1000L
        lobby.tick()
        val snap = inbox.getValue("b").filterIsInstance<ServerMessage.Snapshot>().last()
        assertTrue(snap.players.first { it.id == b }.alive)
        assertEquals(1, snap.players.first { it.id == a }.score)
    }

    @Test
    fun untrackedShooterCantFireButUntrackedTargetCanStillBeHit() {
        val a = join("a")
        val b = join("b")
        syncAndStart(a, b)
        // a lost tracking: the shot is rejected, nobody gets hurt.
        lobby.handle(a, ClientMessage.Shoot(1, origin.copy(arena = null)))
        val rejected = inbox.getValue("a").filterIsInstance<ServerMessage.ShotResult>().single()
        assertTrue(rejected.untracked)
        assertTrue(inbox.getValue("b").none { it is ServerMessage.HitBy })

        // b covers their camera (no arena pose any more): still hittable through GPS.
        now += 1000
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(0.0, 15.0).copy(arena = null)))
        lobby.handle(a, ClientMessage.Shoot(2, origin))
        val hit = inbox.getValue("a").filterIsInstance<ServerMessage.ShotResult>().last()
        assertEquals(b, hit.hitPlayerId)
        assertEquals(HitMethod.GPS, hit.method)
    }

    @Test
    fun fireCooldownIsEnforced() {
        val a = join("a")
        val b = join("b")
        syncAndStart(a, b)
        lobby.handle(a, ClientMessage.Shoot(1, origin))
        lobby.handle(a, ClientMessage.Shoot(2, origin)) // same instant: ignored
        assertEquals(1, inbox.getValue("a").filterIsInstance<ServerMessage.ShotResult>().size)
    }

    @Test
    fun noFriendlyFireInTeamMode() {
        val a = join("a")
        val b = join("b")
        val c = join("c")
        lobby.handle(a, ClientMessage.UpdateSettings(GameSettings(mode = GameMode.TEAMS)))
        lobby.handle(a, ClientMessage.SetTeam(Team.RED))
        lobby.handle(b, ClientMessage.SetTeam(Team.RED))
        lobby.handle(c, ClientMessage.PoseUpdate(trackedPose(180.0, 15.0)))
        syncAndStart(a, b)
        lobby.handle(a, ClientMessage.Shoot(1, origin))
        val result = inbox.getValue("a").filterIsInstance<ServerMessage.ShotResult>().single()
        assertEquals(null, result.hitPlayerId)
    }

    @Test
    fun arenaCantBeResetMidMatch() {
        val a = join("a")
        val b = join("b")
        syncAndStart(a, b)
        lobby.handle(a, ClientMessage.ResetArena(b))
        assertEquals(1, lobby.arena.epoch)
        assertTrue(inbox.getValue("a").any { it is ServerMessage.Error && it.code == ErrorCode.MATCH_IN_PROGRESS })
    }

    @Test
    fun matchEndsWhenTimeRunsOut() {
        val a = join("a")
        val b = join("b")
        syncAndStart(a, b)
        now += lobby.settings.matchDurationSec * 1000L
        lobby.tick()
        assertEquals(Phase.ENDED, lobby.phase)
        assertTrue(inbox.getValue("b").any { it is ServerMessage.MatchEnded })
        lobby.handle(a, ClientMessage.BackToLobby)
        assertEquals(Phase.LOBBY, lobby.phase)
    }

    @Test
    fun hostMigratesWhenHostLeaves() {
        val a = join("a")
        val b = join("b")
        lobby.leave(a)
        val state = inbox.getValue("b").filterIsInstance<ServerMessage.LobbyState>().last()
        assertEquals(b, state.hostId)
    }
}

class LobbyArenaTest {
    private var now = 1_000_000L
    private val lobby = Lobby("ABCDE", "test") { now }
    private val inbox = mutableMapOf<Int, MutableList<ServerMessage>>()

    private fun join(name: String): Int {
        val box = mutableListOf<ServerMessage>()
        val id = (lobby.join(name, arTracking = true) { box += it } as Lobby.JoinResult.Joined).playerId
        inbox[id] = box
        return id
    }

    private fun lastLobby(id: Int) = inbox.getValue(id).filterIsInstance<ServerMessage.LobbyState>().last()

    @Test
    fun firstPlayerDefinesTheArenaAndHostCanReset() {
        val host = join("host")
        val b = join("b")
        assertEquals(ArenaInfo(1, host), lastLobby(b).arena)

        lobby.handle(host, ClientMessage.ResetArena(b))
        assertEquals(ArenaInfo(2, b), lastLobby(host).arena)

        // Non-hosts can't reset.
        lobby.handle(b, ClientMessage.ResetArena(b))
        assertEquals(2, lobby.arena.epoch)
    }

    @Test
    fun beaconsAndArenaPosesFromOldEpochsAreDropped() {
        val a = join("a")
        val b = join("b")
        val screen = Rigid(Vec3(1.0, 1.4, -2.0), Quat.IDENTITY)
        lobby.handle(a, ClientMessage.SetBeacon(epoch = 1, screen = screen))
        lobby.handle(a, ClientMessage.PoseUpdate(Pose(1.0, 1.0, 3.0, 0.0, arena = ArenaPose(1, Vec3.ZERO, Vec3.FORWARD))))
        lobby.handle(b, ClientMessage.PoseUpdate(Pose(1.0, 1.0, 3.0, 0.0, arena = ArenaPose(7, Vec3.ZERO, Vec3.FORWARD))))
        lobby.tick() // snapshots also flow in the lobby, for syncing
        val snap = inbox.getValue(b).filterIsInstance<ServerMessage.Snapshot>().last()
        assertEquals(screen, snap.players.first { it.id == a }.beacon)
        assertEquals(1, snap.players.first { it.id == a }.pose?.arena?.epoch)
        assertEquals(null, snap.players.first { it.id == b }.pose?.arena)

        // Beacons go stale if the phone stops refreshing them.
        now += Lobby.STALE_BEACON_MS + 1
        lobby.tick()
        assertEquals(null, inbox.getValue(b).filterIsInstance<ServerMessage.Snapshot>().last().players.first { it.id == a }.beacon)
    }

    @Test
    fun arenaShotUsesPreciseRayCast() {
        val a = join("a")
        val b = join("b")
        val gps = Pose(48.0, 11.0, 5.0, 90.0) // GPS heading points elsewhere entirely
        lobby.handle(a, ClientMessage.PoseUpdate(gps.copy(arena = ArenaPose(1, Vec3(0.0, 1.5, 0.0), Vec3.FORWARD))))
        lobby.handle(b, ClientMessage.PoseUpdate(gps.copy(arena = ArenaPose(1, Vec3(0.0, 1.5, -12.0), Vec3.FORWARD))))
        lobby.handle(a, ClientMessage.StartMatch)
        now += Lobby.SPAWN_PROTECTION_MS
        lobby.handle(b, ClientMessage.PoseUpdate(gps.copy(arena = ArenaPose(1, Vec3(0.0, 1.5, -12.0), Vec3.FORWARD))))
        lobby.handle(a, ClientMessage.Shoot(1, gps.copy(arena = ArenaPose(1, Vec3(0.0, 1.5, 0.0), Vec3.FORWARD))))
        val result = inbox.getValue(a).filterIsInstance<ServerMessage.ShotResult>().single()
        assertEquals(b, result.hitPlayerId)
        assertEquals(HitMethod.ARENA, result.method)
    }
}
