package com.phonetag.app.voice

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.random.Random

class VoiceCodecTest {
    private val rate = 16_000

    /** A speech-like test signal: a few harmonics with a slow amplitude envelope. */
    private fun voice(n: Int, amp: Double = 8000.0, offset: Int = 0) = ShortArray(n) { i ->
        val t = (i + offset).toDouble() / rate
        val env = 0.6 + 0.4 * sin(2 * PI * 3 * t)
        (amp * env * (sin(2 * PI * 180 * t) + 0.5 * sin(2 * PI * 360 * t) + 0.25 * sin(2 * PI * 1250 * t)) / 1.75).toInt().toShort()
    }

    private fun snrDb(ref: ShortArray, out: ShortArray): Double {
        var sig = 0.0
        var err = 0.0
        for (i in ref.indices) {
            sig += ref[i].toDouble() * ref[i]
            val e = ref[i] - out[i].toDouble()
            err += e * e
        }
        return 10 * log10(sig / err)
    }

    @Test
    fun adpcmRoundTripIsCompactAndClear() {
        val enc = AdpcmEncoder()
        var worst = Double.MAX_VALUE
        // Stream several consecutive 40 ms frames, as the app does.
        for (f in 0 until 10) {
            val pcm = voice(640, offset = f * 640)
            val frame = enc.encode(pcm)
            assertEquals(AdpcmEncoder.HEADER_BYTES + 320, frame.size) // 4:1
            val out = AdpcmDecoder.decode(frame)!!
            assertEquals(640, out.size)
            if (f > 0) worst = minOf(worst, snrDb(pcm, out)) // first frame includes the codec warm-up
        }
        assertTrue("SNR $worst dB too low", worst > 20.0)
    }

    @Test
    fun framesDecodeIndependentlySoLossOnlyCostsThatFrame() {
        val enc = AdpcmEncoder()
        val frames = (0 until 6).map { enc.encode(voice(640, offset = it * 640)) }
        // Skip frames 2 and 3 (lost): frame 4 must still decode as if nothing happened.
        val direct = AdpcmDecoder.decode(frames[4])!!
        assertTrue(snrDb(voice(640, offset = 4 * 640), direct) > 20.0)
    }

    @Test
    fun malformedFramesAreRejected() {
        assertNull(AdpcmDecoder.decode(ByteArray(2)))
        assertNull(AdpcmDecoder.decode(byteArrayOf(0, 0, 100, 0, 0x11)))
    }

    @Test
    fun vadDetectsSpeechOverNoiseAndHangsOverBriefPauses() {
        val vad = VoiceActivityDetector()
        val rnd = Random(3)
        fun noise(amp: Int) = ShortArray(320) { rnd.nextInt(-amp, amp + 1).toShort() }
        // Settle on background noise: not speech.
        repeat(100) { assertFalse(vad.isSpeech(noise(150))) }
        assertTrue(vad.isSpeech(voice(320, amp = 6000.0)))
        // Short pause: still "speech" thanks to the hangover...
        repeat(5) { assertTrue(vad.isSpeech(noise(150))) }
        // ...then it stops.
        repeat(10) { vad.isSpeech(noise(150)) }
        assertFalse(vad.isSpeech(noise(150)))
        // Louder steady background (wind): the floor adapts and stops triggering.
        repeat(400) { vad.isSpeech(noise(2500)) }
        assertFalse(vad.isSpeech(noise(2500)))
    }
}
