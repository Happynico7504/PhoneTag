package com.phonetag.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class BystanderTest {
    private val shooter = trackedPose(0.0, 0.0, azimuth = 0.0) // facing north (-Z)
    private val seen = VisionHint(personInCrosshair = true)

    private fun player(id: Int, bearing: Double, dist: Double) = HitResolver.Candidate(id, trackedPose(bearing, dist))

    @Test
    fun personInCrosshairWithNoPlayerNearbyIsARandom() {
        val others = listOf(player(2, 90.0, 20.0), player(3, 200.0, 10.0)) // east and behind
        assertTrue(HitResolver.isBystanderShot(shooter, others, 60.0, seen))
        assertTrue(HitResolver.isBystanderShot(shooter, emptyList(), 60.0, seen))
    }

    @Test
    fun narrowlyMissedPlayerIsNeverARandom() {
        // 12° off at 20 m: outside the arena hit tolerance, but well inside the 15° clearance.
        assertFalse(HitResolver.isBystanderShot(shooter, listOf(player(2, 12.0, 20.0)), 60.0, seen))
        // Without arena data the (wider) GPS cone is doubled.
        val gpsOnly = HitResolver.Candidate(2, trackedPose(20.0, 8.0).copy(arena = null))
        assertFalse(HitResolver.isBystanderShot(shooter, listOf(gpsOnly), 60.0, seen))
    }

    @Test
    fun noPersonSeenOrPersonFarOutOfRangeIsNotARandom() {
        assertFalse(HitResolver.isBystanderShot(shooter, emptyList(), 60.0, VisionHint(personInCrosshair = false)))
        assertFalse(HitResolver.isBystanderShot(shooter, emptyList(), 60.0, null))
        assertFalse(HitResolver.isBystanderShot(shooter, emptyList(), 60.0, VisionHint(true, estDistanceM = 120.0)))
    }

    @Test
    fun lobbyCountsRandomsBroadcastsThemAndPutsThemOnTheScoreboard() {
        var now = 1_000_000L
        val lobby = Lobby("RANDO", "r") { now }
        val inbox = mutableMapOf<Int, MutableList<ServerMessage>>()
        fun join(n: String) = mutableListOf<ServerMessage>().let { box ->
            (lobby.join(n, arTracking = true) { box += it } as Lobby.JoinResult.Joined).playerId.also { inbox[it] = box }
        }
        val a = join("a")
        val b = join("b")
        lobby.handle(a, ClientMessage.PoseUpdate(shooter))
        lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(90.0, 20.0)))
        lobby.handle(a, ClientMessage.StartMatch)
        now += Lobby.SPAWN_PROTECTION_MS

        repeat(2) {
            now += 1000
            lobby.handle(b, ClientMessage.PoseUpdate(trackedPose(90.0, 20.0)))
            lobby.handle(a, ClientMessage.Shoot(it, shooter, seen)) // aiming north at a passer-by
        }
        val results = inbox.getValue(a).filterIsInstance<ServerMessage.ShotResult>()
        assertTrue(results.all { it.random && it.hitPlayerId == null })
        assertEquals(listOf(1, 2), inbox.getValue(b).filterIsInstance<ServerMessage.RandomShot>().map { it.total })

        // A shot that actually hits a player is never a random.
        now += 1000
        lobby.handle(a, ClientMessage.Shoot(9, trackedPose(0.0, 0.0, azimuth = 90.0), seen))
        assertEquals(b, inbox.getValue(a).filterIsInstance<ServerMessage.ShotResult>().last().hitPlayerId)

        lobby.tick()
        assertEquals(2, inbox.getValue(b).filterIsInstance<ServerMessage.Snapshot>().last().players.first { it.id == a }.randoms)
        now += lobby.settings.matchDurationSec * 1000L
        lobby.tick()
        val end = inbox.getValue(b).filterIsInstance<ServerMessage.MatchEnded>().single()
        assertEquals(2, end.scores.first { it.id == a }.randoms)
    }
}
