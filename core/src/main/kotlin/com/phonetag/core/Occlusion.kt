package com.phonetag.core

import kotlinx.serialization.Serializable
import kotlin.math.abs

/**
 * A wall (or any large vertical surface) detected by a phone's AR tracking, as a rectangle in
 * the shared arena frame: [center] plus two orthogonal in-plane unit axes and half extents.
 */
@Serializable
data class Wall(val center: Vec3, val axisU: Vec3, val axisV: Vec3, val halfU: Double, val halfV: Double) {
    val normal get() = (axisU cross axisV).normalized()

    fun isSane() = center.isFinite() && axisU.isFinite() && axisV.isFinite() &&
        halfU in 0.05..MAX_HALF_EXTENT_M && halfV in 0.05..MAX_HALF_EXTENT_M &&
        abs(axisU.length() - 1) < 0.05 && abs(axisV.length() - 1) < 0.05

    companion object {
        const val MAX_HALF_EXTENT_M = 30.0
    }
}

/** Line-of-sight checks against the shared wall map. */
object Occlusion {
    /**
     * Distance along the ray `o + d·t` (d a unit vector) at which it crosses [wall], or null if
     * it doesn't within `(0, maxT]`.
     */
    fun rayHit(o: Vec3, d: Vec3, maxT: Double, wall: Wall): Double? {
        val n = wall.normal
        val denom = d dot n
        if (abs(denom) < 1e-6) return null // ray parallel to the wall
        val t = ((wall.center - o) dot n) / denom
        if (t <= 0.0 || t > maxT) return null
        val rel = (o + d * t) - wall.center
        if (abs(rel dot wall.axisU) > wall.halfU || abs(rel dot wall.axisV) > wall.halfV) return null
        return t
    }

    /** Nearest wall crossing along the ray within `(0, maxT]`, if any. */
    fun firstHit(o: Vec3, d: Vec3, maxT: Double, walls: List<Wall>): Double? {
        if (maxT <= 0) return null
        var best: Double? = null
        for (w in walls) {
            val t = rayHit(o, d, maxT, w) ?: continue
            if (best == null || t < best) best = t
        }
        return best
    }
}
