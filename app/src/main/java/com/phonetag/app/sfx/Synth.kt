package com.phonetag.app.sfx

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.random.Random

/** Every sound effect in the game. All are synthesized at runtime, so no audio assets ship with the app. */
enum class Sfx { LASER, HIT_CONFIRM, TAG, DAMAGE, TAGGED_OUT, RESPAWN, LOCK_ON, MATCH_START, MATCH_END, HEARTBEAT, BLOCKED, RANDOM }

/**
 * Tiny offline synthesizer: renders each [Sfx] to 16-bit mono PCM. Pure Kotlin so it can be
 * unit-tested on the JVM; [SoundFx] writes the results to WAV files and plays them via SoundPool.
 */
object Synth {
    const val SAMPLE_RATE = 44_100

    fun render(sfx: Sfx): ShortArray = toPcm(
        when (sfx) {
            // Classic "pew": square-ish wave sweeping down fast, with a noisy attack.
            Sfx.LASER -> mix(
                sweep(1800.0, 260.0, 0.20, Wave.SQUARE, decay = 14.0, gain = 0.5),
                noise(0.04, decay = 60.0, gain = 0.25),
            )
            Sfx.HIT_CONFIRM -> concat(
                tone(880.0, 0.06, Wave.SINE, decay = 20.0, gain = 0.7),
                tone(1320.0, 0.09, Wave.SINE, decay = 18.0, gain = 0.7),
            )
            // Rising arpeggio for tagging someone out.
            Sfx.TAG -> concat(*doubleArrayOf(523.25, 659.25, 783.99, 1046.5).map {
                tone(it, 0.075, Wave.TRIANGLE, decay = 8.0, gain = 0.6)
            }.toTypedArray())
            Sfx.DAMAGE -> mix(
                sweep(160.0, 60.0, 0.25, Wave.SINE, decay = 10.0, gain = 0.9),
                noise(0.12, decay = 30.0, gain = 0.35),
            )
            Sfx.TAGGED_OUT -> tremolo(sweep(620.0, 70.0, 0.8, Wave.SAW, decay = 3.0, gain = 0.5), rateHz = 14.0)
            Sfx.RESPAWN -> sweep(220.0, 1300.0, 0.42, Wave.TRIANGLE, decay = 2.5, gain = 0.6)
            Sfx.LOCK_ON -> tone(2100.0, 0.03, Wave.SQUARE, decay = 60.0, gain = 0.25)
            Sfx.MATCH_START -> concat(
                tone(660.0, 0.12, Wave.SQUARE, decay = 6.0, gain = 0.35), silence(0.12),
                tone(660.0, 0.12, Wave.SQUARE, decay = 6.0, gain = 0.35), silence(0.12),
                tone(1320.0, 0.45, Wave.SQUARE, decay = 3.0, gain = 0.4),
            )
            Sfx.MATCH_END -> concat(
                tone(783.99, 0.15, Wave.TRIANGLE, decay = 4.0, gain = 0.6),
                tone(659.25, 0.15, Wave.TRIANGLE, decay = 4.0, gain = 0.6),
                tone(523.25, 0.6, Wave.TRIANGLE, decay = 2.0, gain = 0.6),
            )
            // Comic slide-whistle "whoops" for shooting a bystander.
            Sfx.RANDOM -> concat(
                tremolo(sweep(1500.0, 380.0, 0.45, Wave.SINE, decay = 1.5, gain = 0.6), rateHz = 9.0),
                tone(330.0, 0.12, Wave.TRIANGLE, decay = 10.0, gain = 0.5),
            )
            // Dull "thunk" of a shot hitting cover.
            Sfx.BLOCKED -> mix(
                sweep(320.0, 90.0, 0.16, Wave.TRIANGLE, decay = 16.0, gain = 0.8),
                noise(0.05, decay = 70.0, gain = 0.2),
            )
            Sfx.HEARTBEAT -> concat(
                tone(55.0, 0.11, Wave.SINE, decay = 18.0, gain = 1.0), silence(0.09),
                tone(50.0, 0.14, Wave.SINE, decay = 16.0, gain = 0.8),
            )
        }
    )

