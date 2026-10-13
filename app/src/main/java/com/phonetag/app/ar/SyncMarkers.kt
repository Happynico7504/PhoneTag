package com.phonetag.app.ar

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import kotlin.random.Random

/**
 * Procedural image targets for ARCore Augmented Images, one per marker slot.
 *
 * ARCore needs feature-rich, non-repetitive images: we draw a deterministic scatter of
 * high-contrast shapes seeded by the slot, inside a thick frame. Every phone generates the
 * same pixels, so nothing needs to be shipped or printed.
 */
object SyncMarkers {
    const val SLOTS = 32
    /** Physical width the marker is displayed at on screen, so ARCore can measure distance. */
    const val WIDTH_M = 0.05f
    private const val SIZE_PX = 480

    fun slotFor(playerId: Int) = (playerId - 1).mod(SLOTS)
    fun name(slot: Int) = "phonetag-$slot"
    fun slotOf(name: String) = name.removePrefix("phonetag-").toIntOrNull()

    fun bitmap(slot: Int): Bitmap {
        val bmp = Bitmap.createBitmap(SIZE_PX, SIZE_PX, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        c.drawColor(Color.WHITE)
        val rnd = Random(0x5EED + slot * 7919)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        val inset = SIZE_PX * 0.1f
        val span = SIZE_PX - 2 * inset
        repeat(70) {
            paint.color = if (rnd.nextInt(5) == 0) Color.rgb(110, 110, 110) else Color.BLACK
            val cx = inset + rnd.nextFloat() * span
            val cy = inset + rnd.nextFloat() * span
            val r = SIZE_PX * (0.012f + rnd.nextFloat() * 0.05f)
            when (rnd.nextInt(3)) {
                0 -> c.drawCircle(cx, cy, r, paint)
                1 -> c.drawRect(cx - r, cy - r * 0.6f, cx + r, cy + r * 0.6f, paint)
                else -> c.drawPath(Path().apply {
                    moveTo(cx, cy - r); lineTo(cx + r, cy + r); lineTo(cx - r, cy + r * 0.4f); close()
                }, paint)
            }
        }
        // Thick asymmetric frame (notched corner) so orientation is unambiguous.
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = SIZE_PX * 0.05f
        c.drawRect(SIZE_PX * 0.025f, SIZE_PX * 0.025f, SIZE_PX * 0.975f, SIZE_PX * 0.975f, paint)
        paint.style = Paint.Style.FILL
        c.drawRect(0f, 0f, SIZE_PX * 0.16f, SIZE_PX * 0.16f, paint)
        return bmp
    }
}
