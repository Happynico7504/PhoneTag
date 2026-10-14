package com.phonetag.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HitResolverTest {
    private val lat = 48.137
    private val lon = 11.575

    private fun at(bearing: Double, dist: Double, acc: Double = 3.0): Pose {
        val (la, lo) = Geo.offset(lat, lon, bearing, dist)
        return Pose(la, lo, acc, 0.0)
    }

    @Test
    fun geoRoundTrip() {
        val p = at(90.0, 25.0)
        assertEquals(25.0, Geo.distanceM(lat, lon, p.lat, p.lon), 0.01)
        assertEquals(90.0, Geo.bearingDeg(lat, lon, p.lat, p.lon), 0.1)
        assertEquals(20.0, Geo.angleDiffDeg(350.0, 10.0), 1e-9)
        assertEquals(-20.0, Geo.signedAngleDeg(10.0, 350.0), 1e-9)
    }

    @Test
    fun aimedShotHits() {
        val shooter = Pose(lat, lon, 3.0, azimuthDeg = 45.0)
        val hit = HitResolver.resolve(shooter, listOf(HitResolver.Candidate(2, at(46.0, 20.0))), 60.0)
        assertNotNull(hit)
        assertEquals(2, hit.targetId)
    }

    @Test
    fun wideMissDoesNotHit() {
        val shooter = Pose(lat, lon, 3.0, azimuthDeg = 90.0)
        assertNull(HitResolver.resolve(shooter, listOf(HitResolver.Candidate(2, at(45.0, 20.0))), 60.0))
    }

    @Test
    fun outOfRangeDoesNotHit() {
        val shooter = Pose(lat, lon, 3.0, azimuthDeg = 0.0)
        assertNull(HitResolver.resolve(shooter, listOf(HitResolver.Candidate(2, at(0.0, 80.0))), 60.0))
    }

    @Test
    fun closerTargetInLineBlocksFartherOne() {
        val shooter = Pose(lat, lon, 3.0, azimuthDeg = 0.0)
        val hit = HitResolver.resolve(
            shooter,
            listOf(HitResolver.Candidate(3, at(0.0, 40.0)), HitResolver.Candidate(2, at(0.0, 10.0))),
            60.0,
        )
        assertEquals(2, hit?.targetId)
    }

    @Test
    fun coneWidensWithGpsErrorButStaysBounded() {
        val tight = HitResolver.allowedConeDeg(30.0, 1.0, 1.0)
        val loose = HitResolver.allowedConeDeg(30.0, 10.0, 10.0)
        assertTrue(loose > tight)
        assertTrue(HitResolver.allowedConeDeg(0.5, 50.0, 50.0) <= HitResolver.MAX_CONE_DEG)
        assertTrue(HitResolver.allowedConeDeg(1000.0, 0.0, 0.0) >= HitResolver.MIN_CONE_DEG)
    }
}
