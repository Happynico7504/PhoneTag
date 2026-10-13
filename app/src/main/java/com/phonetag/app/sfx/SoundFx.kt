package com.phonetag.app.sfx

import android.content.Context
import android.media.AudioAttributes
import android.media.SoundPool
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Low-latency playback of the synthesized effects. Sounds are rendered once into WAV files in
 * the cache directory (on a background thread) and loaded into a [SoundPool], which mixes
 * overlapping effects such as rapid fire on top of hit confirms.
 */
class SoundFx(private val context: Context) {
    private val pool = SoundPool.Builder()
        .setMaxStreams(8)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_GAME)
                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                .build()
        )
        .build()
    private val ids = ConcurrentHashMap<Sfx, Int>()
    private val loaded = ConcurrentHashMap.newKeySet<Int>()

    @Volatile var enabled = true

    init {
        pool.setOnLoadCompleteListener { _, sampleId, status -> if (status == 0) loaded += sampleId }
        Thread({
            val dir = File(context.cacheDir, "sfx-v$VERSION").apply { mkdirs() }
            for (sfx in Sfx.entries) {
                val file = File(dir, "${sfx.name.lowercase()}.wav")
                if (!file.exists()) runCatching { file.writeBytes(Synth.wav(Synth.render(sfx))) }
                runCatching { ids[sfx] = pool.load(file.path, 1) }
            }
        }, "sfx-init").start()
    }

    fun play(sfx: Sfx, volume: Float = 1f, rate: Float = 1f) {
        if (!enabled) return
        val id = ids[sfx] ?: return
        if (id !in loaded) return
        pool.play(id, volume, volume, if (sfx == Sfx.LASER) 2 else 1, 0, rate.coerceIn(0.5f, 2f))
    }

    fun release() = pool.release()

    private companion object {
        /** Bump when the synth recipes change so cached WAVs are regenerated. */
        const val VERSION = 2
    }
}
