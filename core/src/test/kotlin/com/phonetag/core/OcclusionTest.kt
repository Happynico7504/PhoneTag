package com.phonetag.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class OcclusionTest {
    /** A 4 m wide, 3 m tall wall facing the shooter, [z] metres ahead (along -Z). */
    private fun wallAt(z: Double, x: Double = 0.0) =
        Wall(Vec3(x, 1.5, -z), axisU = Vec3(1.0, 0.0, 0.0), axisV = Vec3(0.0, 1.0, 0.0), halfU = 2.0, halfV = 1.5)

    private val gps = Pose(48.137, 11.575, 4.0, azimuthDeg = 0.0)
    private fun arena(pos: Vec3, forward: Vec3 = Vec3.FORWARD) = ArenaPose(1, pos, forward)
    private val shooter = gps.copy(arena = arena(Vec3(0.0, 1.5, 0.0)))
    private val target = HitResolver.Candidate(2, gps.copy(arena = arena(Vec3(0.0, 1.5, -15.0))))

    @Test
    fun rayWallIntersection() {
        assertEquals(10.0, Occlusion.rayHit(Vec3(0.0, 1.5, 0.0), Vec3.FORWARD, 50.0, wallAt(10.0))!!, 1e-9)
        assertNull(Occlusion.rayHit(Vec3(0.0, 1.5, 0.0), Vec3.FORWARD, 9.0, wallAt(10.0)))      // beyond maxT
        assertNull(Occlusion.rayHit(Vec3(0.0, 1.5, 0.0), Vec3.FORWARD, 50.0, wallAt(10.0, x = 5.0))) // passes beside it
        assertNull(Occlusion.rayHit(Vec3(0.0, 1.5, 0.0), Vec3(1.0, 0.0, 0.0), 50.0, wallAt(10.0)))    // parallel
        assertNull(Occlusion.rayHit(Vec3(0.0, 1.5, 0.0), Vec3(0.0, 0.0, 1.0), 50.0, wallAt(10.0)))    // behind
        assertEquals(5.0, Occlusion.firstHit(Vec3(0.0, 1.5, 0.0), Vec3.FORWARD, 50.0, listOf(wallAt(10.0), wallAt(5.0)))!!, 1e-9)
    }

    @Test
    fun sharedWallBlocksArenaShot() {
        assertNotNull(HitResolver.resolve(shooter, listOf(target), 60.0, arenaEpoch = 1))
        val r = HitResolver.resolveDetailed(shooter, listOf(target), 60.0, arenaEpoch = 1, walls = listOf(wallAt(8.0)))
        assertNull(r.hit)
        assertEquals(2, r.blockedId)
        // A wall right behind the target (they lean on it) or off to the side doesn't block.
        assertNotNull(HitResolver.resolve(shooter, listOf(target), 60.0, arenaEpoch = 1, walls = listOf(wallAt(15.2), wallAt(8.0, x = 6.0))))
    }

    @Test
    fun depthAtCrosshairBlocksArenaShot() {
        // Something solid 4 m away, target 15 m away: blocked.
        assertNull(HitResolver.resolve(shooter, listOf(target), 60.0, VisionHint(clearDistanceM = 4.0), arenaEpoch = 1))
        // Depth beyond its reliable range says nothing.
        assertNotNull(HitResolver.resolve(shooter, listOf(target), 60.0, VisionHint(clearDistanceM = 9.0), arenaEpoch = 1))
        // Depth measuring the target itself (close target) doesn't block.
        val near = HitResolver.Candidate(3, gps.copy(arena = arena(Vec3(0.0, 1.5, -5.0))))
        assertNotNull(HitResolver.resolve(shooter, listOf(near), 60.0, VisionHint(clearDistanceM = 4.8), arenaEpoch = 1))
    }

    @Test
    fun depthBlocksGpsShotUnlessCameraSeesAPerson() {
        val s = Pose(48.137, 11.575, 3.0, azimuthDeg = 0.0)
        val (la, lo) = Geo.offset(s.lat, s.lon, 0.0, 12.0)
        val c = listOf(HitResolver.Candidate(2, Pose(la, lo, 3.0, 0.0)))
        assertNotNull(HitResolver.resolve(s, c, 60.0))
        assertNull(HitResolver.resolve(s, c, 60.0, VisionHint(clearDistanceM = 3.0)))
        // Within GPS error of the target distance: not enough evidence to block.
        assertNotNull(HitResolver.resolve(s, c, 60.0, VisionHint(clearDistanceM = 10.8)))
        // The near surface is a person in the crosshair, i.e. most likely the target.
        assertNotNull(HitResolver.resolve(s, c, 60.0, VisionHint(personInCrosshair = true, clearDistanceM = 7.0)))
    }

    @Test
    fun syncedShooterVsGpsTargetStillRespectsWalls() {
        val (la, lo) = Geo.offset(gps.lat, gps.lon, 0.0, 20.0)
        val gpsTarget = HitResolver.Candidate(2, Pose(la, lo, 4.0, 0.0)) // not synced
        val aimedNorth = Pose(gps.lat, gps.lon, 4.0, 0.0, arena = arena(Vec3(0.0, 1.5, 0.0)))
        assertNotNull(HitResolver.resolve(aimedNorth, listOf(gpsTarget), 60.0, arenaEpoch = 1))
        assertNull(HitResolver.resolve(aimedNorth, listOf(gpsTarget), 60.0, arenaEpoch = 1, walls = listOf(wallAt(6.0))))
    }

    @Test
    fun lobbySharesWallsAndReportsBlockedShots() {
        var now = 1_000_000L
        val lobby = Lobby("WALLS", "t") { now }
        val inbox = mutableMapOf<Int, MutableList<ServerMessage>>()
        fun join(n: String): Int {
            val box = mutableListOf<ServerMessage>()
            return (lobby.join(n, arTracking = true) { box += it } as Lobby.JoinResult.Joined).playerId.also { inbox[it] = box }
        }
        val a = join("a")
        val b = join("b")
        lobby.handle(b, ClientMessage.ReportWalls(1, listOf(wallAt(8.0), wallAt(8.0).copy(halfU = 1e9)))) // insane one dropped
        lobby.tick()
        val obstacles = inbox.getValue(a).filterIsInstance<ServerMessage.Obstacles>().last()
        assertEquals(listOf(wallAt(8.0)), obstacles.walls)

        // Late joiners get the map right away.
        val c = join("c")
        assertTrue(inbox.getValue(c).any { it is ServerMessage.Obstacles && it.walls.size == 1 })

        // Everyone must be synced before the match can start (c too).
        lobby.handle(a, ClientMessage.PoseUpdate(shooter))
        lobby.handle(b, ClientMessage.PoseUpdate(gps.copy(arena = arena(Vec3(0.0, 1.5, -15.0)))))
        lobby.handle(c, ClientMessage.PoseUpdate(gps.copy(arena = arena(Vec3(5.0, 1.5, 5.0)))))
        lobby.handle(a, ClientMessage.StartMatch)
        assertEquals(Phase.RUNNING, lobby.phase)
        now += Lobby.SPAWN_PROTECTION_MS
        lobby.handle(b, ClientMessage.PoseUpdate(gps.copy(arena = arena(Vec3(0.0, 1.5, -15.0)))))
        lobby.handle(a, ClientMessage.Shoot(1, shooter))
        val result = inbox.getValue(a).filterIsInstance<ServerMessage.ShotResult>().single()
        assertNull(result.hitPlayerId)
        assertTrue(result.blocked)

        // Resetting the arena (between matches) clears the map.
        now += lobby.settings.matchDurationSec * 1000L
        lobby.tick()
        lobby.handle(a, ClientMessage.ResetArena(a))
        assertEquals(emptyList(), inbox.getValue(b).filterIsInstance<ServerMessage.Obstacles>().last().walls)
    }
}
