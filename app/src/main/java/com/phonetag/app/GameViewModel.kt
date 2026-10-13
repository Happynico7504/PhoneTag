package com.phonetag.app

import android.app.Application
import android.content.Context
import android.os.Build
import android.os.SystemClock
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.phonetag.app.ar.ArEngine
import com.phonetag.app.ar.ArSupport
import com.phonetag.app.net.GroupInfo
import com.phonetag.app.net.NearbyGame
import com.phonetag.app.net.WifiDirect
import com.phonetag.app.sensors.Aim
import com.phonetag.app.light.TorchController
import com.phonetag.app.sensors.LightSensor
import com.phonetag.app.sensors.LocationTracker
import com.phonetag.app.sensors.OrientationTracker
import com.phonetag.app.sfx.Sfx
import com.phonetag.app.sfx.SoundFx
import com.phonetag.app.vision.PersonDetector
import com.phonetag.core.ArenaInfo
import com.phonetag.core.ClientMessage
import com.phonetag.core.GameClient
import com.phonetag.core.GameServer
import com.phonetag.core.GameSettings
import com.phonetag.core.LobbySummary
import com.phonetag.core.Phase
import com.phonetag.core.Pose
import com.phonetag.core.Protocol
import com.phonetag.core.ServerMessage
import com.phonetag.core.Team
import com.phonetag.core.VisionHint
import com.phonetag.core.Wall
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

enum class Screen { HOME, NEARBY, ONLINE, CONNECTING, SESSION }

enum class Transport { WIFI_DIRECT_HOST, WIFI_DIRECT_JOIN, ONLINE }

/** Flashlight behaviour, chosen per phone. */
enum class TorchMode { AUTO, ON, OFF }

data class FeedItem(val text: String, val atMs: Long)

/** One-shot moments the AR view turns into visual effects (beams, sparks, damage indicators…). */
sealed interface GameEvent {
    data object Fired : GameEvent
    data class HitDealt(val victimId: Int, val eliminated: Boolean) : GameEvent
    data class HitTaken(val shooterId: Int, val eliminated: Boolean) : GameEvent
    data object Respawned : GameEvent
    data object MatchStarted : GameEvent
    /** Our shot would have hit, but a wall/obstacle was in the way. */
    data object ShotBlocked : GameEvent
}

data class UiState(
    val screen: Screen = Screen.HOME,
    val playerName: String = "",
    val serverAddress: String = "",
    val status: String? = null,
    val error: String? = null,
    val transport: Transport? = null,
    val myId: Int = 0,
    val lobby: ServerMessage.LobbyState? = null,
    val snapshot: ServerMessage.Snapshot? = null,
    val results: ServerMessage.MatchEnded? = null,
    val onlineLobbies: List<LobbySummary> = emptyList(),
    val feed: List<FeedItem> = emptyList(),
    val soundOn: Boolean = true,
    /** Full-screen AR view for syncing this phone to the shared arena. */
    val showArenaSync: Boolean = false,
    /** Showing our sync marker so other players can scan it. */
    val showingMarker: Boolean = false,
    /** Shared wall map of the current arena (from every synced phone's plane detection). */
    val walls: List<Wall> = emptyList(),
    val torchMode: TorchMode = TorchMode.AUTO,
    val torchOn: Boolean = false,
) {
    val me get() = snapshot?.players?.firstOrNull { it.id == myId }
    val isHost get() = lobby?.hostId == myId
    fun nameOf(id: Int) = lobby?.players?.firstOrNull { it.id == id }?.name ?: "#$id"
    fun teamOf(id: Int) = lobby?.players?.firstOrNull { it.id == id }?.team ?: Team.NONE
    /** Whether a player is currently reporting a pose in the lobby's arena. */
    fun isSynced(id: Int) = lobby != null && lobby.arena.epoch > 0 &&
        snapshot?.players?.firstOrNull { it.id == id }?.pose?.arena?.epoch == lobby.arena.epoch
}

/**
 * Owns the whole session: Wi-Fi Direct group, the optional local [GameServer] (when this
 * phone hosts), the [GameClient] connection and the sensors feeding our pose to the server.
 */
class GameViewModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("phonetag", Context.MODE_PRIVATE)

    private val _state = MutableStateFlow(
        UiState(
            playerName = prefs.getString("name", null) ?: Build.MODEL.take(16),
            serverAddress = prefs.getString("server", "") ?: "",
            soundOn = prefs.getBoolean("sound", true),
        )
    )
    val state: StateFlow<UiState> = _state.asStateFlow()

    val wifiDirect = WifiDirect(app)
    val nearbyGames: StateFlow<List<NearbyGame>> = wifiDirect.nearby
    val groupInfo: StateFlow<GroupInfo?> = wifiDirect.groupInfo

    private val orientation = OrientationTracker(app)
    private val locations = LocationTracker(app)
    val aim: StateFlow<Aim> = orientation.aim
    val location = locations.location

    /** ARCore camera tracking + shared arena; idle on phones without ARCore. */
    val ar = ArEngine(app)
    /** On-device person detection, fed by ARCore or CameraX frames. */
    val personDetector = PersonDetector()
    private var beaconSent = false

    // Flashlight: decided locally on every phone, never synchronised.
    private val lightSensor = LightSensor(app)
    private val torch = TorchController()
    @Volatile private var cameraXTorch: ((Boolean) -> Unit)? = null
    @Volatile private var cameraXBrightness: Float? = null
    private var torchApplied: Boolean? = null

    private var wallsSentVersion = -1

    private var localServer: GameServer? = null
    private var client: GameClient? = null
    private var poseJob: Job? = null
    private var shotSeq = 0
    private var lastShotAtMs = 0L
    @Volatile private var leaving = false

    private val sfx = SoundFx(app).also { it.enabled = _state.value.soundOn }
    private val _events = MutableSharedFlow<GameEvent>(extraBufferCapacity = 32)
    val events: SharedFlow<GameEvent> = _events.asSharedFlow()
    private val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= 31) {
        app.getSystemService(VibratorManager::class.java)?.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        app.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    }

    // ------------------------------------------------------------------ sensors (permission-gated)

    fun onPermissionsGranted() {
        locations.start()
        orientation.start()
        lightSensor.start()
        ar.imageSink = { image, rotation ->
            personDetector.process(image, rotation) {
                image.close()
                ar.imageDone()
            }
        }
        viewModelScope.launch {
            // Tell the lobby once we know whether this phone can do AR.
            ar.support.collect {
                // Only once we're in a lobby: before the Welcome the server rejects anything but Hello.
                if (it != ArSupport.CHECKING && _state.value.myId != 0) client?.send(ClientMessage.SetCapabilities(it == ArSupport.SUPPORTED))
            }
        }
        viewModelScope.launch {
            locations.location.collect { loc -> loc?.let(orientation::updateDeclination) }
        }
    }

    fun currentPose(): Pose? {
        val loc = locations.location.value ?: return null
        val a = orientation.aim.value
        return Pose(loc.latitude, loc.longitude, loc.accuracy.toDouble().coerceAtLeast(1.0), a.azimuthDeg, a.pitchDeg, ar.arenaPose)
    }

    // ------------------------------------------------------------------ navigation

    fun setName(name: String) {
        _state.update { it.copy(playerName = name.take(20)) }
        prefs.edit().putString("name", name.take(20)).apply()
    }

    fun setServerAddress(address: String) {
        _state.update { it.copy(serverAddress = address.trim()) }
        prefs.edit().putString("server", address.trim()).apply()
    }

    fun dismissError() = _state.update { it.copy(error = null) }

    fun openNearby() {
        _state.update { it.copy(screen = Screen.NEARBY, error = null) }
        wifiDirect.startDiscovery()
    }

    fun refreshNearby() = wifiDirect.startDiscovery()

    fun openOnline() {
        _state.update { it.copy(screen = Screen.ONLINE, error = null, onlineLobbies = emptyList()) }
    }

    fun back() {
        if (_state.value.showArenaSync) return closeArenaSync()
        when (_state.value.screen) {
            Screen.NEARBY -> wifiDirect.stopDiscovery()
            Screen.SESSION, Screen.CONNECTING -> return leave()
            else -> Unit
        }
        _state.update { it.copy(screen = Screen.HOME) }
    }

    // ------------------------------------------------------------------ Wi-Fi Direct

    /** This phone becomes the Wi-Fi Direct group owner and runs the authoritative server. */
    fun hostNearby() {
        val name = _state.value.playerName
        connecting(Transport.WIFI_DIRECT_HOST, "Creating Wi-Fi Direct group…")
        wifiDirect.host(
            lobbyName = "$name's game",
            onReady = {
                viewModelScope.launch {
                    val ok = withContext(Dispatchers.IO) {
                        runCatching {
                            localServer?.stop()
                            localServer = GameServer(port = Protocol.DEFAULT_PORT, singleLobby = true).also { it.start() }
                        }
                    }
                    ok.onFailure { return@launch fail("Could not start game server: ${it.message}") }
                    connectTo("127.0.0.1", Protocol.DEFAULT_PORT, ClientMessage.Hello(Protocol.VERSION, name, create = true, lobbyName = "$name's game"))
                }
            },
            onError = ::fail,
        )
    }

    fun joinNearby(game: NearbyGame) {
        wifiDirect.stopDiscovery()
        connecting(Transport.WIFI_DIRECT_JOIN, "Connecting to ${game.lobbyName ?: game.deviceName}… (the host may need to accept)")
        wifiDirect.join(
            game,
            onConnected = { ownerIp ->
                _state.update { it.copy(status = "Joined Wi-Fi Direct group, opening game connection…") }
                connectTo(ownerIp, Protocol.DEFAULT_PORT, ClientMessage.Hello(Protocol.VERSION, _state.value.playerName), retries = 8)
            },
            onError = ::fail,
        )
    }

    // ------------------------------------------------------------------ dedicated server

    fun createOnline() = online(ClientMessage.Hello(Protocol.VERSION, _state.value.playerName, create = true, lobbyName = "${_state.value.playerName}'s game"))

    fun joinOnline(code: String) = online(ClientMessage.Hello(Protocol.VERSION, _state.value.playerName, lobbyCode = code.trim().uppercase()))

    fun refreshOnlineLobbies() {
        val (host, port) = parseAddress(_state.value.serverAddress) ?: return fail("Enter a server address like play.example.com:47474")
        viewModelScope.launch(Dispatchers.IO) {
            val probe = GameClient(
                onMessage = { msg -> if (msg is ServerMessage.LobbyList) _state.update { it.copy(onlineLobbies = msg.lobbies) } },
                onDisconnected = {},
            )
            runCatching {
                probe.connect(host, port)
                probe.send(ClientMessage.ListLobbies)
                delay(1500)
            }.onFailure { e -> _state.update { it.copy(error = "Server unreachable: ${e.message}") } }
            probe.disconnect()
        }
    }

    private fun online(hello: ClientMessage.Hello) {
        val (host, port) = parseAddress(_state.value.serverAddress) ?: return fail("Enter a server address like play.example.com:47474")
        connecting(Transport.ONLINE, "Connecting to $host:$port…")
        connectTo(host, port, hello)
    }

    private fun parseAddress(raw: String): Pair<String, Int>? {
        if (raw.isBlank()) return null
        val idx = raw.lastIndexOf(':')
        return if (idx > 0 && !raw.substring(0, idx).contains(':')) {
            raw.substring(0, idx) to (raw.substring(idx + 1).toIntOrNull() ?: return null)
        } else {
            raw to Protocol.DEFAULT_PORT
        }
    }

    // ------------------------------------------------------------------ session

    private fun connecting(transport: Transport, status: String) {
        leaving = false
        _state.update {
            it.copy(screen = Screen.CONNECTING, transport = transport, status = status, error = null,
                lobby = null, snapshot = null, results = null, feed = emptyList(), myId = 0)
        }
    }

    private fun connectTo(host: String, port: Int, hello: ClientMessage.Hello, retries: Int = 1) {
        viewModelScope.launch {
            val c = GameClient(onMessage = ::onServerMessage, onDisconnected = ::onDisconnected)
            var lastError: Throwable? = null
            for (attempt in 1..retries) {
                if (leaving) return@launch
                val r = withContext(Dispatchers.IO) { runCatching { c.connect(host, port) } }
                if (r.isSuccess) {
                    client = c
                    c.send(hello)
                    startPoseLoop()
                    return@launch
                }
                lastError = r.exceptionOrNull()
                delay(1000) // the group owner's IP / server may need a moment
            }
            fail("Could not reach game at $host:$port (${lastError?.message})")
        }
    }

    private fun startPoseLoop() {
        poseJob?.cancel()
        poseJob = viewModelScope.launch {
            var tick = 0
            while (isActive) {
                val c = client ?: break
                currentPose()?.let { c.send(ClientMessage.PoseUpdate(it)) }
                sendBeacon(c)
                sendWalls(c)
                updateTorch()
                if (tick++ % 25 == 0) c.send(ClientMessage.Ping(System.currentTimeMillis()))
                delay(200)
            }
        }
    }

    private fun onServerMessage(msg: ServerMessage) {
        val now = System.currentTimeMillis()
        when (msg) {
            is ServerMessage.Welcome -> {
                ar.myId = msg.playerId
                _state.update { it.copy(myId = msg.playerId, screen = Screen.SESSION, status = null) }
                if (ar.support.value != ArSupport.CHECKING) client?.send(ClientMessage.SetCapabilities(ar.support.value == ArSupport.SUPPORTED))
            }
            is ServerMessage.LobbyState -> {
                ar.arena = msg.arena
                val starting = _state.value.lobby?.phase != Phase.RUNNING && msg.phase == Phase.RUNNING
                _state.update { s ->
                    s.copy(
                        lobby = msg,
                        results = if (starting) null else s.results,
                        feed = if (starting) emptyList() else s.feed,
                        snapshot = if (starting) null else s.snapshot,
                        // The game view takes over the AR camera (and keeps the arena sync).
                        showArenaSync = if (starting) false else s.showArenaSync,
                        showingMarker = if (starting) false else s.showingMarker,
                    )
                }
                if (starting) {
                    sfx.play(Sfx.MATCH_START)
                    _events.tryEmit(GameEvent.MatchStarted)
                }
            }
            is ServerMessage.Snapshot -> {
                ar.beacons = msg.players.filter { it.id != _state.value.myId }.mapNotNull { p -> p.beacon?.let { p.id to it } }.toMap()
                val wasDead = _state.value.me?.alive == false
                _state.update { it.copy(snapshot = msg) }
                if (wasDead && _state.value.me?.alive == true) {
                    sfx.play(Sfx.RESPAWN)
                    _events.tryEmit(GameEvent.Respawned)
                }
            }
            is ServerMessage.ShotResult -> {
                val victim = msg.hitPlayerId
                if (victim != null) {
                    sfx.play(if (msg.eliminated) Sfx.TAG else Sfx.HIT_CONFIRM)
                    _events.tryEmit(GameEvent.HitDealt(victim, msg.eliminated))
                } else if (msg.blocked) {
                    sfx.play(Sfx.BLOCKED)
                    _events.tryEmit(GameEvent.ShotBlocked)
                }
            }
            is ServerMessage.Obstacles -> _state.update { it.copy(walls = msg.walls) }
            is ServerMessage.HitBy -> {
                vibrate(if (msg.eliminated) 600 else 150)
                sfx.play(if (msg.eliminated) Sfx.TAGGED_OUT else Sfx.DAMAGE)
                _events.tryEmit(GameEvent.HitTaken(msg.shooterId, msg.eliminated))
            }
            is ServerMessage.Tagged -> _state.update { s ->
                val text = "${s.nameOf(msg.shooterId)} tagged ${s.nameOf(msg.victimId)}"
                s.copy(feed = (s.feed + FeedItem(text, now)).takeLast(5))
            }
            is ServerMessage.MatchEnded -> {
                sfx.play(Sfx.MATCH_END)
                _state.update { it.copy(results = msg) }
            }
            is ServerMessage.Error -> viewModelScope.launch {
                if (_state.value.myId == 0) fail(msg.message) else _state.update { it.copy(error = msg.message) }
            }
            is ServerMessage.LobbyList -> _state.update { it.copy(onlineLobbies = msg.lobbies) }
            is ServerMessage.Pong -> Unit
        }
    }

    private fun onDisconnected() {
        if (leaving) return
        viewModelScope.launch { fail("Connection to the game was lost") }
    }

    // ------------------------------------------------------------------ actions

    fun fire(vision: VisionHint? = null) {
        val now = System.currentTimeMillis()
        val s = _state.value
        val cooldown = s.lobby?.settings?.fireCooldownMs ?: 350
        if (now - lastShotAtMs < cooldown || s.me?.alive == false) return
        val pose = currentPose() ?: return _state.update { it.copy(error = "Waiting for GPS fix…") }
        lastShotAtMs = now
        // Tiny pitch variation keeps rapid fire from sounding robotic.
        sfx.play(Sfx.LASER, rate = 0.94f + (shotSeq % 5) * 0.03f)
        _events.tryEmit(GameEvent.Fired)
        client?.send(ClientMessage.Shoot(++shotSeq, pose, vision))
    }

    // ------------------------------------------------------------------ arena sync

    fun openArenaSync() = _state.update { it.copy(showArenaSync = true) }

    fun closeArenaSync() = _state.update { it.copy(showArenaSync = false, showingMarker = false) }

    fun toggleMarker() = _state.update { it.copy(showingMarker = !it.showingMarker) }

    /** Host only: start a new arena defined by [referenceId]'s phone; everyone re-syncs. */
    fun resetArena(referenceId: Int) = client?.send(ClientMessage.ResetArena(referenceId))

    /** While our marker is on screen and we're synced and holding still, broadcast its arena pose. */
    private fun sendBeacon(c: GameClient) {
        val screen = ar.screenInArena
        val epoch = ar.arena.epoch
        val active = _state.value.showingMarker && ar.sync.value.steady && screen != null
        if (active) {
            c.send(ClientMessage.SetBeacon(epoch, screen))
            beaconSent = true
        } else if (beaconSent) {
            c.send(ClientMessage.SetBeacon(epoch, null))
            beaconSent = false
        }
    }

    /** Our AR walls go to the server whenever they change (the engine re-scans every 2 s). */
    private fun sendWalls(c: GameClient) {
        val version = ar.wallsVersion
        if (version == wallsSentVersion || ar.arenaPose == null) return
        wallsSentVersion = version
        c.send(ClientMessage.ReportWalls(ar.arena.epoch, ar.walls))
    }

    /**
     * Combines person detection ([base], from the UI's view geometry) with AR depth down the
     * crosshair into the evidence sent with a shot.
     */
    fun withDepth(base: VisionHint?): VisionHint? {
        val depth = ar.crosshairDepth() ?: return base
        return (base ?: VisionHint()).copy(clearDistanceM = depth)
    }

    // ------------------------------------------------------------------ flashlight

    /** Called by the CameraX viewfinder: how to switch its torch (null when it closes). */
    fun attachCameraXTorch(sink: ((Boolean) -> Unit)?) {
        cameraXTorch = sink
        cameraXBrightness = null
        torch.reset()
        torchApplied = null
    }

    fun onCameraXBrightness(brightness: Float) {
        cameraXBrightness = brightness
    }

    fun cycleTorchMode() = _state.update {
        it.copy(torchMode = TorchMode.entries[(it.torchMode.ordinal + 1) % TorchMode.entries.size])
    }

    /** ~5 Hz: decide and apply the flashlight for whichever camera is open. */
    private fun updateTorch() {
        val usingAr = ar.isRunning
        val cameraX = cameraXTorch
        if (!usingAr && cameraX == null) return // no camera open: nothing to light
        val want = when (_state.value.torchMode) {
            TorchMode.ON -> true
            TorchMode.OFF -> false
            TorchMode.AUTO -> torch.update(
                TorchController.Input(
                    nowMs = SystemClock.elapsedRealtime(),
                    lux = lightSensor.lux,
                    cameraBrightness = if (usingAr) ar.cameraBrightness else cameraXBrightness,
                    insufficientLight = usingAr && ar.insufficientLight,
                )
            )
        }
        if (want != torchApplied) {
            torchApplied = want
            if (usingAr) ar.setTorch(want) else cameraX?.invoke(want)
            // The CameraX sink only exists if there is a flash unit; ARCore reports it separately.
            _state.update { it.copy(torchOn = want && (!usingAr || ar.torchSupported)) }
        }
    }

    /** UI cue when an enemy enters the crosshair. */
    fun onLockAcquired() = sfx.play(Sfx.LOCK_ON, volume = 0.6f)

    fun playHeartbeat() = sfx.play(Sfx.HEARTBEAT, volume = 0.8f)

    fun toggleSound() {
        val on = !_state.value.soundOn
        sfx.enabled = on
        prefs.edit().putBoolean("sound", on).apply()
        _state.update { it.copy(soundOn = on) }
    }

    fun setTeam(team: Team) = client?.send(ClientMessage.SetTeam(team))
    fun updateSettings(settings: GameSettings) = client?.send(ClientMessage.UpdateSettings(settings))
    fun startMatch() = client?.send(ClientMessage.StartMatch)
    fun backToLobby() = client?.send(ClientMessage.BackToLobby)

    fun leave() {
        leaving = true
        poseJob?.cancel()
        val c = client
        val server = localServer
        client = null
        localServer = null
        // Plain thread: this also runs from onCleared(), when viewModelScope is already cancelled.
        Thread {
            c?.disconnect()
            server?.stop()
        }.start()
        if (_state.value.transport != Transport.ONLINE) wifiDirect.leave()
        ar.arena = ArenaInfo()
        ar.myId = 0
        ar.beacons = emptyMap()
        beaconSent = false
        ar.setTorch(false)
        torch.reset()
        torchApplied = null
        wallsSentVersion = -1
        _state.update {
            UiState(playerName = it.playerName, serverAddress = it.serverAddress, error = it.error, soundOn = it.soundOn, torchMode = it.torchMode)
        }
    }

    private fun fail(message: String) {
        leave()
        _state.update { it.copy(error = message) }
    }

    private fun vibrate(ms: Long) {
        vibrator?.vibrate(VibrationEffect.createOneShot(ms, VibrationEffect.DEFAULT_AMPLITUDE))
    }

    override fun onCleared() {
        leave()
        wifiDirect.close()
        locations.stop()
        lightSensor.stop()
        orientation.stop()
        sfx.release()
        ar.close()
        personDetector.close()
    }
}
