package com.phonetag.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/** Small-area geodesy helpers. Laser tag distances are tens of metres, so haversine is plenty. */
object Geo {
    private const val EARTH_RADIUS_M = 6_371_000.0

    fun distanceM(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2) * sin(dLon / 2)
        return 2 * EARTH_RADIUS_M * atan2(sqrt(a), sqrt(1 - a))
    }

    /** Initial bearing from point 1 to point 2, degrees clockwise from true north in [0, 360). */
    fun bearingDeg(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val p1 = Math.toRadians(lat1)
        val p2 = Math.toRadians(lat2)
        val dLon = Math.toRadians(lon2 - lon1)
        val y = sin(dLon) * cos(p2)
        val x = cos(p1) * sin(p2) - sin(p1) * cos(p2) * cos(dLon)
        return normalizeDeg(Math.toDegrees(atan2(y, x)))
    }

    fun normalizeDeg(deg: Double): Double = ((deg % 360.0) + 360.0) % 360.0

    /** Smallest absolute difference between two bearings, in [0, 180]. */
    fun angleDiffDeg(a: Double, b: Double): Double {
        val d = abs(normalizeDeg(a) - normalizeDeg(b))
        return if (d > 180.0) 360.0 - d else d
    }

    /** Signed difference `to - from` in (-180, 180]; positive means clockwise. */
    fun signedAngleDeg(from: Double, to: Double): Double {
        val d = normalizeDeg(to - from)
        return if (d > 180.0) d - 360.0 else d
    }

    /** Destination point moving [distanceM] along [bearingDeg]; handy for tests and simulations. */
    fun offset(lat: Double, lon: Double, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val br = Math.toRadians(bearingDeg)
        val dr = distanceM / EARTH_RADIUS_M
        val p1 = Math.toRadians(lat)
        val l1 = Math.toRadians(lon)
        val p2 = kotlin.math.asin(sin(p1) * cos(dr) + cos(p1) * sin(dr) * cos(br))
        val l2 = l1 + atan2(sin(br) * sin(dr) * cos(p1), cos(dr) - sin(p1) * sin(p2))
        return Math.toDegrees(p2) to Math.toDegrees(l2)
    }
}
