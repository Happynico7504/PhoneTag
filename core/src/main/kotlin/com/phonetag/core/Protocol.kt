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
    const val VERSION = 8

    /** Hard cap on players per lobby. Never configurable above this. */
    const val MAX_PLAYERS_PER_LOBBY = 16

    const val DEFAULT_PORT = 47474

    /** The open-lobby list only shows lobbies whose players are within this distance of you. */
    const val LOBBY_LIST_RADIUS_KM = 5.0

    /** Max base64 length of one voice frame (40 ms of ADPCM is ~430 chars). */
    const val MAX_VOICE_CHARS = 1024

    /** Upper bound on a single framed line; protects the server from memory abuse. */
    const val MAX_LINE_BYTES = 64 * 1024

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
enum class ErrorCode {
    LOBBY_FULL, LOBBY_NOT_FOUND, MATCH_IN_PROGRESS, NOT_HOST, BAD_VERSION, BAD_REQUEST, SERVER_FULL,
    /** AR camera tracking is mandatory: phones without ARCore can't join. */
    AR_REQUIRED,
    /** The match can't start until every player is synced to the arena. */
    NOT_SYNCED,
    /** Cheating was detected from this IP or device; it's locked out for a while. */
    LOCKED_OUT,
}

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

@Serializable
enum class HitMethod {
    /** Both players are synced to the shared AR arena: precise 3D ray cast. */
    ARENA,
    /** GPS + compass cone. */
    GPS,
    /** GPS + compass cone, widened because the shooter's camera saw a person in the crosshair. */
    GPS_VISION,
}

/**
 * A position/orientation fix reported by a phone. Bearings are degrees from true north.
 * [arena] is present when the phone is synced to the lobby's shared AR arena (camera tracking).
 */
@Serializable
data class Pose(
    val lat: Double,
    val lon: Double,
    val accuracyM: Double,
    val azimuthDeg: Double,
    val pitchDeg: Double = 0.0,
    val arena: ArenaPose? = null,
)

/** Camera position and viewing direction in the shared arena frame (metres, +Y up). */
@Serializable
data class ArenaPose(val epoch: Int, val position: Vec3, val forward: Vec3)

/** What the shooter's camera saw when firing. Every field is optional evidence. */
@Serializable
data class VisionHint(
    /** True if a person's body box covered the crosshair, false if detection saw nobody there, null if unknown. */
    val personInCrosshair: Boolean? = null,
    /** Distance estimated from the person's apparent height, if the whole body was visible. */
    val estDistanceM: Double? = null,
    /** AR depth measured straight down the crosshair: nothing solid is closer than this. */
    val clearDistanceM: Double? = null,
)

/** The lobby's shared AR frame. Bumping [epoch] invalidates every device's sync. */
@Serializable
data class ArenaInfo(val epoch: Int = 0, val referenceId: Int = 0)

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
    /** Pose of the sync marker on this player's screen (arena frame), while they show it. */
    val beacon: Rigid? = null,
    /** Shots this match that hit a bystander ("random") instead of a player. */
    val randoms: Int = 0,
)

@Serializable
data class LobbySummary(
    val code: String,
    val name: String,
    val players: Int,
    val maxPlayers: Int,
    val phase: Phase,
    /** Rounded-up distance to the lobby in km (no coordinates are ever shared). */
    val distanceKm: Int? = null,
)

@Serializable
data class ScoreLine(val id: Int, val name: String, val team: Team, val score: Int, val deaths: Int, val randoms: Int = 0)

// ---------------------------------------------------------------- client -> server

@Serializable
sealed class ClientMessage {
    /**
     * First message on every connection. [lobbyCode] null + [create]=false joins the only lobby
     * (Wi-Fi Direct). [arTracking]: this phone runs ARCore camera tracking, which every player must.
     */
    @Serializable @SerialName("hello")
    data class Hello(
        val protocol: Int,
        val name: String,
        val create: Boolean = false,
        val lobbyCode: String? = null,
        val lobbyName: String? = null,
        val arTracking: Boolean = false,
        /** Stable, hashed per-install device ID; lets the server lock out cheaters across reconnects. */
        val deviceId: String? = null,
    ) : ClientMessage()

