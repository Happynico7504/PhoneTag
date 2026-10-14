package com.phonetag.app.voice

import kotlin.math.log10
import kotlin.math.max
import kotlin.math.sqrt

/**
 * Decides whether a frame contains speech, so the radio only transmits while someone talks.
 * Energy-based with an adaptive noise floor (wind, footsteps and crowd noise raise it), plus a
 * hangover so word endings and short pauses aren't clipped.
 */
class VoiceActivityDetector(
    /** Speech must be this many dB above the noise floor... */
    private val marginDb: Double = 9.0,
    /** ...and above this absolute level. */
    private val minSpeechDbfs: Double = -48.0,
    private val hangoverFrames: Int = 8,
) {
    private var noiseFloorDb = -60.0
    private var hangover = 0

    var levelDb = -96.0
        private set

    fun isSpeech(frame: ShortArray): Boolean {
        levelDb = dbfs(frame)
        // The floor tracks quiet frames quickly and loud frames very slowly.
        noiseFloorDb += (levelDb - noiseFloorDb) * if (levelDb < noiseFloorDb) 0.2 else 0.005
        val loud = levelDb > max(noiseFloorDb + marginDb, minSpeechDbfs)
        if (loud) hangover = hangoverFrames else if (hangover > 0) hangover--
        return loud || hangover > 0
    }

    companion object {
        fun dbfs(frame: ShortArray): Double {
            if (frame.isEmpty()) return -96.0
            var sum = 0.0
            for (s in frame) sum += s.toDouble() * s
            val rms = sqrt(sum / frame.size) / 32768.0
            return if (rms <= 1e-5) -96.0 else 20 * log10(rms)
        }
    }
}
