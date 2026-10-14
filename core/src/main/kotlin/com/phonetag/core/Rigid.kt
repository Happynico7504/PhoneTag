package com.phonetag.core

import kotlinx.serialization.Serializable
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * Minimal 3D rigid-transform math for the shared AR arena.
 *
 * Conventions match ARCore: right-handed, metres, +Y is up (gravity-aligned), cameras look
 * along -Z. Every device's ARCore world is gravity-aligned, so two worlds differ only by a
 * yaw rotation about +Y plus a translation. Calibration exploits that (see [ArenaCalibration]).
 */
@Serializable
data class Vec3(val x: Double, val y: Double, val z: Double) {
    operator fun plus(o: Vec3) = Vec3(x + o.x, y + o.y, z + o.z)
    operator fun minus(o: Vec3) = Vec3(x - o.x, y - o.y, z - o.z)
    operator fun times(k: Double) = Vec3(x * k, y * k, z * k)
    infix fun dot(o: Vec3) = x * o.x + y * o.y + z * o.z
    infix fun cross(o: Vec3) = Vec3(y * o.z - z * o.y, z * o.x - x * o.z, x * o.y - y * o.x)
    fun length() = sqrt(this dot this)
    fun normalized(): Vec3 = length().let { if (it < 1e-12) this else this * (1 / it) }
    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite()

    companion object {
        val ZERO = Vec3(0.0, 0.0, 0.0)
        val UP = Vec3(0.0, 1.0, 0.0)
        val FORWARD = Vec3(0.0, 0.0, -1.0)
    }
}

/** Unit quaternion (x, y, z, w). */
@Serializable
data class Quat(val x: Double, val y: Double, val z: Double, val w: Double) {
    operator fun times(o: Quat) = Quat(
        w * o.x + x * o.w + y * o.z - z * o.y,
        w * o.y - x * o.z + y * o.w + z * o.x,
        w * o.z + x * o.y - y * o.x + z * o.w,
        w * o.w - x * o.x - y * o.y - z * o.z,
    )

    fun conjugate() = Quat(-x, -y, -z, w)

    fun normalized(): Quat {
        val n = sqrt(x * x + y * y + z * z + w * w)
        return if (n < 1e-12) IDENTITY else Quat(x / n, y / n, z / n, w / n)
    }

    fun rotate(v: Vec3): Vec3 {
        // v' = v + 2w(q × v) + 2 q × (q × v)
        val q = Vec3(x, y, z)
        val t = (q cross v) * 2.0
        return v + t * w + (q cross t)
    }

    /**
     * Heading about +Y, in radians, of this rotation's -Z (forward) axis; 0 = looking along -Z,
     * positive = turned towards -X (counter-clockwise seen from above, like ARCore's yaw).
     * If forward is near vertical, falls back to the rotated +Y axis' horizontal direction.
     */
    fun yaw(): Double {
        var f = rotate(Vec3.FORWARD)
        if (f.x * f.x + f.z * f.z < 1e-6) f = rotate(Vec3(0.0, if (f.y > 0) -1.0 else 1.0, 0.0))
        return atan2(-f.x, -f.z)
    }

    fun isFinite() = x.isFinite() && y.isFinite() && z.isFinite() && w.isFinite()

    companion object {
        val IDENTITY = Quat(0.0, 0.0, 0.0, 1.0)

        fun axisAngle(axis: Vec3, radians: Double): Quat {
            val a = axis.normalized()
            val s = sin(radians / 2)
            return Quat(a.x * s, a.y * s, a.z * s, cos(radians / 2))
        }

        fun yaw(radians: Double) = axisAngle(Vec3.UP, radians)
    }
}

/** A rigid transform: rotate by [rotation] then translate by [position]. Maps local → parent. */
@Serializable
data class Rigid(val position: Vec3, val rotation: Quat) {
    operator fun times(o: Rigid) = Rigid(position + rotation.rotate(o.position), (rotation * o.rotation).normalized())
    fun transform(p: Vec3) = position + rotation.rotate(p)
    fun inverse(): Rigid {
        val inv = rotation.conjugate()
        return Rigid(inv.rotate(position) * -1.0, inv)
    }

    /** Keeps only the heading part of the rotation (drops pitch/roll), keeping the translation. */
    fun yawOnly() = Rigid(position, Quat.yaw(rotation.yaw()))

    fun isFinite() = position.isFinite() && rotation.isFinite()

    companion object {
        val IDENTITY = Rigid(Vec3.ZERO, Quat.IDENTITY)
    }
}

/**
 * Turns "the same physical marker seen from two devices" into a shared coordinate frame.
 *
 * A synced player (A) shows a marker on their screen and broadcasts that screen's pose in the
 * arena frame. A scanning player (B) sees the marker through ARCore and gets its pose in B's own
 * world. Because they are the same object: `markerInWorld = arenaToWorld * screenInArena`, so
 * `arenaToWorld = markerInWorld * screenInArena⁻¹`. Both worlds are gravity-aligned, so the
 * result is projected to yaw + translation, which also removes most marker-tilt noise.
 */
object ArenaCalibration {
    /**
     * Rotation from a phone's display-oriented camera frame (X right, Y up, +Z out of the screen)
     * to the ARCore augmented-image frame of an image shown full-face on that screen
     * (X right, Y normal out of the image, Z towards the image bottom): +90° about X.
     */
    val CAMERA_TO_SCREEN_IMAGE: Quat = Quat.axisAngle(Vec3(1.0, 0.0, 0.0), Math.PI / 2)

    /**
     * Approximate offset from the rear camera to the centre of the screen, in the camera frame.
     * Varies by a few cm between phone models, which is small next to every other error source.
     */
    val SCREEN_CENTER_FROM_CAMERA = Vec3(0.0, -0.055, 0.0)

    /** Pose of the marker displayed on this phone's screen, given the camera pose in the same frame. */
    fun screenPose(camera: Rigid): Rigid = camera * Rigid(SCREEN_CENTER_FROM_CAMERA, CAMERA_TO_SCREEN_IMAGE)

    fun arenaToWorld(markerInWorld: Rigid, screenInArena: Rigid): Rigid =
        (markerInWorld * screenInArena.inverse()).yawOnly()

    /**
     * Robust average of several calibration samples. Returns null if they disagree too much
     * (someone moved during the scan), so the caller keeps collecting.
     */
    fun average(samples: List<Rigid>, maxSpreadM: Double = 0.15, maxSpreadDeg: Double = 4.0): Rigid? {
        if (samples.isEmpty()) return null
        var sx = 0.0; var sy = 0.0; var sz = 0.0; var ss = 0.0; var sc = 0.0
        for (s in samples) {
            sx += s.position.x; sy += s.position.y; sz += s.position.z
            val yaw = s.rotation.yaw()
            ss += sin(yaw); sc += cos(yaw)
        }
        val n = samples.size.toDouble()
        val mean = Vec3(sx / n, sy / n, sz / n)
        val meanYaw = atan2(ss, sc)
        for (s in samples) {
            if ((s.position - mean).length() > maxSpreadM) return null
            if (kotlin.math.abs(Geo.signedAngleDeg(Math.toDegrees(meanYaw), Math.toDegrees(s.rotation.yaw()))) > maxSpreadDeg) return null
        }
        return Rigid(mean, Quat.yaw(meanYaw))
    }
}
