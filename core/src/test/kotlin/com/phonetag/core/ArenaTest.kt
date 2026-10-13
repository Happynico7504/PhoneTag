package com.phonetag.core

import kotlin.math.PI
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ArenaTest {
    private fun assertVec(expected: Vec3, actual: Vec3, tol: Double = 1e-6) {
        assertTrue((expected - actual).length() < tol, "expected $expected but was $actual")
    }

    @Test
    fun rigidAlgebra() {
        val t = Rigid(Vec3(1.0, 2.0, 3.0), Quat.axisAngle(Vec3(0.3, 1.0, 0.2), 0.9))
        val p = Vec3(-0.5, 0.7, 4.0)
        assertVec(p, t.inverse().transform(t.transform(p)))
        assertVec(p, (t * t.inverse()).transform(p))
        // Yaw of +90° about +Y turns forward (-Z) to -X.
        assertVec(Vec3(-1.0, 0.0, 0.0), Quat.yaw(PI / 2).rotate(Vec3.FORWARD))
        assertEquals(PI / 2, Quat.yaw(PI / 2).yaw(), 1e-9)
        // Pitch doesn't change yaw.
        val pitched = Quat.yaw(0.4) * Quat.axisAngle(Vec3(1.0, 0.0, 0.0), 0.5)
        assertEquals(0.4, pitched.yaw(), 1e-9)
    }

    /**
     * Two phones with unrelated gravity-aligned worlds. A is synced (its arena frame is known),
     * shows its marker; B sees that marker and must recover arena→B-world exactly.
     */
    @Test
    fun calibrationRecoversTheSharedFrame() {
        val arenaToWorldA = Rigid(Vec3(3.0, -1.2, 7.5), Quat.yaw(1.1))
        val arenaToWorldB = Rigid(Vec3(-20.0, 0.4, 2.0), Quat.yaw(-2.3)) // ground truth to recover

        // A holds the phone up, slightly tilted, screen facing B.
        val cameraA = Rigid(Vec3(1.0, 1.5, -2.0), Quat.yaw(0.7) * Quat.axisAngle(Vec3(1.0, 0.0, 0.0), 0.15))
        val screenInWorldA = ArenaCalibration.screenPose(cameraA)
        val screenInArena = arenaToWorldA.inverse() * screenInWorldA

        // What B's ARCore reports for the marker: the same physical pose, in B's world.
        val markerInWorldB = arenaToWorldB * screenInArena

        val recovered = ArenaCalibration.arenaToWorld(markerInWorldB, screenInArena)
        assertVec(arenaToWorldB.position, recovered.position)
        assertEquals(arenaToWorldB.rotation.yaw(), recovered.rotation.yaw(), 1e-9)

        // A point in the arena maps to the same place for both phones.
        val p = Vec3(4.0, 1.0, -9.0)
        assertVec(arenaToWorldB.transform(p), recovered.transform(p))
    }

    @Test
    fun screenFacesAwayFromTheCamera() {
        // Camera looks along -Z, so the image normal (+Y of the image frame) must point along +Z.
        val screen = ArenaCalibration.screenPose(Rigid.IDENTITY)
        assertVec(Vec3(0.0, 0.0, 1.0), screen.rotation.rotate(Vec3.UP))
        assertVec(Vec3(0.0, -1.0, 0.0), screen.rotation.rotate(Vec3(0.0, 0.0, 1.0))) // image bottom = down
    }

    @Test
    fun averagingRejectsInconsistentSamplesAndSmoothsNoise() {
        val truth = Rigid(Vec3(2.0, 0.0, -3.0), Quat.yaw(0.5))
        val rnd = Random(1)
        val noisy = List(20) {
            Rigid(truth.position + Vec3(rnd.nextDouble(-0.03, 0.03), 0.0, rnd.nextDouble(-0.03, 0.03)), Quat.yaw(0.5 + rnd.nextDouble(-0.02, 0.02)))
        }
        val avg = assertNotNull(ArenaCalibration.average(noisy))
        assertTrue((avg.position - truth.position).length() < 0.02)
        assertEquals(0.5, avg.rotation.yaw(), 0.01)

        val moved = noisy + Rigid(truth.position + Vec3(0.5, 0.0, 0.0), truth.rotation)
        assertNull(ArenaCalibration.average(moved))
    }

    // ------------------------------------------------------------------ fused hit detection

    private val gps = Pose(48.137, 11.575, 8.0, azimuthDeg = 0.0)

    private fun arena(pos: Vec3, forward: Vec3 = Vec3.FORWARD, epoch: Int = 1) = ArenaPose(epoch, pos, forward)

    @Test
    fun arenaRayHitsAndMissesPrecisely() {
        val shooter = gps.copy(arena = arena(Vec3(0.0, 1.5, 0.0)))
        // Target 20 m straight ahead (-Z). GPS says they're on top of each other: GPS is ignored.
        val ahead = HitResolver.Candidate(2, gps.copy(arena = arena(Vec3(0.0, 1.5, -20.0))))
        val hit = assertNotNull(HitResolver.resolve(shooter, listOf(ahead), 60.0, arenaEpoch = 1))
        assertEquals(HitMethod.ARENA, hit.method)
        assertEquals(20.0, hit.distanceM, 1e-6)

        // 1.5 m to the side at 20 m: tolerance is 0.35 + 0.15 + 0.4 = 0.9 m → miss, although the
        // (identical) GPS fixes would have called it a hit.
        val side = HitResolver.Candidate(3, gps.copy(arena = arena(Vec3(1.5, 1.5, -20.0))))
        assertNull(HitResolver.resolve(shooter, listOf(side), 60.0, arenaEpoch = 1))

        // Aiming low at the legs still hits the body capsule.
        val low = shooter.copy(arena = arena(Vec3(0.0, 1.5, 0.0), Vec3(0.0, -0.05, -1.0)))
        assertNotNull(HitResolver.resolve(low, listOf(ahead), 60.0, arenaEpoch = 1))

        // Behind the shooter: miss.
        val behind = HitResolver.Candidate(4, gps.copy(arena = arena(Vec3(0.0, 1.5, 10.0))))
        assertNull(HitResolver.resolve(shooter, listOf(behind), 60.0, arenaEpoch = 1))
    }

    @Test
    fun staleArenaEpochFallsBackToGps() {
        val shooter = gps.copy(arena = arena(Vec3(0.0, 1.5, 0.0), epoch = 1))
        val (la, lo) = Geo.offset(gps.lat, gps.lon, 0.0, 15.0)
        // Target's arena pose says "far to the side" but it is from another epoch, so GPS decides.
        val target = HitResolver.Candidate(2, Pose(la, lo, 4.0, 0.0, arena = arena(Vec3(30.0, 1.5, 0.0), epoch = 2)))
        val hit = assertNotNull(HitResolver.resolve(shooter, listOf(target), 60.0, arenaEpoch = 1))
        assertEquals(HitMethod.GPS, hit.method)
    }

    @Test
    fun visionWidensOrNarrowsTheGpsCone() {
        val shooter = Pose(48.137, 11.575, 3.0, azimuthDeg = 0.0)
        // Target at 15 m, 8° off the compass heading: base cone ≈ atan(0.6+2.1 / 15) ≈ 10.2°.
        val (la, lo) = Geo.offset(shooter.lat, shooter.lon, 8.0, 15.0)
        val c = listOf(HitResolver.Candidate(2, Pose(la, lo, 3.0, 0.0)))
        assertEquals(HitMethod.GPS, HitResolver.resolve(shooter, c, 60.0)?.method)
        // Camera saw nobody under the crosshair: narrowed cone → miss.
        assertNull(HitResolver.resolve(shooter, c, 60.0, VisionHint(personInCrosshair = false)))

        // 14° off: miss on GPS alone, but a person in the crosshair at a plausible distance widens it.
        val (la2, lo2) = Geo.offset(shooter.lat, shooter.lon, 14.0, 15.0)
        val c2 = listOf(HitResolver.Candidate(2, Pose(la2, lo2, 3.0, 0.0)))
        assertNull(HitResolver.resolve(shooter, c2, 60.0))
        assertEquals(HitMethod.GPS_VISION, HitResolver.resolve(shooter, c2, 60.0, VisionHint(true, estDistanceM = 13.0))?.method)
        // ...but not if the detected person is clearly somebody much closer.
        assertNull(HitResolver.resolve(shooter, c2, 60.0, VisionHint(true, estDistanceM = 3.0)))
    }

    @Test
    fun rayToSegmentGeometry() {
        val (s, d) = HitResolver.rayToSegment(Vec3.ZERO, Vec3(0.0, 0.0, -1.0), 100.0, Vec3(1.0, -1.0, -5.0), Vec3(1.0, 1.0, -5.0))
        assertEquals(5.0, s, 1e-9)
        assertEquals(1.0, d, 1e-9)
    }
}
