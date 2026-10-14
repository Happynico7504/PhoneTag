package com.phonetag.core

import java.net.InetSocketAddress
import java.net.Socket

/**
 * Client side of the protocol. Used by the app for both Wi-Fi Direct and online play,
 * and by tests/bots. Callbacks arrive on the connection's reader thread.
 */
class GameClient(
    private val onMessage: (ServerMessage) -> Unit,
    private val onDisconnected: () -> Unit,
) {
    @Volatile private var conn: Connection? = null

    val isConnected get() = conn?.isOpen == true

    /** Blocking connect; call off the main thread. */
    fun connect(host: String, port: Int = Protocol.DEFAULT_PORT, timeoutMs: Int = 8_000) {
        val socket = Socket()
        socket.connect(InetSocketAddress(host, port), timeoutMs)
        val c = Connection(socket, ::onLine, onDisconnected, readTimeoutMs = 30_000, name = "server")
        conn = c
        c.start()
    }

    fun send(msg: ClientMessage) {
        conn?.send(Protocol.encode(msg))
    }

    /** Real-time data (voice): dropped rather than queued if the connection is backed up. */
    fun sendDroppable(msg: ClientMessage) {
        conn?.sendDroppable(Protocol.encode(msg))
    }

    fun disconnect() {
        conn?.let {
            it.send(Protocol.encode(ClientMessage.Leave))
            it.close()
        }
        conn = null
    }

    private fun onLine(line: String) {
        val msg = runCatching { Protocol.decodeServer(line) }.getOrNull() ?: return
        onMessage(msg)
    }
}
