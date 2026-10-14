package com.phonetag.core

import kotlin.math.atan2
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Decides whether a "laser" shot hits anyone, fusing three sources of tracking:
 *
 * 1. **Shared AR arena** (camera tracking). When shooter and target are both synced to the same
 *    arena epoch, the shot is a real 3D ray from the shooter's camera along its viewing direction,
 *    tested against a vertical capsule around the target's phone. Errors are centimetres plus a
 *    little calibration yaw error that grows with distance.
 * 2. **GPS + compass.** Otherwise the shot is a ray along the compass heading from the GPS fix and
 *    hits if the bearing to the target is inside a cone sized for the body plus part of both
 *    players' GPS error.
 * 3. **On-device person detection.** If the shooter's camera saw a person under the crosshair
 *    (at a plausible distance) the GPS cone is widened, because GPS error shouldn't deny a visibly
 *    aimed shot. If the camera clearly saw nobody there at close range, it is narrowed.
 *
 * Every would-be hit is then checked for **obstacles** in the line of fire:
 * - AR depth straight down the shooter's crosshair: something solid clearly in front of the
 *   target (within the depth sensor's reliable range) blocks the shot.
 * - The shared wall map, built from vertical surfaces every synced phone detects: if the shot's
 *   ray crosses a wall before reaching the target, it is blocked.
 */
object HitResolver {
    const val BODY_RADIUS_M = 0.6
    /** Fraction of the combined GPS accuracy radius we forgive. */
    const val GPS_SLACK_FACTOR = 0.5
    const val MAX_GPS_SLACK_M = 6.0
    const val MIN_CONE_DEG = 2.0
    const val MAX_CONE_DEG = 30.0

    const val ARENA_BODY_RADIUS_M = 0.35
    const val ARENA_BASE_SLACK_M = 0.15
    /** ~1° of calibration yaw error. */
    const val ARENA_SLACK_PER_M = 0.02
    /** The phone is held around chest/eye height: the body extends this far below/above it. */
    const val BODY_BELOW_PHONE_M = 1.45
    const val BODY_ABOVE_PHONE_M = 0.25

    const val VISION_WIDEN = 1.6
    const val VISION_NARROW = 0.6
    /** Beyond this, "no person detected" is more likely a detector miss than an empty crosshair. */
    const val VISION_RELIABLE_RANGE_M = 20.0

    /** ARCore depth is trustworthy up to roughly this far; beyond it we don't block on depth. */
    const val DEPTH_RELIABLE_M = 8.0
    /** How much closer than the target the measured surface must be to count as an obstacle. */
    const val DEPTH_MARGIN_ARENA_M = 0.75
    const val DEPTH_MARGIN_GPS_MIN_M = 1.5
    /** A wall must be at least this far in front of the target (targets lean on walls). */
    const val WALL_MARGIN_M = 0.4

    data class Candidate(val id: Int, val pose: Pose)

    data class Hit(val targetId: Int, val distanceM: Double, val angleErrorDeg: Double, val method: HitMethod)

    /** [hit] is the player hit, if any; [blockedId] is who would have been hit but for an obstacle. */
    data class Resolution(val hit: Hit?, val blockedId: Int?)

    fun allowedConeDeg(distanceM: Double, shooterAccuracyM: Double, targetAccuracyM: Double): Double {
        val combined = sqrt(shooterAccuracyM * shooterAccuracyM + targetAccuracyM * targetAccuracyM)
        val radius = BODY_RADIUS_M + min(combined * GPS_SLACK_FACTOR, MAX_GPS_SLACK_M)
        val cone = Math.toDegrees(atan2(radius, distanceM.coerceAtLeast(0.1)))
        return cone.coerceIn(MIN_CONE_DEG, MAX_CONE_DEG)
    }

    /**
     * Returns the best hit, if any. Among candidates inside their tolerance we pick the one most
     * centred in the crosshair (relative to its tolerance), breaking ties by distance, i.e. the
     * player in front blocks the one behind.
     *
     * [arenaEpoch]: arena poses are only trusted if they belong to this epoch (null = any match).
     */
    fun resolve(
        shooter: Pose,
        candidates: List<Candidate>,
        maxRangeM: Double,
        vision: VisionHint? = null,
        arenaEpoch: Int? = null,
        walls: List<Wall> = emptyList(),
    ): Hit? = resolveDetailed(shooter, candidates, maxRangeM, vision, arenaEpoch, walls).hit

    fun resolveDetailed(
        shooter: Pose,
        candidates: List<Candidate>,
        maxRangeM: Double,
        vision: VisionHint? = null,
        arenaEpoch: Int? = null,
        walls: List<Wall> = emptyList(),
    ): Resolution {
        var best: Hit? = null
        var bestScore = Double.MAX_VALUE
        var blocked: Int? = null
        var blockedScore = Double.MAX_VALUE
        for (c in candidates) {
            // Arena tracking, when it applies for this pair, is authoritative (a miss is a miss).
            val eval = evaluateArena(shooter, c, maxRangeM, arenaEpoch) ?: evaluateGps(shooter, c, maxRangeM, vision)
            val hit = eval.hit ?: continue
            if (isObstructed(shooter, c, eval, vision, arenaEpoch, walls)) {
                if (eval.score < blockedScore) {
                    blockedScore = eval.score
                    blocked = c.id
                }
                continue
            }
            if (eval.score < bestScore) {
                bestScore = eval.score
                best = hit
            }
        }
        return Resolution(best, if (best == null) blocked else null)
    }

    /** A shot near another player by at least this angle is never a "random" (bystander) shot. */
    const val BYSTANDER_MIN_CLEARANCE_DEG = 15.0

    /**
     * True if this (missed) shot hit a bystander: the shooter's camera saw a person in the
     * crosshair, yet no player is anywhere near the line of fire. "Near" is deliberately generous
     * (double the hit tolerance, at least 15°) so a narrowly missed player never counts.
     * [players] should include every other player with a known position, whatever their team or state.
     */
    fun isBystanderShot(shooter: Pose, players: List<Candidate>, maxRangeM: Double, vision: VisionHint?): Boolean {
        if (vision?.personInCrosshair != true) return false
        // An estimated distance beyond weapon range means the person is out of reach anyway.
        if (vision.estDistanceM != null && vision.estDistanceM > maxRangeM * 1.5) return false
        val a = shooter.arena
        for (c in players) {
            val b = c.pose.arena
            val (distance, angleDeg, toleranceDeg) = if (a != null && b != null && a.epoch == b.epoch && a.epoch != 0) {
                val v = b.position - a.position
                val d = v.length()
                val cos = (v.normalized() dot a.forward.normalized()).coerceIn(-1.0, 1.0)
                val radius = ARENA_BODY_RADIUS_M + ARENA_BASE_SLACK_M + ARENA_SLACK_PER_M * d
                Triple(d, Math.toDegrees(kotlin.math.acos(cos)), Math.toDegrees(atan2(radius, d.coerceAtLeast(0.1))))
            } else {
                val d = Geo.distanceM(shooter.lat, shooter.lon, c.pose.lat, c.pose.lon)
                val bearing = Geo.bearingDeg(shooter.lat, shooter.lon, c.pose.lat, c.pose.lon)
                Triple(d, Geo.angleDiffDeg(shooter.azimuthDeg, bearing), allowedConeDeg(d, shooter.accuracyM, c.pose.accuracyM))
            }
            if (distance > maxRangeM * 1.5) continue
            if (angleDeg <= maxOf(BYSTANDER_MIN_CLEARANCE_DEG, toleranceDeg * 2)) return false
        }
        return true
    }

    /** [rangeM]: distance along the line of fire to where the shot meets the target. */
    private class Eval(val score: Double, val hit: Hit?, val rangeM: Double = 0.0)

    private val MISS = Eval(Double.MAX_VALUE, null)

    private fun isObstructed(shooter: Pose, c: Candidate, eval: Eval, vision: VisionHint?, epoch: Int?, walls: List<Wall>): Boolean {
        val hit = eval.hit ?: return false
        val precise = hit.method == HitMethod.ARENA
        val range = eval.rangeM

        // 1. Depth down the crosshair.
        val clear = vision?.clearDistanceM
        if (clear != null && clear > 0 && clear < DEPTH_RELIABLE_M) {
            if (precise) {
                if (clear < range - DEPTH_MARGIN_ARENA_M) return true
            } else {
                // GPS distance is rough, so demand a clear gap; and if the camera sees a person in
                // the crosshair, the near surface is most likely that person, not a wall.
                val acc = sqrt(shooter.accuracyM * shooter.accuracyM + c.pose.accuracyM * c.pose.accuracyM)
                val margin = maxOf(DEPTH_MARGIN_GPS_MIN_M, acc * 0.5)
                if (clear < range - margin && vision.personInCrosshair != true) return true
            }
        }

        // 2. Shared wall map (needs the shooter's position and aim in the arena).
        val a = shooter.arena ?: return false
        if (walls.isEmpty() || a.epoch == 0 || (epoch != null && a.epoch != epoch)) return false
        val dir = a.forward.normalized()
        val maxT = if (precise) {
            range - WALL_MARGIN_M
        } else {
            // Shooter is synced but the target isn't: stop well short of the GPS distance.
            val acc = sqrt(shooter.accuracyM * shooter.accuracyM + c.pose.accuracyM * c.pose.accuracyM)
            range - maxOf(DEPTH_MARGIN_GPS_MIN_M, acc * 0.5)
        }
        return Occlusion.firstHit(a.position, dir, maxT, walls) != null
    }

    /** Null if arena tracking can't be used for this pair. */
    private fun evaluateArena(shooter: Pose, c: Candidate, maxRangeM: Double, epoch: Int?): Eval? {
        val a = shooter.arena ?: return null
        val b = c.pose.arena ?: return null
        if (a.epoch != b.epoch || a.epoch == 0 || (epoch != null && a.epoch != epoch)) return null
        val dir = a.forward.normalized()
        if (dir.length() < 0.5) return null
        val distance = (b.position - a.position).length()
        if (distance > maxRangeM) return MISS
        val bottom = b.position - Vec3(0.0, BODY_BELOW_PHONE_M, 0.0)
        val top = b.position + Vec3(0.0, BODY_ABOVE_PHONE_M, 0.0)
        val (along, miss) = rayToSegment(a.position, dir, maxRangeM, bottom, top)
        if (along <= 0.05) return MISS
        val radius = ARENA_BODY_RADIUS_M + ARENA_BASE_SLACK_M + ARENA_SLACK_PER_M * distance
        if (miss > radius) return MISS
        val err = Math.toDegrees(atan2(miss, along))
        return Eval(miss / radius + distance / maxRangeM * 0.25, Hit(c.id, distance, err, HitMethod.ARENA), along)
    }

    private fun evaluateGps(shooter: Pose, c: Candidate, maxRangeM: Double, vision: VisionHint?): Eval {
        val d = Geo.distanceM(shooter.lat, shooter.lon, c.pose.lat, c.pose.lon)
        if (d > maxRangeM) return MISS
        val bearing = Geo.bearingDeg(shooter.lat, shooter.lon, c.pose.lat, c.pose.lon)
        val err = Geo.angleDiffDeg(shooter.azimuthDeg, bearing)
        var cone = allowedConeDeg(d, shooter.accuracyM, c.pose.accuracyM)
        var method = HitMethod.GPS
        if (vision != null) {
            val plausible = vision.estDistanceM == null || vision.estDistanceM in (d * 0.5)..(d * 2.0 + 3.0)
            if (vision.personInCrosshair == true && plausible) {
                cone = (cone * VISION_WIDEN).coerceAtMost(MAX_CONE_DEG)
                method = HitMethod.GPS_VISION
            } else if (vision.personInCrosshair == false && d <= VISION_RELIABLE_RANGE_M) {
                cone = (cone * VISION_NARROW).coerceAtLeast(MIN_CONE_DEG)
            }
        }
        if (err > cone) return MISS
        // Normalised error (0 = dead centre, 1 = edge) plus a small distance term.
        return Eval(err / cone + d / maxRangeM * 0.25, Hit(c.id, d, err, method), d)
    }

    /**
     * Closest approach between the ray segment `o + d·s, s ∈ [0, len]` and segment `[p, q]`.
     * Returns (s at closest approach, distance).
     */
    internal fun rayToSegment(o: Vec3, d: Vec3, len: Double, p: Vec3, q: Vec3): Pair<Double, Double> {
        val u = q - p
        val w = o - p
        val a = d dot d
        val b = d dot u
        val c = u dot u
        val dd = d dot w
        val e = u dot w
        val denom = a * c - b * b
        var s = if (denom > 1e-9) ((b * e - c * dd) / denom).coerceIn(0.0, len) else 0.0
        var t = if (c > 1e-9) ((b * s + e) / c) else 0.0
        if (t < 0.0) {
            t = 0.0
            s = (-dd / a).coerceIn(0.0, len)
        } else if (t > 1.0) {
            t = 1.0
            s = ((b - dd) / a).coerceIn(0.0, len)
        }
        val closestRay = o + d * s
        val closestSeg = p + u * t
        return s to (closestRay - closestSeg).length()
    }
}
