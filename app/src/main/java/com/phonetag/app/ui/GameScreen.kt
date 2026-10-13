package com.phonetag.app.ui

import androidx.camera.core.CameraSelector
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.phonetag.app.GameViewModel
import com.phonetag.app.UiState
import com.phonetag.core.GameMode
import com.phonetag.core.Geo
import com.phonetag.core.HitResolver
import com.phonetag.core.Lobby
import com.phonetag.core.Pose
import kotlin.math.cos
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

    val myPose = location?.let { Pose(it.latitude, it.longitude, it.accuracy.toDouble(), aim.azimuthDeg) }
    val enemies = state.snapshot?.players.orEmpty().filter { p ->
        p.id != state.myId && p.alive && p.pose != null && !(teams && state.teamOf(p.id) == myTeam)
    }
    // Client-side preview of the server's hit test: tells the player when someone is in the crosshair.
    val lockedOn = myPose != null && HitResolver.resolve(
        myPose, enemies.map { HitResolver.Candidate(it.id, it.pose!!) }, lobby.settings.maxRangeM,
    ) != null

    val hitFlash = remember { Animatable(0f) }
    LaunchedEffect(state.hitTakenCount) {
        if (state.hitTakenCount > 0) {
            hitFlash.snapTo(0.55f)
            hitFlash.animateTo(0f, tween(450))
        }
    }
    val dealtFlash = remember { Animatable(0f) }
    LaunchedEffect(state.hitDealtCount) {
        if (state.hitDealtCount > 0) {
            dealtFlash.snapTo(1f)
            dealtFlash.animateTo(0f, tween(400))
        }
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        if (cameraGranted) CameraViewfinder(Modifier.fillMaxSize())

        Crosshair(locked = lockedOn, confirm = dealtFlash.value, modifier = Modifier.align(Alignment.Center).size(120.dp))

        Box(Modifier.fillMaxSize().alpha(hitFlash.value).background(Color.Red))

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
            Row {
                Text(
                    "${aim.azimuthDeg.toInt()}° ${compassPoint(aim.azimuthDeg)}",
                    color = Color.White,
                    modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                Spacer(Modifier.width(8.dp))
                val acc = location?.accuracy?.toInt()
                Text(
                    if (acc == null) "No GPS" else "GPS ±${acc} m",
                    color = if (acc == null || acc > 10) Color(0xFFFFC107) else Color.White,
                    modifier = Modifier.background(Color(0x66000000), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 2.dp),
                )
                if (teams) {
                    Spacer(Modifier.width(8.dp))
                    Box(Modifier.size(20.dp).background(myTeam.color(), CircleShape))
                }
            }
            if (!aim.compassReliable) {
                Text("Compass needs calibration: wave the phone in a figure 8", color = Color(0xFFFFC107), fontSize = 13.sp)
            }
            state.error?.let { Text(it, color = Color(0xFFFFC107), fontSize = 13.sp) }
            state.feed.forEach { Text(it.text, color = Color.White, fontSize = 13.sp) }

            Spacer(Modifier.weight(1f))

            // ---- bottom HUD
            Row(verticalAlignment = Alignment.Bottom) {
                Radar(state, myPose, lobby.settings.maxRangeM, Modifier.size(170.dp))
                Spacer(Modifier.weight(1f))
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    FireButton(enabled = me?.alive != false && location != null, onFire = vm::fire)
                    TextButton(onClick = vm::leave) { Text("Quit", color = Color.White) }
                }
            }
        }

        if (me != null && !me.alive) {
            Column(
                Modifier.fillMaxSize().background(Color(0xCC000000)),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text("TAGGED OUT", color = RedTeam, fontSize = 40.sp, fontWeight = FontWeight.Black)
                Text("Back in ${(me.respawnInMs + 999) / 1000}s", color = Color.White, fontSize = 20.sp)
            }
        }
    }
}

private fun compassPoint(deg: Double) = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")[(((deg + 22.5) % 360) / 45).toInt()]

@Composable
private fun Crosshair(locked: Boolean, confirm: Float, modifier: Modifier) {
    val color = if (locked) RedTeam else Color.White
    Canvas(modifier) {
        val c = center
        val r = size.minDimension / 2 * 0.6f
        val stroke = 3.dp.toPx()
        drawCircle(color, r, c, style = Stroke(stroke))
        val gap = r * 0.35f
        val len = r * 0.9f
        drawLine(color, Offset(c.x, c.y - gap), Offset(c.x, c.y - len - gap), stroke, StrokeCap.Round)
        drawLine(color, Offset(c.x, c.y + gap), Offset(c.x, c.y + len + gap), stroke, StrokeCap.Round)
        drawLine(color, Offset(c.x - gap, c.y), Offset(c.x - len - gap, c.y), stroke, StrokeCap.Round)
        drawLine(color, Offset(c.x + gap, c.y), Offset(c.x + len + gap, c.y), stroke, StrokeCap.Round)
        drawCircle(color, 3.dp.toPx(), c)
        if (confirm > 0f) {
            // Hit marker: an X that fades out.
            val k = r * 0.5f
            val hc = Color.White.copy(alpha = confirm)
            drawLine(hc, Offset(c.x - k, c.y - k), Offset(c.x + k, c.y + k), stroke * 1.5f, StrokeCap.Round)
            drawLine(hc, Offset(c.x - k, c.y + k), Offset(c.x + k, c.y - k), stroke * 1.5f, StrokeCap.Round)
        }
    }
}

@Composable
private fun FireButton(enabled: Boolean, onFire: () -> Unit) {
    Box(
        Modifier
            .size(110.dp)
            .background(if (enabled) RedTeam else Color.DarkGray, CircleShape)
            .border(4.dp, Color.White.copy(alpha = 0.7f), CircleShape)
            // onPress instead of onTap so shots fire on touch-down without waiting for release.
            .pointerInput(enabled) { detectTapGestures(onPress = { if (enabled) onFire() }) },
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
private fun Radar(state: UiState, myPose: Pose?, rangeM: Double, modifier: Modifier) {
    val players = state.snapshot?.players.orEmpty()
    val teams = state.lobby?.settings?.mode == GameMode.TEAMS
    Canvas(modifier.background(Color(0x99000000), CircleShape)) {
        val c = center
        val r = size.minDimension / 2
        drawCircle(Color(0x5539FF14), r, c, style = Stroke(1.dp.toPx()))
        drawCircle(Color(0x3339FF14), r / 2, c, style = Stroke(1.dp.toPx()))
        // Field of fire wedge.
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

@Composable
private fun CameraViewfinder(modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val previewView = remember { PreviewView(context).apply { scaleType = PreviewView.ScaleType.FILL_CENTER } }
    DisposableEffect(lifecycleOwner) {
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener({
            val provider = future.get()
            val preview = Preview.Builder().build().also { it.surfaceProvider = previewView.surfaceProvider }
            runCatching {
                provider.unbindAll()
                provider.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview)
            }
        }, ContextCompat.getMainExecutor(context))
        onDispose { runCatching { future.get().unbindAll() } }
    }
    AndroidView(factory = { previewView }, modifier = modifier)
}