    @Serializable @SerialName("list")
    /**
     * Asks for open lobbies near ([Protocol.LOBBY_LIST_RADIUS_KM]) the given GPS position. The
     * server filters, so lobby locations never leave it. No position = empty list.
     */
    data class ListLobbies(val lat: Double? = null, val lon: Double? = null) : ClientMessage()

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
    data class Shoot(val seq: Int, val pose: Pose, val vision: VisionHint? = null) : ClientMessage()

    /** Start (non-null [screen]) or stop showing this phone's sync marker. */
    @Serializable @SerialName("beacon")
    data class SetBeacon(val epoch: Int, val screen: Rigid?) : ClientMessage()

    /** Walls this phone's AR tracking currently sees, in arena coordinates (replaces its previous report). */
    @Serializable @SerialName("walls")
    data class ReportWalls(val epoch: Int, val walls: List<Wall>) : ClientMessage()

    /** Host only: start a fresh arena whose frame is defined by [referenceId]'s phone. */
    @Serializable @SerialName("arena")
    data class ResetArena(val referenceId: Int) : ClientMessage()

    @Serializable @SerialName("ping")
    data class Ping(val clientTimeMs: Long) : ClientMessage()

    /** One frame of compressed voice audio (base64). The server forwards it over open radio links only. */
    @Serializable @SerialName("voice")
    data class Voice(val seq: Int, val data: String) : ClientMessage()

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
        val arena: ArenaInfo = ArenaInfo(),
    ) : ServerMessage()

    /** Periodic world state: everyone's tracked position, health and score. */
    @Serializable @SerialName("snap")
    data class Snapshot(
        val timeLeftMs: Long,
        val players: List<PlayerSnapshot>,
        /** Pairs of players currently connected by proximity voice chat (far enough apart). */
        val radio: List<RadioLink> = emptyList(),
    ) : ServerMessage()

    /** Voice audio from another player, forwarded only over an open [RadioLink]. */
    @Serializable @SerialName("voice")
    data class Voice(val from: Int, val seq: Int, val data: String) : ServerMessage()

    /** Sent to the shooter for every shot so the client can show hit/miss feedback. */
    @Serializable @SerialName("shot")
    data class ShotResult(
        val seq: Int,
        val hitPlayerId: Int?,
        val distanceM: Double?,
        val eliminated: Boolean,
        val method: HitMethod? = null,
        /** The shot would have hit someone, but a wall or obstacle was in the way. */
        val blocked: Boolean = false,
        /** Rejected: the shooter's phone wasn't tracking in the arena when firing. */
        val untracked: Boolean = false,
        /** Missed every player, but the camera saw a person in the crosshair: a bystander was "shot". */
        val random: Boolean = false,
    ) : ServerMessage()

    /** The shared wall map of the current arena epoch (sent when it changes, at most every 2 s). */
    @Serializable @SerialName("walls")
    data class Obstacles(val epoch: Int, val walls: List<Wall>) : ServerMessage()

    /** Sent to the player who got hit. */
    @Serializable @SerialName("hit")
    data class HitBy(val shooterId: Int, val damage: Int, val health: Int, val eliminated: Boolean) : ServerMessage()

    /** Broadcast when someone is tagged out. */
    @Serializable @SerialName("tag")
    data class Tagged(val shooterId: Int, val victimId: Int) : ServerMessage()

    /** Broadcast when the server removes a player for cheating. */
    @Serializable @SerialName("kicked")
    data class PlayerKicked(val playerId: Int, val name: String, val reason: String) : ServerMessage()

    /** Broadcast when someone shoots a bystander; [total] is their count this match. */
    @Serializable @SerialName("random")
    data class RandomShot(val shooterId: Int, val total: Int) : ServerMessage()

    @Serializable @SerialName("end")
    data class MatchEnded(val scores: List<ScoreLine>, val winnerTeam: Team?, val winnerId: Int?) : ServerMessage()

    @Serializable @SerialName("pong")
    data class Pong(val clientTimeMs: Long) : ServerMessage()
}
