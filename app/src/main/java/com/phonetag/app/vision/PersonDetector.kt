package com.phonetag.app.vision

import android.media.Image
import android.os.SystemClock
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.pose.PoseDetection
import com.google.mlkit.vision.pose.PoseLandmark
import com.google.mlkit.vision.pose.defaults.PoseDetectorOptions
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.max
import kotlin.math.min

/**
 * A person found in the camera image. Coordinates are normalised (0..1) in the upright image,
 * whose size is [imageWidth] x [imageHeight] pixels.
 */
data class PersonBox(
    val left: Float, val top: Float, val right: Float, val bottom: Float,
    val imageWidth: Int, val imageHeight: Int,
    /** Head and feet are both in frame, so the box height is the person's full height. */
    val fullBody: Boolean,
)

/** Result of the most recent detection pass ([person] null = looked, saw nobody). */
data class Detection(val person: PersonBox?, val atMs: Long)

/**
 * On-device person detection with ML Kit pose detection (stream mode). It finds the most
 * prominent person in view, which is normally whoever you're aiming at, and works fully
 * offline. Used to confirm shots and to draw a box around the player in the AR view.
 */
class PersonDetector {
    private val detector = PoseDetection.getClient(
        PoseDetectorOptions.Builder().setDetectorMode(PoseDetectorOptions.STREAM_MODE).build()
    )
    private val _latest = MutableStateFlow<Detection?>(null)
    val latest: StateFlow<Detection?> = _latest.asStateFlow()

    /** Runs detection; [done] is always called exactly once, after which [image] may be closed. */
    fun process(image: Image, rotationDegrees: Int, done: () -> Unit) {
        val upright = rotationDegrees % 180 != 0
        val w = if (upright) image.height else image.width
        val h = if (upright) image.width else image.height
        val input = try {
            InputImage.fromMediaImage(image, rotationDegrees)
        } catch (e: Exception) {
            done()
            return
        }
        detector.process(input)
            .addOnSuccessListener { pose ->
                val points = pose.allPoseLandmarks.filter { it.inFrameLikelihood > 0.5f }
                val box = if (points.size < 6) null else {
                    var l = Float.MAX_VALUE; var t = Float.MAX_VALUE; var r = -Float.MAX_VALUE; var b = -Float.MAX_VALUE
                    for (p in points) {
                        l = min(l, p.position.x); r = max(r, p.position.x)
                        t = min(t, p.position.y); b = max(b, p.position.y)
                    }
                    // Landmarks stop at the nose/eyes and ankles: pad for the head, arms and shoes.
                    val bh = b - t
                    val bw = r - l
                    t -= bh * 0.12f; b += bh * 0.04f
                    l -= bw * 0.15f; r += bw * 0.15f
                    fun likely(type: Int) = (pose.getPoseLandmark(type)?.inFrameLikelihood ?: 0f) > 0.8f
                    val full = likely(PoseLandmark.NOSE) && (likely(PoseLandmark.LEFT_ANKLE) || likely(PoseLandmark.RIGHT_ANKLE))
                    PersonBox(
                        (l / w).coerceIn(0f, 1f), (t / h).coerceIn(0f, 1f), (r / w).coerceIn(0f, 1f), (b / h).coerceIn(0f, 1f),
                        w, h, full,
                    )
                }
                _latest.value = Detection(box, SystemClock.elapsedRealtime())
            }
            .addOnCompleteListener { done() }
    }

    fun close() = detector.close()
}
