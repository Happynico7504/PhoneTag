package com.phonetag.app.vision

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class VisionGeometryTest {
    // 480x640 upright camera image shown center-cropped in a 1080x2340 view.
    private fun box(l: Float, t: Float, r: Float, b: Float, full: Boolean = true) = PersonBox(l, t, r, b, 480, 640, full)

    @Test
    fun centerCropMappingKeepsTheCentreAndCropsTheSides() {
        val r = VisionGeometry.toView(box(0.5f, 0.5f, 0.5f, 0.5f), 1080f, 2340f)
        assertEquals(540f, r.left, 0.01f)
        assertEquals(1170f, r.top, 0.01f)
        // Height drives the scale (2340/640); the image's left edge is cropped off-screen.
        val edge = VisionGeometry.toView(box(0f, 0f, 1f, 1f), 1080f, 2340f)
        assertEquals(0f, edge.top, 0.01f)
        assertEquals(2340f, edge.bottom, 0.01f)
        assertTrue(edge.left < 0f && edge.right > 1080f)
    }

    @Test
    fun hintReportsCrosshairAndDistance() {
        val now = 10_000L
        val centered = Detection(box(0.45f, 0.40f, 0.55f, 0.60f), now)
        val hint = VisionGeometry.hint(centered, now, 1080f, 2340f, focalPx = 1800f)!!
        assertTrue(hint.personInCrosshair)
        // Box is 0.2 * 2340 = 468 px tall → 1.7 m * 1800 / 468 ≈ 6.5 m.
        assertEquals(6.54, hint.estDistanceM!!, 0.05)

        val aside = Detection(box(0.05f, 0.4f, 0.2f, 0.6f), now)
        assertFalse(VisionGeometry.hint(aside, now, 1080f, 2340f, 1800f)!!.personInCrosshair)
        assertFalse(VisionGeometry.hint(Detection(null, now), now, 1080f, 2340f, 1800f)!!.personInCrosshair)
        assertNull(VisionGeometry.hint(centered, now + 2_000, 1080f, 2340f, 1800f)) // stale
        assertNull(VisionGeometry.hint(Detection(box(0.45f, 0.4f, 0.55f, 0.6f, full = false), now), now, 1080f, 2340f, 1800f)!!.estDistanceM)
    }
}
