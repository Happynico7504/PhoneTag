package com.phonetag.core

/**
 * Authoritative game state for one lobby (max 16 players).
 *
 * AR camera tracking is mandatory for fairness: only AR-capable phones may join, a match only
 * starts once everyone is synced to the shared arena, and only a phone that is tracking in the
 * arena can fire. A target whose tracking drops stays hittable via its GPS position, so losing
 * tracking (or covering the camera) never protects you.
 *
 * Pure logic: no sockets or threads. Every public method is synchronized so the network layer
 * can call it from any connection thread, and outgoing messages go through each player's [sink],
 * which must not block (see [Connection.send]). Time comes from [clock] so tests can drive it.
 */
class Lobby(
    val code: String,
    val name: String,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Reports things only a modified client does (see [Violation]). The server tallies them per
     * connection and locks persistent offenders out. Called with this lobby's lock held.
     */
    private val onViolation: (playerId: Int, Violation) -> Unit = { _, _ -> },
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
        var randoms: Int = 0,
        /** Last arena position, for the speed check (survives gaps without tracking). */
        var lastArena: ArenaPose? = null,
        var lastArenaAtMs: Long = 0,
        var beacon: Rigid? = null,
        var beaconAtMs: Long = 0,
    ) {
        val alive get() = health > 0
        fun info(hostId: Int) = PlayerInfo(id, name, team, id == hostId)
    }

    sealed class JoinResult {
        data class Joined(val playerId: Int) : JoinResult()
        data class Rejected(val code: ErrorCode, val message: String) : JoinResult()
    }

    private val players = LinkedHashMap<Int, Player>()
    private val radio = VoiceRouter()
    private var nextId = 1
    private var hostId = 0
    private var matchEndsAtMs = 0L

    var settings = GameSettings()
        private set
    var phase = Phase.LOBBY
        private set
    var arena = ArenaInfo()
        private set

    /** Shared wall map of the current arena epoch, per reporting phone. Kept when a player leaves: walls don't move. */
    private val wallsByPlayer = LinkedHashMap<Int, List<Wall>>()
    private var wallsDirty = false
    private var wallsBroadcastAtMs = 0L
    private val allWalls get() = wallsByPlayer.values.flatten().take(MAX_WALLS_TOTAL)

    @get:Synchronized
    val playerCount get() = players.size

    /** Where the lobby is: the host's last GPS fix, else any player's. Null until someone has one. */
    @Synchronized
    fun location(): Pair<Double, Double>? =
        (players[hostId]?.pose ?: players.values.firstNotNullOfOrNull { it.pose })?.let { it.lat to it.lon }

    @Synchronized
    fun summary() = LobbySummary(code, name, players.size, settings.maxPlayers, phase)

    @Synchronized
    fun join(playerName: String, arTracking: Boolean, sink: (ServerMessage) -> Unit): JoinResult {
        if (!arTracking) {
            return JoinResult.Rejected(ErrorCode.AR_REQUIRED, "This game needs AR camera tracking (ARCore), which this phone doesn't support")
        }
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
        // The first player's phone defines the shared arena.
        if (arena.epoch == 0) arena = ArenaInfo(epoch = 1, referenceId = id)
        p.sink(ServerMessage.Welcome(id, code))
        broadcastLobby()
        if (wallsByPlayer.isNotEmpty()) p.sink(ServerMessage.Obstacles(arena.epoch, allWalls))
        return JoinResult.Joined(id)
    }

    /** Removes a cheater and tells everyone else why. */
    @Synchronized
    fun kick(playerId: Int, reason: String) {
        val p = players[playerId] ?: return
        leave(playerId)
        broadcast(ServerMessage.PlayerKicked(playerId, p.name, reason))
    }

    @Synchronized
    fun leave(playerId: Int) {
        players.remove(playerId) ?: return
        if (playerId == hostId) hostId = players.keys.firstOrNull() ?: 0
        // Synced players keep sharing the frame; only the "reference" label goes away.
        if (playerId == arena.referenceId) arena = arena.copy(referenceId = 0)
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
            is ClientMessage.Voice -> {
                if (msg.data.length > Protocol.MAX_VOICE_CHARS) return onViolation(p.id, Violation.BAD_DATA)
                val frame = ServerMessage.Voice(p.id, msg.seq, msg.data)
                for (q in players.values) if (q.id != p.id && radio.canHear(p.id, q.id)) q.sink(frame)
            }
            is ClientMessage.SetBeacon -> {
                val screen = msg.screen
                if (screen != null && !screen.isFinite()) onViolation(p.id, Violation.BAD_DATA)
                if (screen == null || msg.epoch != arena.epoch || !screen.isFinite()) {
                    p.beacon = null
                } else {
                    p.beacon = screen
                    p.beaconAtMs = clock()
                }
            }
            is ClientMessage.ReportWalls -> {
                if (msg.epoch != arena.epoch || arena.epoch == 0) return
                wallsByPlayer[p.id] = msg.walls.filter { it.isSane() }.take(MAX_WALLS_PER_PLAYER)
                wallsDirty = true
            }
            is ClientMessage.ResetArena -> {
                if (!requireHost(p)) return
                if (phase == Phase.RUNNING) {
                    // Would unsync everyone mid-match.
                    p.sink(ServerMessage.Error(ErrorCode.MATCH_IN_PROGRESS, "Can't restart the arena during a match"))
                    return
                }
                val ref = players[msg.referenceId]
                if (ref == null) {
                    p.sink(ServerMessage.Error(ErrorCode.BAD_REQUEST, "No such player"))
                    return
                }
                arena = ArenaInfo(epoch = arena.epoch + 1, referenceId = ref.id)
                players.values.forEach { it.beacon = null }
                wallsByPlayer.clear()
                broadcastLobby()
                broadcast(ServerMessage.Obstacles(arena.epoch, emptyList()))
            }
            is ClientMessage.Hello, is ClientMessage.ListLobbies, ClientMessage.Leave -> Unit // handled by the server
        }
    }

    /**
     * Called periodically (~5 Hz) by the server: respawns, match timer and world snapshot.
     * Snapshots also go out in the lobby so players can see each other and sync the AR arena.
     */
    @Synchronized
    fun tick() {
        val now = clock()
        radio.update(
            players.values.map { VoiceRouter.Member(it.id, it.team, it.pose?.takeIf { _ -> now - it.poseAtMs <= STALE_POSE_MS }) },
            settings.mode, now,
        )
        if (wallsDirty && now - wallsBroadcastAtMs >= WALLS_BROADCAST_INTERVAL_MS) {
            wallsDirty = false
            wallsBroadcastAtMs = now
            broadcast(ServerMessage.Obstacles(arena.epoch, allWalls))
        }
        if (phase == Phase.ENDED) return
        if (phase == Phase.LOBBY) {
            broadcastSnapshot(now, timeLeftMs = 0)
            return
        }
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
        broadcastSnapshot(now, matchEndsAtMs - now)
    }

    private fun broadcastSnapshot(now: Long, timeLeftMs: Long) {
        val snap = ServerMessage.Snapshot(
            timeLeftMs = timeLeftMs,
            players = players.values.map {
                PlayerSnapshot(
                    id = it.id,
                    pose = it.pose,
                    health = it.health,
                    alive = it.alive,
                    respawnInMs = if (it.alive) 0 else (it.respawnAtMs - now).coerceAtLeast(0),
                    score = it.score,
                    deaths = it.deaths,
                    beacon = it.beacon?.takeIf { _ -> now - it.beaconAtMs <= STALE_BEACON_MS },
                    randoms = it.randoms,
                )
            },
            radio = radio.links(),
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
        if (!pose.isPlausible()) return onViolation(p.id, Violation.BAD_DATA)
        val now = clock()
        // Arena poses from an older (reset) arena epoch are meaningless now.
        val arenaPose = pose.arena
        val valid = arenaPose != null && arenaPose.epoch == arena.epoch && arenaPose.position.isFinite() && arenaPose.forward.isFinite()
        p.pose = if (arenaPose != null && !valid) pose.copy(arena = null) else pose
        p.poseAtMs = now
        if (valid && arenaPose != null) {
            checkMovement(p, arenaPose, now)
            p.lastArena = arenaPose
            p.lastArenaAtMs = now
        }
    }

    /**
     * Speed / teleport check on precise arena positions (GPS is far too jumpy to judge). Small
     * moves are ignored so network bunching of pose updates can't look like speed, and the
     * teleport check only covers short gaps (a player who lost tracking may legitimately walk far).
     */
    private fun checkMovement(p: Player, pose: ArenaPose, now: Long) {
        val last = p.lastArena ?: return
        if (last.epoch != pose.epoch) return
        val dt = now - p.lastArenaAtMs
        if (dt <= 0) return
        val v = pose.position - last.position
        val d = kotlin.math.sqrt(v.x * v.x + v.z * v.z)
        when {
            d > TELEPORT_M && dt < TELEPORT_WINDOW_MS -> onViolation(p.id, Violation.TELEPORT)
            d > MIN_SPEED_CHECK_M && d / (dt / 1000.0) > MAX_SPEED_MPS -> onViolation(p.id, Violation.SPEED)
        }
    }

    private fun startMatch() {
        if (phase == Phase.RUNNING) return
        if (players.size < 2) {
            players[hostId]?.sink?.invoke(ServerMessage.Error(ErrorCode.BAD_REQUEST, "Need at least 2 players"))
            return
        }
        val now = clock()
        val unsynced = players.values.filter { !it.isTrackedInArena(now) }
        if (unsynced.isNotEmpty()) {
            players[hostId]?.sink?.invoke(
                ServerMessage.Error(ErrorCode.NOT_SYNCED, "Waiting for ${unsynced.joinToString { it.name }} to sync AR tracking")
            )
            return
        }
        if (settings.mode == GameMode.TEAMS) {
            players.values.filter { it.team == Team.NONE }.forEach { it.team = smallerTeam() }
        }
        for (p in players.values) {
            p.health = MAX_HEALTH
            p.score = 0
            p.deaths = 0
            p.randoms = 0
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
        val sinceLast = now - shooter.lastShotAtMs
        if (sinceLast < settings.fireCooldownMs) {
            // The app never fires faster than the cooldown; well under it is a modified client
            // (half the cooldown leaves room for network jitter bunching two honest shots).
            if (sinceLast < settings.fireCooldownMs / 2) onViolation(shooter.id, Violation.FIRE_RATE)
            return
        }
        if (!msg.pose.isPlausible()) return onViolation(shooter.id, Violation.BAD_DATA)
        shooter.lastShotAtMs = now
        // The shot carries the freshest fix of the shooter; use it as their pose too.
        updatePose(shooter, msg.pose)
        if (shooter.pose?.arena == null) {
            // Fairness: everyone shoots with precise camera tracking or not at all. The app disables
            // its fire button without tracking, so this mostly comes from modified clients.
            onViolation(shooter.id, Violation.UNTRACKED_SHOT)
            shooter.sink(ServerMessage.ShotResult(msg.seq, null, null, false, untracked = true))
            return
        }

        val candidates = players.values.filter { t ->
            t.id != shooter.id && t.alive && now >= t.invulnerableUntilMs &&
                t.pose != null && now - t.poseAtMs <= STALE_POSE_MS &&
                !(settings.mode == GameMode.TEAMS && t.team == shooter.team)
        }.map { HitResolver.Candidate(it.id, it.pose!!) }

        val resolution = HitResolver.resolveDetailed(shooter.pose!!, candidates, settings.maxRangeM, msg.vision, arena.epoch, allWalls)
        val hit = resolution.hit
        if (hit == null) {
            // Every other player with a known position counts here (any team, alive or not):
            // a person in the crosshair near *any* player is not a bystander.
            val everyone = players.values.filter { it.id != shooter.id && it.pose != null && now - it.poseAtMs <= STALE_POSE_MS }
                .map { HitResolver.Candidate(it.id, it.pose!!) }
            val random = resolution.blockedId == null &&
                HitResolver.isBystanderShot(shooter.pose!!, everyone, settings.maxRangeM, msg.vision)
            shooter.sink(ServerMessage.ShotResult(msg.seq, null, null, false, blocked = resolution.blockedId != null, random = random))
            if (random) {
                shooter.randoms++
                broadcast(ServerMessage.RandomShot(shooter.id, shooter.randoms))
            }
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
        shooter.sink(ServerMessage.ShotResult(msg.seq, victim.id, hit.distanceM, eliminated, hit.method))
        victim.sink(ServerMessage.HitBy(shooter.id, settings.damage, victim.health, eliminated))
        if (eliminated) broadcast(ServerMessage.Tagged(shooter.id, victim.id))
    }

    private fun endMatch() {
        phase = Phase.ENDED
        val lines = players.values
            .map { ScoreLine(it.id, it.name, it.team, it.score, it.deaths, it.randoms) }
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
                arena = arena,
            )
        )
    }

    private fun broadcast(msg: ServerMessage) = players.values.forEach { it.sink(msg) }

    private fun Player.isTrackedInArena(now: Long) =
        pose?.arena?.epoch == arena.epoch && now - poseAtMs <= STALE_POSE_MS

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
        const val STALE_BEACON_MS = 1_500L

        /** Faster than a sprinting human, sustained between two pose updates. */
        const val MAX_SPEED_MPS = 12.0
        const val MIN_SPEED_CHECK_M = 3.0
        const val TELEPORT_M = 30.0
        const val TELEPORT_WINDOW_MS = 2_000L
        const val MAX_WALLS_PER_PLAYER = 32
        const val MAX_WALLS_TOTAL = 192
        const val WALLS_BROADCAST_INTERVAL_MS = 2_000L
    }
}
