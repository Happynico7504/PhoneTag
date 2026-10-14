package com.phonetag.app.servers

import com.phonetag.core.Protocol

/** A saved server: a display name and its `host:port` address. */
data class FavoriteServer(val name: String, val address: String)

/**
 * The player's favorite servers, kept on the phone (one line per server in preferences).
 * Addresses are normalised (lower-case host, explicit port) so the same server isn't saved twice.
 */
object FavoriteServers {
    const val MAX = 20
    private const val SEP = '\t'

    /** `Host:Port` → `host:port`, adding the default port if missing. Null if it isn't a usable address. */
    fun normalize(raw: String): String? {
        val s = raw.trim()
        if (s.isEmpty() || s.any { it.isWhitespace() || it == SEP }) return null
        val idx = s.lastIndexOf(':')
        val (host, port) = if (idx > 0 && !s.substring(0, idx).contains(':')) {
            s.substring(0, idx) to (s.substring(idx + 1).toIntOrNull() ?: return null)
        } else {
            s to Protocol.DEFAULT_PORT
        }
        if (host.isEmpty() || port !in 1..65535) return null
        return "${host.lowercase()}:$port"
    }

    /** Adds (or renames, if already saved) a server at the top of the list. */
    fun add(list: List<FavoriteServer>, name: String, address: String): List<FavoriteServer> {
        val normalized = normalize(address) ?: return list
        val label = name.filter { it != SEP && it != '\n' }.trim().take(32).ifEmpty { normalized.substringBefore(':') }
        return (listOf(FavoriteServer(label, normalized)) + list.filter { it.address != normalized }).take(MAX)
    }

    fun remove(list: List<FavoriteServer>, address: String): List<FavoriteServer> {
        val normalized = normalize(address) ?: address
        return list.filter { it.address != normalized }
    }

    fun encode(list: List<FavoriteServer>): String = list.joinToString("\n") { "${it.name}$SEP${it.address}" }

    fun decode(stored: String?): List<FavoriteServer> = stored.orEmpty().lineSequence().mapNotNull { line ->
        val name = line.substringBefore(SEP, "")
        val address = normalize(line.substringAfter(SEP, "")) ?: return@mapNotNull null
        FavoriteServer(name.ifEmpty { address.substringBefore(':') }, address)
    }.distinctBy { it.address }.take(MAX).toList()
}
