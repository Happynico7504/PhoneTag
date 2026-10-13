package com.phonetag.server

import com.phonetag.core.GameServer
import com.phonetag.core.Protocol
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import kotlin.system.exitProcess

/**
 * Dedicated PhoneTag server for games whose players are spread further apart than
 * Wi-Fi Direct can reach. Phones connect over mobile data / any Wi-Fi.
 *
 * Usage: phonetag-server [--port 47474] [--max-lobbies 500]
 */
fun main(args: Array<String>) {
    var port = System.getenv("PHONETAG_PORT")?.toIntOrNull() ?: Protocol.DEFAULT_PORT
    var maxLobbies = 500
    var i = 0
    while (i < args.size) {
        when (args[i]) {
            "--port", "-p" -> port = args.getOrNull(++i)?.toIntOrNull() ?: usage()
            "--max-lobbies" -> maxLobbies = args.getOrNull(++i)?.toIntOrNull() ?: usage()
            "--help", "-h" -> usage(0)
            else -> usage()
        }
        i++
    }

    val time = DateTimeFormatter.ofPattern("HH:mm:ss")
    val server = GameServer(
        port = port,
        singleLobby = false,
        maxLobbies = maxLobbies,
        log = { println("[${LocalTime.now().format(time)}] $it") },
    )
    server.start()
    println("Lobbies are capped at ${Protocol.MAX_PLAYERS_PER_LOBBY} players. Ctrl+C to stop.")
    Runtime.getRuntime().addShutdownHook(Thread { server.stop() })
    Thread.currentThread().join()
}

private fun usage(code: Int = 1): Nothing {
    println("Usage: phonetag-server [--port N] [--max-lobbies N]   (env PHONETAG_PORT also works)")
    exitProcess(code)
}
