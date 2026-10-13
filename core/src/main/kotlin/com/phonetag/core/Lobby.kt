package com.phonetag.core

/**
 * Authoritative game state for one lobby (max 16 players).
 *
 * Pure logic: no sockets or threads. Every public method is synchronized so the network layer
 * can call it from any connection thread, and outgoing messages go through each player's [sink],
 * which must not block (see [Connection.send]). Time comes from [clock] so tests can drive it.
 */
class Lobby(
    val code: String,
    val name: String,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    private class Player(
        val id: Int,
        val name: String,
        val sink: (ServerMessage) -> Unit,
        var team: Team = Team.NONE,
        var pose: Pose? = null,
        var poseAtMs: Long = 0,
        var health: Int = MAX_HEALTH,
        var respawnAtMs: Long = 0,
        var invulnerableUntilMs: Long = 0,
        var lastShotAtMs: Long = 0,
        var score: Int = 0,
        var deaths: Int = 0,
    ) {
        val alive get() = health > 0
        fun info(hostId: Int) = PlayerInfo(id, name, team, id == hostId)
    }

    sealed class JoinResult {
        data class Joined(val playerId: Int) : JoinResult()
        data class Rejected(val code: ErrorCode, val message: String) : JoinResult()
    }

    private val players = LinkedHashMap<Int, Player>()
    private var nextId = 1
    private var hostId = 0
    private var matchEndsAtMs = 0L

    var settings = GameSettings()
        private set
    var phase = Phase.LOBBY
        private set

    @get:Synchronized
    val playerCount get() = players.size

    @Synchronized
    fun summary() = LobbySummary(code, name, players.size, settings.maxPlayers, phase)

    @Synchronized
    fun join(playerName: String, sink: (ServerMessage) -> Unit): JoinResult {
        if (players.size >= settings.maxPlayers) {
            return JoinResult.Rejected(ErrorCode.LOBBY_FULL, "Lobby is full (${settings.maxPlayers} players max)")
        }
        if (phase == Phase.RUNNING) {
            return JoinResult.Rejected(ErrorCode.MATCH_IN_PROGRESS, "A match is already running")
        }
        val id = nextId++
        val p = Player(id, cleanName(playerName, id), sink)
        if (settings.mode == GameMode.TEAMS) p.team = smallerTeam()
        players[id] = p
        if (hostId == 0) hostId = id
        p.sink(ServerMessage.Welcome(id, code))
        broadcastLobby()
        return JoinResult.Joined(id)
    }

    @Synchronized
    fun leave(playerId: Int) {
        players.remove(playerId) ?: return
        if (playerId == hostId) hostId = players.keys.firstOrNull() ?: 0
        if (phase == Phase.RUNNING && players.size < 2) endMatch()
        broadcastLobby()
    }

    @Synchronized
    fun handle(playerId: Int, msg: ClientMessage) {
        val p = players[playerId] ?: return
        when (msg) {
            is ClientMessage.PoseUpdate -> updatePose(p, msg.pose)
            is ClientMessage.Shoot -> shoot(p, msg)
            is ClientMessage.SetTeam -> {
                if (phase != Phase.RUNNING && msg.team != Team.NONE) {
                    p.team = msg.team
                    broadcastLobby()
                }
            }
            is ClientMessage.UpdateSettings -> {
                if (!requireHost(p) || phase == Phase.RUNNING) return
                val newSettings = msg.settings.sanitized()
                    .let { it.copy(maxPlayers = it.maxPlayers.coerceAtLeast(players.size)) }
                if (newSettings.mode == GameMode.TEAMS && settings.mode != GameMode.TEAMS) {
                    players.values.forEach { it.team = Team.NONE }
                    players.values.forEach { it.team = smallerTeam() }
                }
                settings = newSettings
                broadcastLobby()
            }
            is ClientMessage.StartMatch -> if (requireHost(p)) startMatch()
            is ClientMessage.BackToLobby -> if (requireHost(p) && phase == Phase.ENDED) {
                phase = Phase.LOBBY
                broadcastLobby()
            }
            is ClientMessage.Ping -> p.sink(ServerMessage.Pong(msg.clientTimeMs))
            is ClientMessage.Hello, ClientMessage.ListLobbies, ClientMessage.Leave -> Unit // handled by the server
        }
    }

    /** Called periodically (~5 Hz) by the server: respawns, match timer and world snapshot. */
    @Synchronized
    fun tick() {
        if (phase != Phase.RUNNING) return
        val now = clock()
        for (p in players.values) {
            if (!p.alive && now >= p.respawnAtMs) {
                p.health = MAX_HEALTH
                p.invulnerableUntilMs = now + SPAWN_PROTECTION_MS
            }
        }
        if (now >= matchEndsAtMs) {
            endMatch()
            return
        }
        val snap = ServerMessage.Snapshot(
            timeLeftMs = matchEndsAtMs - now,
            players = players.values.map {
                PlayerSnapshot(
                    id = it.id,
                    pose = it.pose,
                    health = it.health,
                    alive = it.alive,
                    respawnInMs = if (it.alive) 0 else (it.respawnAtMs - now).coerceAtLeast(0),
                    score = it.score,
                    deaths = it.deaths,
                )
            },
        )
        broadcast(snap)
    }

    // ------------------------------------------------------------------ internals

    private fun requireHost(p: Player): Boolean {
        if (p.id == hostId) return true
        p.sink(ServerMessage.Error(ErrorCode.NOT_HOST, "Only the host can do that"))
        return false
    }

    private fun updatePose(p: Player, pose: Pose) {
        if (!pose.isPlausible()) return
        p.pose = pose
        p.poseAtMs = clock()
    }

    private fun startMatch() {
        if (phase == Phase.RUNNING) return
        if (players.size < 2) {
            players[hostId]?.sink?.invoke(ServerMessage.Error(ErrorCode.BAD_REQUEST, "Need at least 2 players"))
            return
        }
        val now = clock()
        if (settings.mode == GameMode.TEAMS) {
            players.values.filter { it.team == Team.NONE }.forEach { it.team = smallerTeam() }
        }
        for (p in players.values) {
            p.health = MAX_HEALTH
            p.score = 0
            p.deaths = 0
            p.respawnAtMs = 0
            p.lastShotAtMs = 0
            p.invulnerableUntilMs = now + SPAWN_PROTECTION_MS
        }
        matchEndsAtMs = now + settings.matchDurationSec * 1000L
        phase = Phase.RUNNING
        broadcastLobby()
        tick()
    }

    private fun shoot(shooter: Player, msg: ClientMessage.Shoot) {
        if (phase != Phase.RUNNING || !shooter.alive) return
        val now = clock()
        if (now - shooter.lastShotAtMs < settings.fireCooldownMs) return
        if (!msg.pose.isPlausible()) return
        shooter.lastShotAtMs = now
        // The shot carries the freshest fix of the shooter; use it as their pose too.
        updatePose(shooter, msg.pose)

        val candidates = players.values.filter { t ->
            t.id != shooter.id && t.alive && now >= t.invulnerableUntilMs &&
                t.pose != null && now - t.poseAtMs <= STALE_POSE_MS &&
                !(settings.mode == GameMode.TEAMS && t.team == shooter.team)
        }.map { HitResolver.Candidate(it.id, it.pose!!) }

        val hit = HitResolver.resolve(msg.pose, candidates, settings.maxRangeM)
        if (hit == null) {
            shooter.sink(ServerMessage.ShotResult(msg.seq, null, null, false))
            return
        }
        val victim = players.getValue(hit.targetId)
        victim.health = (victim.health - settings.damage).coerceAtLeast(0)
        val eliminated = !victim.alive
        if (eliminated) {
            victim.deaths++
            victim.respawnAtMs = now + settings.respawnSec * 1000L
            shooter.score++
        }
        shooter.sink(ServerMessage.ShotResult(msg.seq, victim.id, hit.distanceM, eliminated))
        victim.sink(ServerMessage.HitBy(shooter.id, settings.damage, victim.health, eliminated))
        if (eliminated) broadcast(ServerMessage.Tagged(shooter.id, victim.id))
    }

    private fun endMatch() {
        phase = Phase.ENDED
        val lines = players.values
            .map { ScoreLine(it.id, it.name, it.team, it.score, it.deaths) }
            .sortedWith(compareByDescending<ScoreLine> { it.score }.thenBy { it.deaths })
        var winnerTeam: Team? = null
        var winnerId: Int? = null
        if (settings.mode == GameMode.TEAMS) {
            val red = lines.filter { it.team == Team.RED }.sumOf { it.score }
            val blue = lines.filter { it.team == Team.BLUE }.sumOf { it.score }
            winnerTeam = when {
                red > blue -> Team.RED
                blue > red -> Team.BLUE
                else -> null
            }
        } else if (lines.isNotEmpty() && (lines.size == 1 || lines[0].score > lines[1].score)) {
            winnerId = lines[0].id
        }
        broadcast(ServerMessage.MatchEnded(lines, winnerTeam, winnerId))
        broadcastLobby()
    }

    private fun smallerTeam(): Team {
        val red = players.values.count { it.team == Team.RED }
        val blue = players.values.count { it.team == Team.BLUE }
        return if (blue < red) Team.BLUE else Team.RED
    }

    private fun broadcastLobby() {
        broadcast(
            ServerMessage.LobbyState(
                code = code,
                name = name,
                hostId = hostId,
                phase = phase,
                settings = settings,
                players = players.values.map { it.info(hostId) },
            )
        )
    }

    private fun broadcast(msg: ServerMessage) = players.values.forEach { it.sink(msg) }

    private fun Pose.isPlausible() =
        lat in -90.0..90.0 && lon in -180.0..180.0 && accuracyM >= 0 &&
            azimuthDeg.isFinite() && pitchDeg.isFinite() && lat.isFinite() && lon.isFinite()

    private fun cleanName(raw: String, id: Int): String {
        val trimmed = raw.filter { !it.isISOControl() }.trim().take(20)
        return trimmed.ifEmpty { "Player $id" }
    }

    companion object {
        const val MAX_HEALTH = 100
        const val SPAWN_PROTECTION_MS = 3_000L
        /** Targets whose last fix is older than this can't be hit (they may have moved anywhere). */
        const val STALE_POSE_MS = 3_000L
    }
}
