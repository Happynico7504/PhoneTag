package com.phonetag.core

import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decides whether a "laser" shot hits anyone.
 *
 * Phones have no real line of sight, so a shot is a ray from the shooter's GPS position along
 * their compass heading. A target is hit if the angle between that ray and the bearing to the
 * target is inside a cone whose width depends on distance and on how uncertain both GPS fixes
 * are: a player is modelled as a disc of [bodyRadiusM] plus part of the combined GPS error.
 */
object HitResolver {
    const val BODY_RADIUS_M = 0.6
    /** Fraction of the combined GPS accuracy radius we forgive. */
    const val GPS_SLACK_FACTOR = 0.5
    const val MAX_GPS_SLACK_M = 6.0
    const val MIN_CONE_DEG = 2.0
    const val MAX_CONE_DEG = 30.0

    data class Candidate(val id: Int, val pose: Pose)

    data class Hit(val targetId: Int, val distanceM: Double, val angleErrorDeg: Double)

    fun allowedConeDeg(distanceM: Double, shooterAccuracyM: Double, targetAccuracyM: Double): Double {
        val combined = sqrt(shooterAccuracyM * shooterAccuracyM + targetAccuracyM * targetAccuracyM)
        val radius = BODY_RADIUS_M + min(combined * GPS_SLACK_FACTOR, MAX_GPS_SLACK_M)
        val cone = Math.toDegrees(atan2(radius, distanceM.coerceAtLeast(0.1)))
        return cone.coerceIn(MIN_CONE_DEG, MAX_CONE_DEG)
    }

    /**
     * Returns the best hit, if any. Among candidates inside their cone we pick the one most
     * centred in the crosshair (relative to its cone), breaking ties by distance — i.e. the
     * player in front blocks the one behind.
     */
    fun resolve(shooter: Pose, candidates: List<Candidate>, maxRangeM: Double): Hit? {
        var best: Hit? = null
        var bestScore = Double.MAX_VALUE
        for (c in candidates) {
            val d = Geo.distanceM(shooter.lat, shooter.lon, c.pose.lat, c.pose.lon)
            if (d > maxRangeM) continue
            val bearing = Geo.bearingDeg(shooter.lat, shooter.lon, c.pose.lat, c.pose.lon)
            val err = Geo.angleDiffDeg(shooter.azimuthDeg, bearing)
            val cone = allowedConeDeg(d, shooter.accuracyM, c.pose.accuracyM)
            if (err > cone) continue
            // Normalised error (0 = dead centre, 1 = edge) plus a small distance term.
            val score = err / cone + d / maxRangeM * 0.25
            if (score < bestScore) {
                bestScore = score
                best = Hit(c.id, d, err)
            }
        }
        return best
    }
}
