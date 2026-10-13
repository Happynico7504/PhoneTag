package com.phonetag.app.vision

import com.phonetag.core.VisionHint
import kotlin.math.max

/** A rectangle in view (screen) pixels. */
data class ViewRect(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    val width get() = right - left
    val height get() = bottom - top
}

object VisionGeometry {
    /** Typical standing height used to turn the box height into a distance. */
    const val PERSON_HEIGHT_M = 1.7
    /** Detections older than this don't describe what the crosshair is on right now. */
    const val MAX_AGE_MS = 500L

    /** Maps a box from the camera image to a view that shows the image center-cropped (fill). */
    fun toView(box: PersonBox, viewW: Float, viewH: Float): ViewRect {
        val scale = max(viewW / box.imageWidth, viewH / box.imageHeight)
        val offX = (box.imageWidth * scale - viewW) / 2
        val offY = (box.imageHeight * scale - viewH) / 2
        return ViewRect(
            box.left * box.imageWidth * scale - offX,
            box.top * box.imageHeight * scale - offY,
            box.right * box.imageWidth * scale - offX,
            box.bottom * box.imageHeight * scale - offY,
        )
    }

    /**
     * What the camera says about the crosshair (view centre) right now, or null if detection
     * isn't running / is stale. [focalPx] is the view's focal length in pixels.
     */
    fun hint(detection: Detection?, nowMs: Long, viewW: Float, viewH: Float, focalPx: Float): VisionHint? {
        if (detection == null || nowMs - detection.atMs > MAX_AGE_MS || viewW <= 0 || viewH <= 0) return null
        val box = detection.person ?: return VisionHint(personInCrosshair = false)
        val r = toView(box, viewW, viewH)
        val pad = r.width * 0.08f
        val cx = viewW / 2
        val cy = viewH / 2
        val inCrosshair = cx in (r.left - pad)..(r.right + pad) && cy in r.top..r.bottom
        val est = if (box.fullBody && r.height > 8f && focalPx > 0) PERSON_HEIGHT_M * focalPx / r.height else null
        return VisionHint(inCrosshair, est)
    }
}
