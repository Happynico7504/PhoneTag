package com.phonetag.app.sfx

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs

class SynthTest {
    @Test
    fun everyEffectRendersAudibleUnclippedShortSound() {
        for (sfx in Sfx.entries) {
            val pcm = Synth.render(sfx)
            val seconds = pcm.size.toDouble() / Synth.SAMPLE_RATE
            assertTrue("$sfx length $seconds", seconds in 0.02..1.5)
            val peak = pcm.maxOf { abs(it.toInt()) }
            assertTrue("$sfx is silent", peak > Short.MAX_VALUE * 0.1)
            assertTrue("$sfx clips", peak <= Short.MAX_VALUE * 0.91)
            // Starts and ends at (near) zero so playback never clicks.
            assertTrue("$sfx clicks at start", abs(pcm.first().toInt()) < 200)
            assertTrue("$sfx clicks at end", abs(pcm.last().toInt()) < 200)
        }
    }

    @Test
    fun renderingIsDeterministic() {
        assertArrayEquals(Synth.render(Sfx.LASER), Synth.render(Sfx.LASER))
    }

    @Test
    fun wavHeaderIsValid() {
        val pcm = Synth.render(Sfx.HIT_CONFIRM)
        val wav = Synth.wav(pcm)
        val b = ByteBuffer.wrap(wav).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals("RIFF", String(wav, 0, 4))
        assertEquals("WAVE", String(wav, 8, 4))
        assertEquals(36 + pcm.size * 2, b.getInt(4))
        assertEquals(1, b.getShort(22).toInt()) // mono
        assertEquals(Synth.SAMPLE_RATE, b.getInt(24))
        assertEquals(16, b.getShort(34).toInt())
        assertEquals(pcm.size * 2, b.getInt(40))
        assertEquals(44 + pcm.size * 2, wav.size)
    }
}
