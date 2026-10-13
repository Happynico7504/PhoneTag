package com.phonetag.core

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * Wire protocol shared by the Android app and the dedicated server.
 *
 * Every message is one line of JSON terminated by '\n'. The same protocol is used
 * whether the authoritative [GameServer] runs on the Wi-Fi Direct group owner's phone
 * or on a dedicated machine on the internet, so the client never needs to care which.
 */
object Protocol {
    const val VERSION = 1

    /** Hard cap on players per lobby. Never configurable above this. */
    const val MAX_PLAYERS_PER_LOBBY = 16

    const val DEFAULT_PORT = 47474

    /** Upper bound on a single framed line; protects the server from memory abuse. */
    const val MAX_LINE_BYTES = 16 * 1024

    val json = Json {
        classDiscriminator = "t"
        ignoreUnknownKeys = true
        encodeDefaults = true
    }

    fun encode(msg: ClientMessage): String = json.encodeToString(ClientMessage.serializer(), msg)
    fun encode(msg: ServerMessage): String = json.encodeToString(ServerMessage.serializer(), msg)
    fun decodeClient(line: String): ClientMessage = json.decodeFromString(ClientMessage.serializer(), line)
    fun decodeServer(line: String): ServerMessage = json.decodeFromString(ServerMessage.serializer(), line)
}

@Serializable
enum class Team { NONE, RED, BLUE }

@Serializable
enum class GameMode { FREE_FOR_ALL, TEAMS }

@Serializable
enum class Phase { LOBBY, RUNNING, ENDED }

@Serializable
enum class ErrorCode { LOBBY_FULL, LOBBY_NOT_FOUND, MATCH_IN_PROGRESS, NOT_HOST, BAD_VERSION, BAD_REQUEST, SERVER_FULL }

@Serializable
data class GameSettings(
    val mode: GameMode = GameMode.FREE_FOR_ALL,
    val maxPlayers: Int = Protocol.MAX_PLAYERS_PER_LOBBY,
    val matchDurationSec: Int = 600,
    /** Beyond this distance a shot can never hit. */
    val maxRangeM: Double = 60.0,
    val damage: Int = 25,
    val respawnSec: Int = 8,
    val fireCooldownMs: Long = 350,
) {
    /** Clamps every value into a sane range; maxPlayers can never exceed 16. */
    fun sanitized() = copy(
        maxPlayers = maxPlayers.coerceIn(2, Protocol.MAX_PLAYERS_PER_LOBBY),
        matchDurationSec = matchDurationSec.coerceIn(60, 3600),
        maxRangeM = maxRangeM.coerceIn(5.0, 200.0),
        damage = damage.coerceIn(1, 100),
        respawnSec = respawnSec.coerceIn(0, 60),
        fireCooldownMs = fireCooldownMs.coerceIn(100, 5000),
    )
}

/** A position/orientation fix reported by a phone. Bearings are degrees from true north. */
@Serializable
data class Pose(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val azimuthDeg: Double,
    val pitchDeg: Double = 0.0,
)

@Serializable
data class PlayerInfo(
    val id: Int,
    val name: String,
    val team: Team,
    val isHost: Boolean,
)

@Serializable
data class PlayerSnapshot(
    val id: Int,
    val pose: Pose?,
    val health: Int,
    val alive: Boolean,
    val respawnInMs: Long,
    val score: Int,
    val deaths: Int,
)

@Serializable
data class LobbySummary(
    val code: String,
    val name: String,
    val players: Int,
    val maxPlayers: Int,
    val phase: Phase,
)

@Serializable
data class ScoreLine(val id: Int, val name: String, val team: Team, val score: Int, val deaths: Int)

// ---------------------------------------------------------------- client -> server

@Serializable
sealed class ClientMessage {
    /** First message on every connection. [lobbyCode] null + [create]=false joins the only lobby (Wi-Fi Direct). */
    @Serializable @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val name: String,
        val create: Boolean = false,
        val lobbyCode: String? = null,
        val lobbyName: String? = null,
    ) : ClientMessage()

    @Serializable @SerialName("list")
    data object ListLobbies : ClientMessage()

    @Serializable @SerialName("team")
    data class SetTeam(val team: Team) : ClientMessage()

    @Serializable @SerialName("settings")
    data class UpdateSettings(val settings: GameSettings) : ClientMessage()

    @Serializable @SerialName("start")
    data object StartMatch : ClientMessage()

    @Serializable @SerialName("reset")
    data object BackToLobby : ClientMessage()

    @Serializable @SerialName("pose")
    data class PoseUpdate(val pose: Pose) : ClientMessage()

    @Serializable @SerialName("shoot")
    data class Shoot(val seq: Int, val pose: Pose) : ClientMessage()

    @Serializable @SerialName("ping")
    data class Ping(val clientTimeMs: Long) : ClientMessage()

    @Serializable @SerialName("leave")
    data object Leave : ClientMessage()
}

// ---------------------------------------------------------------- server -> client

@Serializable
sealed class ServerMessage {
    @Serializable @SerialName("welcome")
    data class Welcome(val playerId: Int, val lobbyCode: String) : ServerMessage()

    @Serializable @SerialName("error")
    data class Error(val code: ErrorCode, val message: String) : ServerMessage()

    @Serializable @SerialName("lobbies")
    data class LobbyList(val lobbies: List<LobbySummary>) : ServerMessage()

    @Serializable @SerialName("lobby")
    data class LobbyState(
        val code: String,
        val name: String,
        val hostId: Int,
        val phase: Phase,
        val settings: GameSettings,
        val players: List<PlayerInfo>,
        val maxPlayers: Int = Protocol.MAX_PLAYERS_PER_LOBBY,
    ) : ServerMessage()

    /** Periodic world state: everyone's tracked position, health and score. */
    @Serializable @SerialName("snap")
    data class Snapshot(
        val timeLeftMs: Long,
        val players: List<PlayerSnapshot>,
    ) : ServerMessage()

    /** Sent to the shooter for every shot so the client can show hit/miss feedback. */
    @Serializable @SerialName("shot")
    data class ShotResult(val seq: Int, val hitPlayerId: Int?, val distanceM: Double?, val eliminated: Boolean) : ServerMessage()

    /** Sent to the player who got hit. */
    @Serializable @SerialName("hit")
    data class HitBy(val shooterId: Int, val damage: Int, val health: Int, val eliminated: Boolean) : ServerMessage()

    /** Broadcast when someone is tagged out. */
    @Serializable @SerialName("tag")
    data class Tagged(val shooterId: Int, val victimId: Int) : ServerMessage()

    @Serializable @SerialName("end")
    data class MatchEnded(val scores: List<ScoreLine>, val winnerTeam: Team?, val winnerId: Int?) : ServerMessage()

    @Serializable @SerialName("pong")
    data class Pong(val clientTimeMs: Long) : ServerMessage()
}
