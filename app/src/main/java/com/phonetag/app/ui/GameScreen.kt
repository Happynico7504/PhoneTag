package com.phonetag.app.ui

import android.hardware.camera2.CameraCharacteristics
import android.os.SystemClock
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.CameraSelector
import androidx.camera.core.ExperimentalGetImage
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
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
import androidx.compose.runtime.DisposableEffect
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
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonetag.app.GameEvent
import com.phonetag.app.ar.ArCameraView
import com.phonetag.app.ar.ArSupport
import com.phonetag.app.vision.PersonDetector
import com.phonetag.app.vision.VisionGeometry
import com.phonetag.app.GameViewModel
import com.phonetag.app.UiState
import com.phonetag.core.GameMode
import com.phonetag.core.Geo
import com.phonetag.core.HitResolver
import com.phonetag.core.Lobby
import com.phonetag.core.Pose
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import java.util.concurrent.Executors
import kotlin.math.atan
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

    // Camera tracking: ARCore when the phone supports it, otherwise CameraX + GPS/compass.
    val arSupport by vm.ar.support.collectAsStateWithLifecycle()
    val useAr = arSupport == ArSupport.SUPPORTED && cameraGranted
    val sync by vm.ar.sync.collectAsStateWithLifecycle()
    val detection by vm.personDetector.latest.collectAsStateWithLifecycle()
    var viewSize by remember { mutableStateOf(IntSize.Zero) }
    var fov by remember { mutableStateOf(CameraFov()) }

    // Recomposes with the compass (~50 Hz) anyway, so reading the latest arena pose here is fresh enough.
    val myPose = location?.let { Pose(it.latitude, it.longitude, it.accuracy.toDouble(), aim.azimuthDeg, aim.pitchDeg, vm.ar.arenaPose) }
    val others = state.snapshot?.players.orEmpty().filter { it.id != state.myId }
    val enemies = others.filter { p -> p.alive && p.pose != null && !(teams && state.teamOf(p.id) == myTeam) }

    fun focalPx() = vm.ar.view.value?.focalPx ?: fov.focalPx(viewSize.width.toFloat(), viewSize.height.toFloat())
    fun visionNow() = VisionGeometry.hint(
        vm.personDetector.latest.value, SystemClock.elapsedRealtime(), viewSize.width.toFloat(), viewSize.height.toFloat(), focalPx(),
    )
    // Client-side preview of the server's (fused) hit test: tells the player who is in the crosshair.
    val lockedId = if (myPose == null || dead) null else HitResolver.resolve(
        myPose, enemies.map { HitResolver.Candidate(it.id, it.pose!!) }, lobby.settings.maxRangeM,
        visionNow(), lobby.arena.epoch,
    )?.targetId
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
        when {
            useAr -> ArCameraView(vm.ar, Modifier.fillMaxSize())
            cameraGranted -> CameraViewfinder(Modifier.fillMaxSize(), vm.personDetector, onFov = { fov = it })
        }

        ArOverlay(
            effects = effects,
            players = others.map { ArPlayer(it, state.nameOf(it.id), state.teamOf(it.id), teams && state.teamOf(it.id) == myTeam) },
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
                    useAr && !sync.tracking -> Chip("AR: move slowly", Color(0xFFFFC107))
                    useAr -> Chip("AR: not synced", Color(0xFFFFC107))
                }
                Spacer(Modifier.weight(1f))
                TextButton(onClick = vm::toggleSound) { Text(if (state.soundOn) "Sound on" else "Muted", color = Color.White) }
            }
            if (!aim.compassReliable) {
                Text("Compass needs calibration: wave the phone in a figure 8", color = Color(0xFFFFC107), fontSize = 13.sp)
            }
            state.error?.let { Text(it, color = Color(0xFFFFC107), fontSize = 13.sp) }
            state.feed.forEach { Text(it.text, color = Color.White, fontSize = 13.sp) }

            Spacer(Modifier.weight(1f))

            // ---- bottom HUD
            Row(verticalAlignment = Alignment.Bottom) {
                Radar(state, myPose, lobby.settings.maxRangeM, effects, Modifier.size(170.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FireButton(enabled = !dead && location != null, color = laserColor, onFire = { vm.fire(visionNow()) })
                    TextButton(onClick = vm::leave) { Text("Quit", color = Color.White) }
                }
            }
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
        for (p in players) {
            if (p.id == state.myId) continue
            val pose = p.pose ?: continue
            val d = Geo.distanceM(myPose.lat, myPose.lon, pose.lat, pose.lon)
            val rel = Math.toRadians(Geo.signedAngleDeg(myPose.azimuthDeg, Geo.bearingDeg(myPose.lat, myPose.lon, pose.lat, pose.lon)))
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

/** CameraX viewfinder for phones without ARCore; also feeds frames to person detection. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class, ExperimentalGetImage::class)
@Composable
private fun CameraViewfinder(modifier: Modifier, detector: PersonDetector, onFov: (CameraFov) -> Unit) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val analysisExecutor = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            val analysis = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .build()
                .also { a ->
                    a.setAnalyzer(analysisExecutor) { proxy ->
                        val image = proxy.image
                        if (image == null) proxy.close() else detector.process(image, proxy.imageInfo.rotationDegrees) { proxy.close() }
                    }
                }
            runCatching {
                provider.unbindAll()
                val camera = provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                cameraFov(Camera2CameraInfo.from(camera.cameraInfo))?.let(onFov)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose {
            runCatching { future.get().unbindAll() }
            analysisExecutor.shutdown()
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}

/** Field of view from the lens focal length and sensor size, so AR markers line up with the image. */
@androidx.annotation.OptIn(ExperimentalCamera2Interop::class)
private fun cameraFov(info: Camera2CameraInfo): CameraFov? {
    val sensor = info.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE) ?: return null
    val focal = info.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS)?.minOrNull() ?: return null
    if (focal <= 0f) return null
    fun fov(side: Float) = Math.toDegrees(2 * atan(side / (2.0 * focal)))
    return CameraFov(longDeg = fov(max(sensor.width, sensor.height)), shortDeg = fov(min(sensor.width, sensor.height)))
}
