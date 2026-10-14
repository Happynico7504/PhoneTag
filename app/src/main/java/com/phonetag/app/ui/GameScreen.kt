package com.phonetag.app.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonetag.app.GameEvent
import com.phonetag.app.ar.ArCameraView
import com.phonetag.app.ar.SyncMarkers
import com.phonetag.app.vision.VisionGeometry
import com.phonetag.app.GameViewModel
import com.phonetag.app.TorchMode
import com.phonetag.app.UiState
import com.phonetag.core.GameMode
import com.phonetag.core.Geo
import com.phonetag.core.HitResolver
import com.phonetag.core.Lobby
import com.phonetag.core.Pose
import com.phonetag.core.Vec3
import com.phonetag.core.VoiceRouter
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

@Composable
fun GameScreen(vm: GameViewModel, state: UiState, cameraGranted: Boolean) {
    val aim by vm.aim.collectAsStateWithLifecycle()
    val location by vm.location.collectAsStateWithLifecycle()
    val lobby = state.lobby ?: return
    val me = state.me
    val myTeam = state.teamOf(state.myId)
    val teams = lobby.settings.mode == GameMode.TEAMS
    val dead = me?.alive == false
    val lowHealth = me != null && me.alive && me.health <= 25

    // AR camera tracking is mandatory; GPS + compass only fill in while it (re)starts.
    val sync by vm.ar.sync.collectAsStateWithLifecycle()
    val detection by vm.personDetector.latest.collectAsStateWithLifecycle()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    val fov = CameraFov() // typical phone FOV, only used before ARCore reports real intrinsics

    // Recomposes with the compass (~50 Hz) anyway, so reading the latest arena pose here is fresh enough.
    val myPose = location?.let { Pose(it.latitude, it.longitude, it.accuracy.toDouble(), aim.azimuthDeg, aim.pitchDeg, vm.ar.arenaPose) }
    val others = state.snapshot?.players.orEmpty().filter { it.id != state.myId }
    val enemies = others.filter { p -> p.alive && p.pose != null && !(teams && state.teamOf(p.id) == myTeam) }

    fun focalPx() = vm.ar.view.value?.focalPx ?: fov.focalPx(viewSize.width.toFloat(), viewSize.height.toFloat())
    fun visionNow() = VisionGeometry.hint(
        vm.personDetector.latest.value, SystemClock.elapsedRealtime(), viewSize.width.toFloat(), viewSize.height.toFloat(), focalPx(),
    )
    // Client-side preview of the server's (fused) hit test, including obstacles: tells the player
    // who is in the crosshair, or that cover is in the way.
    val preview = if (myPose == null || dead) null else HitResolver.resolveDetailed(
        myPose, enemies.map { HitResolver.Candidate(it.id, it.pose!!) }, lobby.settings.maxRangeM,
        vm.withDepth(visionNow()), lobby.arena.epoch, state.walls,
    )
    val lockedId = preview?.hit?.targetId
    val blockedPreview = preview?.blockedId != null
    val personRect = detection?.takeIf { SystemClock.elapsedRealtime() - it.atMs < VisionGeometry.MAX_AGE_MS }?.person
        ?.let { VisionGeometry.toView(it, viewSize.width.toFloat(), viewSize.height.toFloat()) }

    val effects = remember { ArEffects() }
    RunArFrameClock(effects)
    val density = LocalDensity.current.density

    // Turn game events into visual effects. The shooter's bearing feeds the damage-direction arrow.
    val currentState by rememberUpdatedState(state)
    val currentPose by rememberUpdatedState(myPose)
    LaunchedEffect(Unit) {
        vm.events.collect { e ->
            val shooterBearing = (e as? GameEvent.HitTaken)?.let { hit ->
                val from = currentPose ?: return@let null
                val at = currentState.snapshot?.players?.firstOrNull { it.id == hit.shooterId }?.pose ?: return@let null
                Geo.bearingDeg(from.lat, from.lon, at.lat, at.lon)
            }
            effects.onEvent(e, shooterBearing, density)
        }
    }
    LaunchedEffect(lockedId) { if (lockedId != null) vm.onLockAcquired() }
    LaunchedEffect(lowHealth) {
        while (lowHealth) {
            vm.playHeartbeat()
            effects.onHeartbeat()
            delay(1100)
        }
    }

    val laserColor = if (teams) myTeam.color() else Color(0xFFFF2D55)

    Box(
        Modifier
            .fillMaxSize()
            .background(Color.Black)
            // Screen shake when hit; only the layer is re-executed per frame.
            .graphicsLayer {
                val o = effects.shakeOffset(density)
                translationX = o.x
                translationY = o.y
            },
    ) {
        if (cameraGranted) ArCameraView(vm.ar, Modifier.fillMaxSize())

        ArOverlay(
            effects = effects,
            players = others.map {
                ArPlayer(it, state.nameOf(it.id), state.teamOf(it.id), teams && state.teamOf(it.id) == myTeam, talking = it.id in state.talking)
            },
            myPose = myPose,
            fov = fov,
            maxRangeM = lobby.settings.maxRangeM,
            lockedId = lockedId,
            laserColor = laserColor,
            lowHealth = lowHealth,
            dead = dead,
            modifier = Modifier.fillMaxSize().onSizeChanged { viewSize = it },
            arView = { vm.ar.view.value },
            myArena = { vm.ar.arenaPose },
            person = personRect,
            walls = state.walls,
            blocked = blockedPreview,
        )

        Column(Modifier.fillMaxSize().safeDrawingPadding().padding(12.dp)) {
            // ---- top HUD
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    val health = me?.health ?: Lobby.MAX_HEALTH
                    Text("HP $health", color = Color.White, fontWeight = FontWeight.Bold)
                    LinearProgressIndicator(
                        progress = { health / Lobby.MAX_HEALTH.toFloat() },
                        color = if (health > 25) Color(0xFF4CAF50) else RedTeam,
                        modifier = Modifier.fillMaxWidth(0.7f).height(8.dp),
                    )
                }
                Column(horizontalAlignment = Alignment.End) {
                    val left = (state.snapshot?.timeLeftMs ?: 0) / 1000
                    Text("%d:%02d".format(left / 60, left % 60), color = Color.White, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                    Text("Tags ${me?.score ?: 0} · Outs ${me?.deaths ?: 0}", color = Color.White)
                    if ((me?.randoms ?: 0) > 0) Text("Randoms ${me?.randoms}", color = RandomOrange, fontSize = 13.sp)
                }
            }
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Chip("${aim.azimuthDeg.toInt()}° ${compassPoint(aim.azimuthDeg)}", Color.White)
                Spacer(Modifier.width(8.dp))
                val acc = location?.accuracy?.toInt()
                Chip(if (acc == null) "No GPS" else "GPS ±${acc} m", if (acc == null || acc > 10) Color(0xFFFFC107) else Color.White)
                if (teams) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(20.dp).background(myTeam.color(), CircleShape))
                }
                Spacer(Modifier.width(8.dp))
                when {
                    sync.synced -> Chip("AR synced", Color(0xFF69F0AE))
                    !sync.tracking -> Chip("AR: move slowly", Color(0xFFFFC107))
                    else -> Chip("AR: not synced", Color(0xFFFFC107))
                }
                Spacer(Modifier.weight(1f))
                TorchButton(state) { vm.cycleTorchMode() }
                MicButton(state, vm::toggleMic)
                TextButton(onClick = vm::toggleSound) { Text(if (state.soundOn) "Sound on" else "Muted", color = Color.White) }
            }
            if (!aim.compassReliable) {
                Text("Compass needs calibration: wave the phone in a figure 8", color = Color(0xFFFFC107), fontSize = 13.sp)
            }
            state.error?.let { Text(it, color = Color(0xFFFFC107), fontSize = 13.sp) }
            if (!sync.synced && !dead) {
                // Fairness rule: only tracked phones can fire. Explain how to get back in.
                Text(
                    if (!sync.tracking) "Tracking lost: move the phone slowly to recover. Your shots don't count until then."
                    else "Not synced: scan a synced player's marker (they tap \"Share sync\"). Your shots don't count until then.",
                    color = Color(0xFFFFC107), fontSize = 13.sp,
                    modifier = Modifier.background(Color(0x99000000), RoundedCornerShape(6.dp)).padding(6.dp),
                )
            }
            RadioStatus(state)
            state.feed.forEach { Text(it.text, color = if (it.highlight) RandomOrange else Color.White, fontSize = 13.sp, fontWeight = if (it.highlight) FontWeight.Bold else null) }

            Spacer(Modifier.weight(1f))

            // ---- bottom HUD
            Row(verticalAlignment = Alignment.Bottom) {
                Radar(state, myPose, lobby.settings.maxRangeM, effects, Modifier.size(170.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FireButton(enabled = !dead && location != null && sync.synced, color = laserColor, onFire = { vm.fire(vm.withDepth(visionNow())) })
                    Row {
                        // Synced players can help others re-sync mid-match by showing their marker.
                        if (sync.synced) TextButton(onClick = vm::toggleMarker) {
                            Text(if (state.showingMarker) "Hide marker" else "Share sync", color = Color.White)
                        }
                        TextButton(onClick = vm::leave) { Text("Quit", color = Color.White) }
                    }
                }
            }
        }

        if (state.showingMarker && sync.synced) {
            MarkerCard(slot = SyncMarkers.slotFor(state.myId), steady = sync.steady, modifier = Modifier.align(Alignment.Center))
        }

        if (me != null && dead) {
            Column(
                Modifier.fillMaxSize().background(Color(0x99000000)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("TAGGED OUT", color = RedTeam, fontSize = 40.sp, fontWeight = FontWeight.Black)
                Text("Back in ${(me.respawnInMs + 999) / 1000}s", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

@Composable
private fun Chip(text: String, color: Color) = Text(
    text,
    color = color,
    modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
)

private fun compassPoint(deg: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

@Composable
private fun FireButton(enabled: Boolean, color: Color, onFire: () -> Unit) {
    val press = remember { Animatable(1f) }
    val scope = rememberCoroutineScope()
    Box(
        Modifier
            .size(110.dp)
            .graphicsLayer { scaleX = press.value; scaleY = press.value }
            .background(
                if (enabled) Brush.radialGradient(listOf(Color.White.copy(alpha = 0.5f), color, color.copy(alpha = 0.8f)))
                else Brush.linearGradient(listOf(Color.DarkGray, Color.DarkGray)),
                CircleShape,
            )
            .border(4.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            // onPress instead of onTap so shots fire on touch-down without waiting for release.
            .pointerInput(enabled) {
                detectTapGestures(onPress = {
                    if (enabled) {
                        onFire()
                        scope.launch {
                            press.snapTo(0.86f)
                            press.animateTo(1f, spring(dampingRatio = 0.35f, stiffness = 900f))
                        }
                    }
                })
            },
        contentAlignment = Alignment.Center,
    ) {
        Text("FIRE", color = Color.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
    }
}

/**
 * Heading-up radar of every tracked player. Up is where the camera points; the outer ring is
 * the weapon's max range. Players use their team colour (red in free-for-all), tagged-out players grey.
 */
@Composable
private fun Radar(state: UiState, myPose: Pose?, rangeM: Double, effects: ArEffects, modifier: Modifier) {
    val players = state.snapshot?.players.orEmpty()
    val teams = state.lobby?.settings?.mode == GameMode.TEAMS
    Canvas(modifier.background(Color(0x99000000), CircleShape)) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Color(0x5539FF14), r, c, style = Stroke(1.dp.toPx()))
        drawCircle(Color(0x3339FF14), r / 2, c, style = Stroke(1.dp.toPx()))
        // Voice ring: players inside it hear you directly, so the radio is muted for them.
        val voiceR = (VoiceRouter.RANGE_M / rangeM).toFloat().coerceAtMost(1f) * r
        drawCircle(
            Color(0x88FFD54F), voiceR, c,
            style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))),
        )
        // Rotating sweep with a fading trail.
        val sweep = (effects.t * 120 % 360).toFloat()
        rotate(sweep, c) {
            drawArc(
                Brush.sweepGradient(0.88f to Color.Transparent, 1f to Color(0x6639FF14), center = c),
                startAngle = 0f, sweepAngle = 360f, useCenter = true,
                topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
            )
        }
        // Line of fire.
        drawLine(Color(0x5539FF14), c, Offset(c.x, c.y - r), 1.dp.toPx())
        drawCircle(Color.White, 4.dp.toPx(), c)
        if (myPose == null) return@Canvas
        val mine = myPose.arena
        for (p in players) {
            if (p.id == state.myId) continue
            val pose = p.pose ?: continue
            val theirs = pose.arena
            val (d, rel) = if (mine != null && theirs != null && mine.epoch == theirs.epoch) {
                // Both tracked in the arena: exact relative position, heading-up.
                val f = Vec3(mine.forward.x, 0.0, mine.forward.z).normalized()
                val right = Vec3(-f.z, 0.0, f.x)
                val v = theirs.position - mine.position
                val flat = Vec3(v.x, 0.0, v.z)
                flat.length() to atan2(flat dot right, flat dot f)
            } else {
                Geo.distanceM(myPose.lat, myPose.lon, pose.lat, pose.lon) to
                    Math.toRadians(Geo.signedAngleDeg(myPose.azimuthDeg, Geo.bearingDeg(myPose.lat, myPose.lon, pose.lat, pose.lon)))
            }
            val dist = min(d / rangeM, 1.0) * r
            val pos = Offset(c.x + (sin(rel) * dist).toFloat(), c.y - (cos(rel) * dist).toFloat())
            val color = when {
                !p.alive -> Color.Gray
                teams -> state.teamOf(p.id).color()
                else -> RedTeam
            }
            drawCircle(color, if (d > rangeM) 3.dp.toPx() else 6.dp.toPx(), pos)
        }
    }
}

/** Mic switch for proximity voice chat; highlighted while we're transmitting. */
@Composable
fun MicButton(state: UiState, onClick: () -> Unit) {
    val label = when {
        !state.micAllowed -> "Mic: no permission"
        !state.micOn -> "Mic off"
        state.meTalking -> "Mic: on air"
        else -> "Mic on"
    }
    TextButton(onClick = onClick, enabled = state.micAllowed) {
        Text(label, color = if (state.meTalking) Color(0xFF69F0AE) else Color.White)
    }
}

/**
 * Who you can talk to over the radio. Only players at least 10 m away are linked; closer ones
 * can hear you directly, and muting them avoids speaker-to-mic feedback.
 */
@Composable
fun RadioStatus(state: UiState) {
    val names = state.radioPartners.map { id -> (if (id in state.talking) "🔊 " else "") + state.nameOf(id) }
    Text(
        if (names.isEmpty()) "Radio: nobody beyond ${VoiceRouter.RANGE_M.toInt()} m" else "Radio: ${names.joinToString()}",
        color = if (state.talking.isNotEmpty()) Color(0xFF69F0AE) else Color.LightGray,
        fontSize = 13.sp,
        modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
    )
}

/** Flashlight mode (Auto / On / Off), with whether it's lit right now. Each phone decides alone. */
@Composable
fun TorchButton(state: UiState, onClick: () -> Unit) {
    val mode = state.torchMode.name.lowercase().replaceFirstChar { it.uppercase() }
    TextButton(onClick = onClick) {
        Text("Light: $mode" + if (state.torchMode == TorchMode.AUTO && state.torchOn) " (on)" else "", color = if (state.torchOn) Color(0xFFFFE082) else Color.White)
    }
}
