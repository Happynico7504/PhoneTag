package com.phonetag.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class LobbyTest {
    private var now = 1_000_000L
    private val lobby = Lobby("ABCDE", "test") { now }
    private val inbox = mutableMapOf<String, MutableList<ServerMessage>>()

    private fun join(name: String): Int {
        val box = mutableListOf<ServerMessage>().also { inbox[name] = it }
        val r = lobby.join(name) { box += it }
        return (r as Lobby.JoinResult.Joined).playerId
    }

    private val origin = Pose(48.137, 11.575, 3.0, 0.0)
    private fun poseAt(bearing: Double, dist: Double, azimuth: Double = 0.0): Pose {
        val (la, lo) = Geo.offset(origin.lat, origin.lon, bearing, dist)
        return Pose(la, lo, 3.0, azimuth)
    }

    @Test
    fun lobbyIsCappedAtSixteenPlayers() {
        repeat(16) { join("p$it") }
        val r = lobby.join("seventeenth") {}
        assertIs<Lobby.JoinResult.Rejected>(r)
        assertEquals(ErrorCode.LOBBY_FULL, r.code)
        assertEquals(16, lobby.playerCount)
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
        assertIs<Lobby.JoinResult.Rejected>(lobby.join("d") {})
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
    fun fourHitsTagOutAndPlayerRespawns() {
        val a = join("a")
        val b = join("b")
        lobby.handle(a, ClientMessage.StartMatch)
        now += Lobby.SPAWN_PROTECTION_MS
        lobby.handle(b, ClientMessage.PoseUpdate(poseAt(0.0, 15.0)))
        repeat(4) { i ->
            now += 1000
            lobby.handle(b, ClientMessage.PoseUpdate(poseAt(0.0, 15.0)))
            lobby.handle(a, ClientMessage.Shoot(i, origin))
        }
        val tags = inbox.getValue("a").filterIsInstance<ServerMessage.Tagged>()
        assertEquals(listOf(ServerMessage.Tagged(a, b)), tags)
        val hits = inbox.getValue("b").filterIsInstance<ServerMessage.HitBy>()
        assertEquals(listOf(75, 50, 25, 0), hits.map { it.health })

        // Dead players can't shoot.
        lobby.handle(b, ClientMessage.Shoot(99, poseAt(0.0, 15.0, azimuth = 180.0)))
        assertTrue(inbox.getValue("b").filterIsInstance<ServerMessage.ShotResult>().isEmpty())

        now += lobby.settings.respawnSec * 1000L
        lobby.tick()
        val snap = inbox.getValue("b").filterIsInstance<ServerMessage.Snapshot>().last()
        assertTrue(snap.players.first { it.id == b }.alive)
        assertEquals(1, snap.players.first { it.id == a }.score)
    }

    @Test
    fun fireCooldownIsEnforced() {
        val a = join("a")
        val b = join("b")
        lobby.handle(a, ClientMessage.StartMatch)
        now += Lobby.SPAWN_PROTECTION_MS
        lobby.handle(b, ClientMessage.PoseUpdate(poseAt(0.0, 15.0)))
        lobby.handle(a, ClientMessage.Shoot(1, origin))
        lobby.handle(a, ClientMessage.Shoot(2, origin)) // same instant: ignored
        assertEquals(1, inbox.getValue("a").filterIsInstance<ServerMessage.ShotResult>().size)
    }

    @Test
    fun noFriendlyFireInTeamMode() {
        val a = join("a")
        val b = join("b")
        join("c")
        lobby.handle(a, ClientMessage.UpdateSettings(GameSettings(mode = GameMode.TEAMS)))
        lobby.handle(a, ClientMessage.SetTeam(Team.RED))
        lobby.handle(b, ClientMessage.SetTeam(Team.RED))
        lobby.handle(a, ClientMessage.StartMatch)
        now += Lobby.SPAWN_PROTECTION_MS
        lobby.handle(b, ClientMessage.PoseUpdate(poseAt(0.0, 15.0)))
        lobby.handle(a, ClientMessage.Shoot(1, origin))
        val result = inbox.getValue("a").filterIsInstance<ServerMessage.ShotResult>().single()
        assertEquals(null, result.hitPlayerId)
    }

    @Test
    fun matchEndsWhenTimeRunsOut() {
        val a = join("a")
        join("b")
        lobby.handle(a, ClientMessage.StartMatch)
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
        val id = (lobby.join(name) { box += it } as Lobby.JoinResult.Joined).playerId
        inbox[id] = box
        return id
    }

    private fun lastLobby(id: Int) = inbox.getValue(id).filterIsInstance<ServerMessage.LobbyState>().last()

    @Test
    fun firstArCapablePhoneBecomesReferenceAndHostCanReset() {
        val host = join("host")
        val b = join("b")
        lobby.handle(b, ClientMessage.SetCapabilities(ar = true))
        assertEquals(ArenaInfo(1, b), lastLobby(host).arena)
        assertTrue(lastLobby(host).players.first { it.id == b }.arCapable)

        lobby.handle(host, ClientMessage.SetCapabilities(ar = true))
        lobby.handle(host, ClientMessage.ResetArena(host))
        assertEquals(ArenaInfo(2, host), lastLobby(b).arena)

        // Non-hosts can't reset, and the reference must be AR-capable.
        lobby.handle(b, ClientMessage.ResetArena(b))
        assertEquals(2, lobby.arena.epoch)
    }

    @Test
    fun beaconsAndArenaPosesFromOldEpochsAreDropped() {
        val a = join("a")
        val b = join("b")
        lobby.handle(a, ClientMessage.SetCapabilities(ar = true))
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
        lobby.handle(a, ClientMessage.SetCapabilities(ar = true))
        lobby.handle(a, ClientMessage.StartMatch)
        now += Lobby.SPAWN_PROTECTION_MS
        val gps = Pose(48.0, 11.0, 5.0, 90.0) // GPS heading points elsewhere entirely
        lobby.handle(b, ClientMessage.PoseUpdate(gps.copy(arena = ArenaPose(1, Vec3(0.0, 1.5, -12.0), Vec3.FORWARD))))
        lobby.handle(a, ClientMessage.Shoot(1, gps.copy(arena = ArenaPose(1, Vec3(0.0, 1.5, 0.0), Vec3.FORWARD))))
        val result = inbox.getValue(a).filterIsInstance<ServerMessage.ShotResult>().single()
        assertEquals(b, result.hitPlayerId)
        assertEquals(HitMethod.ARENA, result.method)
    }
}
