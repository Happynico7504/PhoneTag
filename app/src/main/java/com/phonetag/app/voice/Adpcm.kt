package com.phonetag.app.voice

/**
 * IMA ADPCM: 16-bit PCM → 4 bits per sample (4:1), with no native libraries and essentially no
 * latency. Each frame starts with the codec state (predictor + step index), so frames decode
 * independently and a dropped frame costs only its own 40 ms.
 *
 * Frame layout: predictor (int16 LE), step index (uint8), reserved (uint8), then two samples per
 * byte, low nibble first.
 */
class AdpcmEncoder {
    private var predictor = 0
    private var index = 0

    fun encode(pcm: ShortArray): ByteArray {
        val out = ByteArray(HEADER_BYTES + (pcm.size + 1) / 2)
        out[0] = (predictor and 0xFF).toByte()
        out[1] = (predictor shr 8 and 0xFF).toByte()
        out[2] = index.toByte()
        for (i in pcm.indices) {
            val code = encodeSample(pcm[i].toInt())
            val pos = HEADER_BYTES + i / 2
            out[pos] = if (i % 2 == 0) code.toByte() else (out[pos].toInt() or (code shl 4)).toByte()
        }
        return out
    }

    private fun encodeSample(sample: Int): Int {
        val step = STEPS[index]
        var diff = sample - predictor
        var code = 0
        if (diff < 0) {
            code = 8
            diff = -diff
        }
        var delta = step shr 3
        if (diff >= step) { code = code or 4; diff -= step; delta += step }
        if (diff >= step shr 1) { code = code or 2; diff -= step shr 1; delta += step shr 1 }
        if (diff >= step shr 2) { code = code or 1; delta += step shr 2 }
        predictor = (if (code and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
        index = (index + INDEX_TABLE[code]).coerceIn(0, 88)
        return code
    }

    companion object {
        const val HEADER_BYTES = 4
    }
}

object AdpcmDecoder {
    /** Decodes one frame; returns null if it's malformed. */
    fun decode(frame: ByteArray): ShortArray? {
        if (frame.size < AdpcmEncoder.HEADER_BYTES) return null
        var predictor = ((frame[0].toInt() and 0xFF) or (frame[1].toInt() shl 8)).toShort().toInt()
        var index = frame[2].toInt() and 0xFF
        if (index > 88) return null
        val n = (frame.size - AdpcmEncoder.HEADER_BYTES) * 2
        val out = ShortArray(n)
        for (i in 0 until n) {
            val b = frame[AdpcmEncoder.HEADER_BYTES + i / 2].toInt()
            val code = if (i % 2 == 0) b and 0x0F else (b shr 4) and 0x0F
            val step = STEPS[index]
            var delta = step shr 3
            if (code and 4 != 0) delta += step
            if (code and 2 != 0) delta += step shr 1
            if (code and 1 != 0) delta += step shr 2
            predictor = (if (code and 8 != 0) predictor - delta else predictor + delta).coerceIn(-32768, 32767)
            index = (index + INDEX_TABLE[code]).coerceIn(0, 88)
            out[i] = predictor.toShort()
        }
        return out
    }
}

private val INDEX_TABLE = intArrayOf(-1, -1, -1, -1, 2, 4, 6, 8, -1, -1, -1, -1, 2, 4, 6, 8)

private val STEPS = intArrayOf(
    7, 8, 9, 10, 11, 12, 13, 14, 16, 17, 19, 21, 23, 25, 28, 31, 34, 37, 41, 45, 50, 55, 60, 66, 73, 80, 88, 97, 107, 118,
    130, 143, 157, 173, 190, 209, 230, 253, 279, 307, 337, 371, 408, 449, 494, 544, 598, 658, 724, 796, 876, 963, 1060,
    1166, 1282, 1411, 1552, 1707, 1878, 2066, 2272, 2499, 2749, 3024, 3327, 3660, 4026, 4428, 4871, 5358, 5894, 6484, 7132,
    7845, 8630, 9493, 10442, 11487, 12635, 13899, 15289, 16818, 18500, 20350, 22385, 24623, 27086, 29794, 32767,
)
