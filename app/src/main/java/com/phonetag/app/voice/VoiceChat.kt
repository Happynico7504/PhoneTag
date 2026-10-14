package com.phonetag.app.voice

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioDeviceInfo
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.media.AudioTrack
import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.NoiseSuppressor
import android.os.Build
import android.os.Process
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max

/**
 * Proximity voice chat audio engine: captures the mic, gates it with voice activity detection,
 * encodes 40 ms ADPCM frames for the network, and mixes incoming frames from several players
 * through small per-speaker jitter buffers into one output stream.
 *
 * Who hears whom is decided by the server (only players ≥ 10 m apart are linked); [transmit]
 * just avoids uploading when nobody is in radio range. Feedback is further reduced by the
 * platform echo canceller / noise suppressor in communication mode, and by preferring a headset.
 */
class VoiceChat(
    private val context: Context,
    /** Encoded frame ready to send. Called on the capture thread. */
    private val onFrame: (ByteArray) -> Unit,
    /** Players heard in the last moment (for "talking" indicators). Called on the playback thread. */
    private val onTalking: (Set<Int>) -> Unit,
    /** Whether our own mic currently picks up speech that is being transmitted. */
    private val onSpeaking: (Boolean) -> Unit,
) {
    /** Mic is on and at least one player is in radio range. */
    @Volatile var transmit = false

    @Volatile private var running = false
    private var playbackThread: Thread? = null
    private var captureThread: Thread? = null
    private val audio = context.getSystemService(AudioManager::class.java)
    private var previousMode = AudioManager.MODE_NORMAL

    private class Stream {
        val frames = ArrayDeque<ShortArray>()
        var buffering = true
        var lastHeardMs = 0L
    }

    private val streams = ConcurrentHashMap<Int, Stream>()

    fun start() {
        if (running) return
        running = true
        previousMode = audio.mode
        audio.mode = AudioManager.MODE_IN_COMMUNICATION
        routeOutput()
        playbackThread = Thread(::playbackLoop, "voice-playback").also { it.start() }
        startCaptureIfAllowed()
    }

    /** Starts the mic if the permission has been granted (it may come later). */
    fun startCaptureIfAllowed() {
        if (!running || captureThread != null) return
        if (ContextCompat.checkSelfPermission(context, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) return
        captureThread = Thread(::captureLoop, "voice-capture").also { it.start() }
    }

    fun stop() {
        if (!running) return
        running = false
        captureThread?.join(500)
        playbackThread?.join(500)
        captureThread = null
        playbackThread = null
        streams.clear()
        if (Build.VERSION.SDK_INT >= 31) audio.clearCommunicationDevice() else @Suppress("DEPRECATION") { audio.isSpeakerphoneOn = false }
        audio.mode = previousMode
        onTalking(emptySet())
        onSpeaking(false)
    }

    /** A frame from another player (already filtered by the server's proximity routing). */
    fun receive(from: Int, frame: ByteArray) {
        if (!running) return
        val pcm = AdpcmDecoder.decode(frame) ?: return
        val s = streams.getOrPut(from) { Stream() }
        synchronized(s) {
            s.frames.addLast(pcm)
            // Keep latency bounded: if we fell behind, drop the oldest audio.
            while (s.frames.size > MAX_BUFFERED_FRAMES) s.frames.removeFirst()
            s.lastHeardMs = SystemClock.elapsedRealtime()
        }
    }

    // ------------------------------------------------------------------ capture

    @SuppressLint("MissingPermission") // checked in startCaptureIfAllowed
    private fun captureLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val record = try {
            AudioRecord(
                MediaRecorder.AudioSource.VOICE_COMMUNICATION, // enables the platform voice processing chain
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
                max(minBuf, FRAME_SAMPLES * 2 * 4),
            )
        } catch (e: Exception) {
            Log.w(TAG, "mic unavailable: $e")
            captureThread = null
            return
        }
        if (record.state != AudioRecord.STATE_INITIALIZED) {
            record.release()
            captureThread = null
            return
        }
        val aec = if (AcousticEchoCanceler.isAvailable()) AcousticEchoCanceler.create(record.audioSessionId)?.apply { enabled = true } else null
        val ns = if (NoiseSuppressor.isAvailable()) NoiseSuppressor.create(record.audioSessionId)?.apply { enabled = true } else null
        val encoder = AdpcmEncoder()
        val vad = VoiceActivityDetector()
        val frame = ShortArray(FRAME_SAMPLES)
        var wasSpeaking = false
        try {
            record.startRecording()
            while (running) {
                var read = 0
                while (read < FRAME_SAMPLES && running) {
                    val n = record.read(frame, read, FRAME_SAMPLES - read)
                    if (n <= 0) break
                    read += n
                }
                if (read < FRAME_SAMPLES) continue
                val speech = vad.isSpeech(frame) // always run, so the noise floor keeps adapting
                val sending = speech && transmit
                if (sending) onFrame(encoder.encode(frame))
                if (sending != wasSpeaking) {
                    wasSpeaking = sending
                    onSpeaking(sending)
                }
            }
        } finally {
            runCatching { record.stop() }
            aec?.release()
            ns?.release()
            record.release()
        }
    }

    // ------------------------------------------------------------------ playback

    private fun playbackLoop() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_URGENT_AUDIO)
        val minBuf = AudioTrack.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_VOICE_COMMUNICATION)
                    .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build()
            )
            .setBufferSizeInBytes(max(minBuf, FRAME_SAMPLES * 2 * 2))
            .setTransferMode(AudioTrack.MODE_STREAM)
            .build()
        val mix = IntArray(FRAME_SAMPLES)
        val out = ShortArray(FRAME_SAMPLES)
        var lastTalking = emptySet<Int>()
        try {
            track.play()
            while (running) {
                mix.fill(0)
                val now = SystemClock.elapsedRealtime()
                val talking = HashSet<Int>()
                for ((id, s) in streams) {
                    val pcm = synchronized(s) {
                        // Start a talker only once a little audio is buffered (absorbs network jitter).
                        if (s.buffering && s.frames.size >= PREBUFFER_FRAMES) s.buffering = false
                        if (s.buffering) null else s.frames.removeFirstOrNull().also { if (it == null) s.buffering = true }
                    }
                    if (pcm != null) for (i in 0 until minOf(pcm.size, FRAME_SAMPLES)) mix[i] += pcm[i]
                    if (now - s.lastHeardMs < TALKING_HOLD_MS) talking += id
                }
                for (i in 0 until FRAME_SAMPLES) out[i] = mix[i].coerceIn(-32768, 32767).toShort()
                // Blocking write paces this loop at real time (40 ms per frame), silence included.
                track.write(out, 0, FRAME_SAMPLES)
                if (talking != lastTalking) {
                    lastTalking = talking
                    onTalking(talking)
                }
            }
        } finally {
            runCatching { track.stop() }
            track.release()
        }
    }

    /** Headset if one is connected (no acoustic feedback at all), else the loudspeaker. */
    private fun routeOutput() {
        if (Build.VERSION.SDK_INT >= 31) {
            val devices = audio.availableCommunicationDevices
            val headset = devices.firstOrNull { it.type in HEADSET_TYPES }
            val target = headset ?: devices.firstOrNull { it.type == AudioDeviceInfo.TYPE_BUILTIN_SPEAKER }
            target?.let { audio.setCommunicationDevice(it) }
        } else {
            val wired = audio.getDevices(AudioManager.GET_DEVICES_OUTPUTS).any { it.type in HEADSET_TYPES }
            @Suppress("DEPRECATION")
            audio.isSpeakerphoneOn = !wired
        }
    }

    companion object {
        private const val TAG = "VoiceChat"
        const val SAMPLE_RATE = 16_000
        /** 40 ms frames: few enough messages per second, short enough latency. */
        const val FRAME_SAMPLES = 640
        private const val PREBUFFER_FRAMES = 2
        private const val MAX_BUFFERED_FRAMES = 8
        private const val TALKING_HOLD_MS = 400L

        private val HEADSET_TYPES = buildSet {
            add(AudioDeviceInfo.TYPE_WIRED_HEADSET)
            add(AudioDeviceInfo.TYPE_WIRED_HEADPHONES)
            add(AudioDeviceInfo.TYPE_USB_HEADSET)
            add(AudioDeviceInfo.TYPE_BLUETOOTH_SCO)
            if (Build.VERSION.SDK_INT >= 31) add(AudioDeviceInfo.TYPE_BLE_HEADSET)
        }
    }
}
