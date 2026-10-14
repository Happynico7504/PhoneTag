package com.phonetag.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.Socket
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Newline-delimited text framing over a TCP socket with a dedicated writer thread.
 *
 * [send] never blocks: lines go into a bounded queue drained by the writer thread. A peer that
 * can't keep up (queue full) is disconnected, so one slow phone can never stall a lobby.
 */
class Connection(
    private val socket: Socket,
    private val onLine: (String) -> Unit,
    private val onClosed: () -> Unit,
    readTimeoutMs: Int = 20_000,
    private val name: String = "conn",
) {
    private val outbox = ArrayBlockingQueue<String>(OUTBOX_CAPACITY)
    private val closed = AtomicBoolean(false)

    val remoteAddress: String get() = socket.remoteSocketAddress?.toString() ?: "?"
    val isOpen get() = !closed.get()

    init {
        socket.tcpNoDelay = true
        socket.soTimeout = readTimeoutMs
    }

    fun start() {
        Thread(::readLoop, "$name-reader").apply { isDaemon = true }.start()
        Thread(::writeLoop, "$name-writer").apply { isDaemon = true }.start()
    }

    @Volatile private var closing = false

    fun send(line: String) {
        if (closed.get() || closing) return
        if (!outbox.offer(line)) close()
    }

    /** Sends one last line (e.g. why we're disconnecting them), then closes once it's written. */
    fun sendAndClose(line: String) {
        if (closed.get() || closing) return
        closing = true
        if (!outbox.offer(line) || !outbox.offer(POISON)) close()
    }

    /**
     * For real-time data that is worthless when late (voice): dropped instead of queued when the
     * peer is falling behind, so it can never cause a slow-consumer disconnect.
     */
    fun sendDroppable(line: String) {
        if (closed.get() || closing || outbox.size > OUTBOX_CAPACITY / 4) return
        outbox.offer(line)
    }

    fun close() {
        if (!closed.compareAndSet(false, true)) return
        outbox.clear()
        outbox.offer(POISON)
        runCatching { socket.close() }
        onClosed()
    }

    private fun readLoop() {
        try {
            val input = socket.getInputStream().buffered()
            while (!closed.get()) {
                val line = readLine(input) ?: break
                if (line.isNotBlank()) onLine(line)
            }
        } catch (_: IOException) {
            // socket closed, reset or timed out
        } finally {
            close()
        }
    }

    private fun writeLoop() {
        try {
            val out = socket.getOutputStream().buffered()
            while (true) {
                var line = outbox.poll(1, TimeUnit.SECONDS) ?: if (closed.get()) break else continue
                // Write everything queued, then flush once.
                while (true) {
                    if (line === POISON) {
                        // Flush what was queued before the poison (sendAndClose), then hang up.
                        out.flush()
                        close()
                        return
                    }
                    out.write(line.toByteArray(Charsets.UTF_8))
                    out.write('\n'.code)
                    line = outbox.poll() ?: break
                }
                out.flush()
            }
        } catch (_: IOException) {
            close()
        }
    }

    /** Reads one '\n'-terminated UTF-8 line, refusing anything longer than [Protocol.MAX_LINE_BYTES]. */
    private fun readLine(input: InputStream): String? {
        val buf = ByteArrayOutputStream(256)
        while (true) {
            val b = input.read()
            if (b == -1) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8)
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8)
            if (buf.size() >= Protocol.MAX_LINE_BYTES) throw IOException("$name: line too long")
            buf.write(b)
        }
    }

    private companion object {
        const val OUTBOX_CAPACITY = 512
        val POISON = String(charArrayOf('\u0000'))
    }
}