    /** Wraps PCM in a canonical 44-byte RIFF/WAVE header. */
    fun wav(pcm: ShortArray): ByteArray {
        val dataBytes = pcm.size * 2
        val header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN).apply {
            put("RIFF".toByteArray()); putInt(36 + dataBytes); put("WAVE".toByteArray())
            put("fmt ".toByteArray()); putInt(16); putShort(1); putShort(1)
            putInt(SAMPLE_RATE); putInt(SAMPLE_RATE * 2); putShort(2); putShort(16)
            put("data".toByteArray()); putInt(dataBytes)
        }
        val data = ByteBuffer.allocate(dataBytes).order(ByteOrder.LITTLE_ENDIAN)
        pcm.forEach { data.putShort(it) }
        return ByteArrayOutputStream(44 + dataBytes).apply { write(header.array()); write(data.array()) }.toByteArray()
    }

    // ------------------------------------------------------------------ building blocks

    private enum class Wave { SINE, SQUARE, SAW, TRIANGLE }

    private fun samples(seconds: Double) = (seconds * SAMPLE_RATE).toInt()

    private fun osc(wave: Wave, phase: Double): Double {
        val p = phase - kotlin.math.floor(phase) // 0..1
        return when (wave) {
            Wave.SINE -> sin(2 * PI * p)
            Wave.SQUARE -> if (p < 0.5) 0.8 else -0.8 // slightly tamed, squares are loud
            Wave.SAW -> 2 * p - 1
            Wave.TRIANGLE -> 1 - 4 * kotlin.math.abs(p - 0.5)
        }
    }

    /** Short linear attack/release so nothing clicks. */
    private fun edge(i: Int, n: Int): Double {
        val ramp = samples(0.004).coerceAtLeast(1)
        return min(1.0, min(i.toDouble() / ramp, (n - 1 - i).toDouble() / ramp)).coerceAtLeast(0.0)
    }

    private fun tone(freq: Double, seconds: Double, wave: Wave, decay: Double, gain: Double) =
        sweep(freq, freq, seconds, wave, decay, gain)

    /** Exponential frequency glide from [from] to [to] Hz with exponential amplitude decay. */
    private fun sweep(from: Double, to: Double, seconds: Double, wave: Wave, decay: Double, gain: Double): DoubleArray {
        val n = samples(seconds)
        var phase = 0.0
        return DoubleArray(n) { i ->
            val t = i.toDouble() / SAMPLE_RATE
            val f = from * Math.pow(to / from, i.toDouble() / n)
            phase += f / SAMPLE_RATE
            osc(wave, phase) * exp(-decay * t) * gain * edge(i, n)
        }
    }

    private fun noise(seconds: Double, decay: Double, gain: Double): DoubleArray {
        val rnd = Random(42) // deterministic so the sounds are identical on every device
        val n = samples(seconds)
        return DoubleArray(n) { i -> (rnd.nextDouble() * 2 - 1) * exp(-decay * i / SAMPLE_RATE) * gain * edge(i, n) }
    }

    private fun silence(seconds: Double) = DoubleArray(samples(seconds))

    private fun tremolo(x: DoubleArray, rateHz: Double) =
        DoubleArray(x.size) { i -> x[i] * (0.65 + 0.35 * sin(2 * PI * rateHz * i / SAMPLE_RATE)) }

    private fun concat(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.sumOf { it.size })
        var o = 0
        for (p in parts) { p.copyInto(out, o); o += p.size }
        return out
    }

    private fun mix(vararg parts: DoubleArray): DoubleArray {
        val out = DoubleArray(parts.maxOf { it.size })
        for (p in parts) for (i in p.indices) out[i] += p[i]
        return out
    }

    /** Normalises to 90% full scale if the mix would clip, then quantises. */
    private fun toPcm(x: DoubleArray): ShortArray {
        val peak = x.maxOfOrNull { kotlin.math.abs(it) } ?: 0.0
        val scale = if (peak > 0.9) 0.9 / peak else 1.0
        return ShortArray(x.size) { i -> (max(-1.0, min(1.0, x[i] * scale)) * Short.MAX_VALUE).toInt().toShort() }
    }
}
