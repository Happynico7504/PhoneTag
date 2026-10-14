package com.phonetag.app.ui

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.phonetag.app.GameEvent
import com.phonetag.app.ar.ArView
import com.phonetag.app.ar.project
import com.phonetag.app.ar.relativeAngleDeg
import com.phonetag.app.vision.ViewRect
import com.phonetag.core.ArenaPose
import com.phonetag.core.Geo
import com.phonetag.core.Lobby
import com.phonetag.core.PlayerSnapshot
import com.phonetag.core.Pose
import com.phonetag.core.Team
import com.phonetag.core.Vec3
import com.phonetag.core.Wall
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.tan
import kotlin.random.Random

/** Field of view of the back camera, in degrees, along the sensor's long and short sides. */
data class CameraFov(val longDeg: Double = 66.0, val shortDeg: Double = 52.0)

/** Everything the AR layer needs to know about another player for one frame. */
data class ArPlayer(val snap: PlayerSnapshot, val name: String, val team: Team, val friendly: Boolean, val talking: Boolean = false)

private const val LASER_SECS = 0.16
private const val MUZZLE_SECS = 0.09
private const val HIT_MARK_SECS = 0.32
private const val DAMAGE_SECS = 0.6
private const val INDICATOR_SECS = 1.6
private const val BANNER_SECS = 1.4
private const val SHAKE_SECS = 0.28

/**
 * Mutable state for the AR view's effects. A frame loop ([RunArFrameClock]) advances it and
 * bumps [frameNanos]; reading that in a draw lambda redraws every frame without recomposing.
 */
@Stable
class ArEffects {
    var frameNanos by mutableLongStateOf(0L)
        private set

    private class Particle(
        var x: Float, var y: Float, var vx: Float, var vy: Float,
        val born: Double, val life: Double, val color: Color, val radius: Float,
    )
    private class Indicator(val bearingDeg: Double, val born: Double)
    private class Smoothed(var bearingDeg: Double, var distanceM: Double)
    private val smoothedArena = HashMap<Int, Vec3>()
    private class Banner(val text: String, val color: Color, val born: Double)

    private val particles = ArrayList<Particle>()
    private val indicators = ArrayList<Indicator>()
    private val smoothed = HashMap<Int, Smoothed>()
    private val rnd = Random(System.nanoTime())
    private var lastFrame = 0.0
    private var laserAt = -1e9
    private var hitMarkAt = -1e9
    private var hitMarkKill = false
    private var damageAt = -1e9
    private var shakeAt = -1e9
    private var shieldUntil = -1e9
    private var heartbeatAt = -1e9
    private var blockedAt = -1e9
    private var randomAt = -1e9

    /** 1 right after we shot a bystander, fading to 0 over a second (tints their detection box). */
    fun randomFlash(): Float = (1 - (t - randomAt) / 1.0).toFloat().coerceIn(0f, 1f)
    private var banner: Banner? = null

    private fun now() = System.nanoTime() / 1e9

    /** Seconds on the same clock as Choreographer frame times. */
    val t get() = frameNanos / 1e9

    fun step(frameTimeNanos: Long) {
        val tNow = frameTimeNanos / 1e9
        val dt = (tNow - lastFrame).coerceIn(0.0, 0.05).toFloat()
        lastFrame = tNow
        val it = particles.iterator()
        while (it.hasNext()) {
            val p = it.next()
            if (tNow - p.born > p.life) { it.remove(); continue }
            p.vy += 1400f * dt // gravity, px/s²
            p.vx *= 0.985f
            p.x += p.vx * dt
            p.y += p.vy * dt
        }
        indicators.removeAll { tNow - it.born > INDICATOR_SECS }
        frameNanos = frameTimeNanos
    }

    /** Smooths GPS jitter so AR markers glide instead of jumping 5 times a second. */
    fun smoothedPosition(id: Int, bearingDeg: Double, distanceM: Double): Pair<Double, Double> {
        val s = smoothed.getOrPut(id) { Smoothed(bearingDeg, distanceM) }
        s.bearingDeg = Geo.normalizeDeg(s.bearingDeg + Geo.signedAngleDeg(s.bearingDeg, bearingDeg) * 0.12)
        s.distanceM += (distanceM - s.distanceM) * 0.12
        return s.bearingDeg to s.distanceM
    }

    /** Arena positions are already precise; light smoothing just hides the 5 Hz update steps. */
    fun smoothedArenaPosition(id: Int, target: Vec3): Vec3 {
        val cur = smoothedArena[id]
        val next = if (cur == null || (cur - target).length() > 5.0) target else cur + (target - cur) * 0.25
        smoothedArena[id] = next
        return next
    }

    fun onEvent(e: GameEvent, shooterBearingDeg: Double?, density: Float) {
        val n = now()
        when (e) {
            GameEvent.Fired -> laserAt = n
            is GameEvent.HitDealt -> {
                hitMarkAt = n
                hitMarkKill = e.eliminated
                burst(if (e.eliminated) 46 else 18, density, n, kill = e.eliminated)
                if (e.eliminated) banner = Banner("+1 TAG", Color(0xFFFFD54F), n)
            }
            is GameEvent.HitTaken -> {
                damageAt = n
                shakeAt = n
                shooterBearingDeg?.let { indicators += Indicator(it, n) }
            }
            GameEvent.Respawned -> {
                shieldUntil = n + Lobby.SPAWN_PROTECTION_MS / 1000.0
                banner = Banner("BACK IN THE GAME", BlueTeam, n)
            }
            GameEvent.MatchStarted -> {
                shieldUntil = n + Lobby.SPAWN_PROTECTION_MS / 1000.0
                banner = Banner("FIGHT!", RedTeam, n)
            }
            GameEvent.ShotBlocked -> {
                blockedAt = n
                dust(density, n)
            }
            GameEvent.ShotUntracked -> banner = Banner("NOT TRACKING", Color(0xFFFFC107), n)
            is GameEvent.ShotRandom -> {
                randomAt = n
                banner = Banner("RANDOM! ×${e.total}", RandomOrange, n)
            }
        }
    }

    fun onHeartbeat() { heartbeatAt = now() }

    /** Grey debris puff where a shot hit cover. */
    private fun dust(density: Float, born: Double) {
        repeat(14) {
            val angle = rnd.nextDouble(0.0, 2 * PI)
            val speed = rnd.nextDouble(80.0, 260.0).toFloat() * density / 2.5f
            particles += Particle(
                x = 0f, y = 0f,
                vx = cos(angle).toFloat() * speed, vy = sin(angle).toFloat() * speed - 200f * density / 2.5f,
                born = born, life = rnd.nextDouble(0.3, 0.6),
                color = listOf(Color(0xFF9E9E9E), Color(0xFFBDBDBD), Color(0xFF757575))[rnd.nextInt(3)],
                radius = rnd.nextDouble(2.0, 4.0).toFloat() * density,
            )
        }
    }

    /** Sparks flying out of the crosshair. Positions are relative to the screen centre. */
    private fun burst(count: Int, density: Float, born: Double, kill: Boolean) {
        val palette = if (kill) listOf(Color.White, Color(0xFFFFD54F), RedTeam, Color(0xFFFF8A65))
        else listOf(Color.White, Color(0xFFFFD54F), Color(0xFFFFA726))
        repeat(count) {
            val angle = rnd.nextDouble(0.0, 2 * PI)
            val speed = rnd.nextDouble(220.0, if (kill) 900.0 else 600.0).toFloat() * density / 2.5f
            particles += Particle(
                x = 0f, y = 0f,
                vx = cos(angle).toFloat() * speed, vy = sin(angle).toFloat() * speed - 120f * density / 2.5f,
                born = born, life = rnd.nextDouble(0.35, 0.75),
                color = palette[rnd.nextInt(palette.size)],
                radius = rnd.nextDouble(1.5, 3.5).toFloat() * density,
            )
        }
    }

    // ------------------------------------------------------------------ drawing helpers (read-only)

    fun shakeOffset(density: Float): Offset {
        val age = t - shakeAt
        if (age !in 0.0..SHAKE_SECS) return Offset.Zero
        val amp = (1 - age / SHAKE_SECS).toFloat() * 9f * density
        return Offset(sin(age * 90).toFloat() * amp, cos(age * 73).toFloat() * amp * 0.6f)
    }

    internal fun DrawScope.drawEffects(
        text: TextMeasurer,
        laserColor: Color,
        lowHealth: Boolean,
        dead: Boolean,
        locked: Boolean,
        myAzimuthDeg: Double,
        blocked: Boolean = false,
    ) {
        val time = t
        val c = center
        val dp = density

        // Spawn shield: shimmering blue frame while protected.
        if (time < shieldUntil) {
            val pulse = 0.5f + 0.5f * sin(time * 10).toFloat()
            drawRect(
                Brush.radialGradient(
                    0.4f to Color.Transparent, 1f to BlueTeam.copy(alpha = 0.45f + 0.25f * pulse),
                    center = c, radius = size.maxDimension * 0.6f,
                ),
            )
            drawRect(BlueTeam.copy(alpha = 0.6f + 0.4f * pulse), style = Stroke(8 * dp))
        }

        // Low-health heartbeat vignette.
        if (lowHealth && !dead) {
            val beat = (time - heartbeatAt).let { if (it in 0.0..0.5) (1 - it / 0.5).toFloat() else 0f }
            vignette(Color.Red, 0.25f + 0.35f * beat)
        }

        // Damage taken: red vignette + arrow towards the shooter.
        val dmgAge = time - damageAt
        if (dmgAge in 0.0..DAMAGE_SECS) vignette(Color.Red, 0.75f * (1 - dmgAge / DAMAGE_SECS).toFloat())
        for (ind in indicators) {
            val a = (1 - (time - ind.born) / INDICATOR_SECS).toFloat().coerceIn(0f, 1f)
            val rel = Geo.signedAngleDeg(myAzimuthDeg, ind.bearingDeg).toFloat()
            rotate(rel, c) {
                val r = size.minDimension * 0.36f
                drawArc(
                    color = Color.Red.copy(alpha = 0.85f * a),
                    startAngle = -90f - 22f, sweepAngle = 44f, useCenter = false,
                    topLeft = Offset(c.x - r, c.y - r), size = Size(2 * r, 2 * r),
                    style = Stroke(10 * dp, cap = StrokeCap.Round),
                )
                val tip = Path().apply {
                    moveTo(c.x, c.y - r - 26 * dp)
                    lineTo(c.x - 12 * dp, c.y - r - 8 * dp)
                    lineTo(c.x + 12 * dp, c.y - r - 8 * dp)
                    close()
                }
                drawPath(tip, Color.Red.copy(alpha = 0.85f * a))
            }
        }

        // Laser: beam from the "gun" (bottom right) into the crosshair, plus muzzle flash.
        val laserAge = time - laserAt
        if (laserAge in 0.0..LASER_SECS) {
            val k = (1 - laserAge / LASER_SECS).toFloat()
            val origin = Offset(size.width * 0.80f, size.height + 10 * dp)
            // The beam's far end slides into the crosshair as it fades, which reads as travel.
            val reach = 0.75f + 0.25f * (1 - k)
            val end = Offset(origin.x + (c.x - origin.x) * reach, origin.y + (c.y - origin.y) * reach)
            val w = 0.5f + 0.5f * k
            drawLine(laserColor.copy(alpha = 0.25f * k), origin, end, 34 * dp * w, StrokeCap.Round)
            drawLine(laserColor.copy(alpha = 0.7f * k), origin, end, 14 * dp * w, StrokeCap.Round)
            drawLine(Color.White.copy(alpha = 0.95f * k), origin, end, 5 * dp * w, StrokeCap.Round)
            drawCircle(Brush.radialGradient(listOf(Color.White.copy(alpha = 0.9f * k), laserColor.copy(alpha = 0.5f * k), Color.Transparent), center = end, radius = 22 * dp), 22 * dp, end)
        }
        if (laserAge in 0.0..MUZZLE_SECS) {
            val k = (1 - laserAge / MUZZLE_SECS).toFloat()
            val origin = Offset(size.width * 0.80f, size.height)
            drawCircle(
                Brush.radialGradient(listOf(Color.White.copy(alpha = k), laserColor.copy(alpha = 0.7f * k), Color.Transparent), center = origin, radius = 90 * dp),
                90 * dp, origin,
            )
        }

        drawCrosshair(locked, blocked, time)

        // "Blocked" feedback: label under the crosshair when a shot hit cover.
        val blockedAge = time - blockedAt
        if (blockedAge in 0.0..0.9) {
            val a = (1 - blockedAge / 0.9).toFloat()
            val layout = text.measure("BLOCKED", TextStyle(color = Color(0xFFBDBDBD).copy(alpha = a), fontSize = 16.sp, fontWeight = FontWeight.Black))
            drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y + 56 * dp))
        }

        // Hit marker + elimination shockwave.
        val hitAge = time - hitMarkAt
        if (hitAge in 0.0..HIT_MARK_SECS) {
            val k = (1 - hitAge / HIT_MARK_SECS).toFloat()
            val s = (if (hitMarkKill) 30 else 20) * dp * (1.2f - 0.2f * k)
            val col = (if (hitMarkKill) RedTeam else Color.White).copy(alpha = k)
            for (sx in listOf(-1, 1)) for (sy in listOf(-1, 1)) {
                drawLine(col, Offset(c.x + sx * s * 0.4f, c.y + sy * s * 0.4f), Offset(c.x + sx * s, c.y + sy * s), 4 * dp, StrokeCap.Round)
            }
        }
        if (hitMarkKill && hitAge in 0.0..0.5) {
            val k = (hitAge / 0.5).toFloat()
            drawCircle(Color(0xFFFFD54F).copy(alpha = 1 - k), 20 * dp + 180 * dp * k, c, style = Stroke(6 * dp * (1 - k) + 1))
        }
        for (p in particles) {
            val a = (1 - (time - p.born) / p.life).toFloat().coerceIn(0f, 1f)
            val pos = Offset(c.x + p.x, c.y + p.y)
            // Streak along velocity for a sparkier look.
            drawLine(p.color.copy(alpha = a), pos, Offset(pos.x - p.vx * 0.025f, pos.y - p.vy * 0.025f), p.radius * 2, StrokeCap.Round)
        }

        // Centre banner ("FIGHT!", "+1 TAG", …): pops in, then fades.
        banner?.let { b ->
            val age = time - b.born
            if (age > BANNER_SECS) { banner = null; return@let }
            val pop = if (age < 0.15) (age / 0.15).toFloat() else 1f
            val a = if (age > BANNER_SECS - 0.4) ((BANNER_SECS - age) / 0.4).toFloat() else 1f
            val layout = text.measure(b.text, TextStyle(color = b.color.copy(alpha = a), fontSize = 40.sp, fontWeight = FontWeight.Black))
            withTransform({ scale(0.6f + 0.4f * pop + 0.2f * (1 - pop), 0.6f + 0.4f * pop, Offset(c.x, c.y * 0.6f)) }) {
                drawText(layout, topLeft = Offset(c.x - layout.size.width / 2f, c.y * 0.6f - layout.size.height / 2f))
            }
        }

        if (dead) drawGlitch(time)
    }

    private fun DrawScope.vignette(color: Color, alpha: Float) {
        drawRect(
            Brush.radialGradient(
                0.45f to Color.Transparent, 1f to color.copy(alpha = alpha.coerceIn(0f, 1f)),
                center = center, radius = size.maxDimension * 0.7f,
            )
        )
    }

    private fun DrawScope.drawCrosshair(locked: Boolean, blocked: Boolean, time: Double) {
        val c = center
        val dp = density
        val color = when {
            locked -> RedTeam
            blocked -> Color(0xFF9E9E9E)
            else -> Color.White
        }
        val r = 34 * dp * (if (locked) 1f + 0.06f * sin(time * 14).toFloat() else 1f)
        drawCircle(Color.Black.copy(alpha = 0.35f), r, c, style = Stroke(5 * dp))
        drawCircle(color, r, c, style = Stroke(2.5f * dp))
        val gap = r * 0.45f
        val len = r * 0.75f
        for ((dx, dy) in listOf(0f to -1f, 0f to 1f, -1f to 0f, 1f to 0f)) {
            drawLine(color, Offset(c.x + dx * gap, c.y + dy * gap), Offset(c.x + dx * (gap + len), c.y + dy * (gap + len)), 2.5f * dp, StrokeCap.Round)
        }
        drawCircle(color, 2.5f * dp, c)
        if (blocked && !locked) {
            // Someone is lined up but cover is in the way: slash through the reticle.
            drawLine(color, Offset(c.x - r * 0.7f, c.y + r * 0.7f), Offset(c.x + r * 0.7f, c.y - r * 0.7f), 3 * dp, StrokeCap.Round)
        }
        if (locked) {
            // Spinning lock-on ticks.
            rotate((time * 180 % 360).toFloat(), c) {
                val rr = r + 12 * dp
                for (i in 0 until 4) {
                    drawArc(
                        RedTeam, startAngle = i * 90f + 15f, sweepAngle = 30f, useCenter = false,
                        topLeft = Offset(c.x - rr, c.y - rr), size = Size(2 * rr, 2 * rr), style = Stroke(3 * dp, cap = StrokeCap.Round),
                    )
                }
            }
        }
    }

    /** "Signal lost" look while tagged out: tinted scan bars that jitter every frame. */
    private fun DrawScope.drawGlitch(time: Double) {
        val g = Random((time * 12).toInt())
        repeat(7) {
            val y = g.nextFloat() * size.height
            val h = (4 + g.nextFloat() * 28) * density
            val shift = (g.nextFloat() - 0.5f) * 40 * density
            val col = if (g.nextBoolean()) Color(0x55FF1744) else Color(0x4400E5FF)
            drawRect(col, topLeft = Offset(shift, y), size = Size(size.width, h))
        }
        var y = 0f
        while (y < size.height) {
            drawLine(Color.Black.copy(alpha = 0.25f), Offset(0f, y), Offset(size.width, y), 1.5f * density)
            y += 4 * density
        }
    }
}

/** Drives [ArEffects] from the display's frame clock while this composable is on screen. */
@Composable
fun RunArFrameClock(effects: ArEffects) {
    LaunchedEffect(effects) {
        while (true) withFrameNanos { effects.step(it) }
    }
}

/**
 * The AR layer drawn over the camera: player markers projected into the viewfinder (with
 * off-screen arrows), the crosshair, and all combat effects.
 */
@Composable
fun ArOverlay(
    effects: ArEffects,
    players: List<ArPlayer>,
    myPose: Pose?,
    fov: CameraFov,
    maxRangeM: Double,
    lockedId: Int?,
    laserColor: Color,
    lowHealth: Boolean,
    dead: Boolean,
    modifier: Modifier = Modifier,
    /** ARCore camera geometry (read every frame); when we and a target share the arena, markers use true 3D positions. */
    arView: () -> ArView? = { null },
    /** Our own arena pose, read every frame alongside [arView] so both match. */
    myArena: () -> ArenaPose? = { null },
    /** Person found by on-device detection, in view pixels. */
    person: ViewRect? = null,
    /** Shared wall map (arena frame), drawn as translucent panels when AR-synced. */
    walls: List<Wall> = emptyList(),
    /** An enemy is lined up but an obstacle is in the way. */
    blocked: Boolean = false,
) {
    val text = rememberTextMeasurer()
    Canvas(modifier) {
        effects.frameNanos // subscribe to the frame clock
        if (person != null && !dead) drawPersonBox(person, effects.randomFlash(), text)
        if (myPose != null) {
            val arView = arView()
            val myPose = myPose.copy(arena = myArena())
            val focal = arView?.focalPx ?: fov.focalPx(size.width, size.height)
            // Other players are assumed to be at eye level when we only have GPS: the horizon line.
            val horizonY = (size.height / 2 + focal * tan(Math.toRadians(myPose.pitchDeg))).toFloat()
                .coerceIn(size.height * 0.15f, size.height * 0.7f)
            val margin = 24 * density
            fun onScreen(o: Offset) = o.x in margin..(size.width - margin) && o.y in 0f..size.height
            val myArena = myPose.arena?.takeIf { arView != null && it.epoch == arView.epoch }
            if (myArena != null && arView != null) drawWalls(walls, arView)
            val placed = players.mapNotNull { p ->
                val pose = p.snap.pose ?: return@mapNotNull null
                val theirArena = pose.arena
                if (myArena != null && arView != null && theirArena != null && theirArena.epoch == myArena.epoch) {
                    // Precise: project the tracked 3D position through the AR camera.
                    val pos = effects.smoothedArenaPosition(p.snap.id, theirArena.position)
                    val screen = arView.project(pos, size.width, size.height)?.let { (x, y) -> Offset(x, y) }
                    Placed(p, (pos - myArena.position).length(), screen?.takeIf(::onScreen), arView.relativeAngleDeg(pos), precise = true)
                } else {
                    val (bearing, dist) = effects.smoothedPosition(
                        p.snap.id,
                        Geo.bearingDeg(myPose.lat, myPose.lon, pose.lat, pose.lon),
                        Geo.distanceM(myPose.lat, myPose.lon, pose.lat, pose.lon),
                    )
                    val rel = Geo.signedAngleDeg(myPose.azimuthDeg, bearing)
                    val screen = if (abs(rel) < 85) Offset(size.width / 2 + focal * tan(Math.toRadians(rel)).toFloat(), horizonY) else null
                    Placed(p, dist, screen?.takeIf(::onScreen), rel, precise = false)
                }
            }.sortedByDescending { it.distanceM } // far first so near ones draw on top
            val edgeSlots = IntArray(2) // off-screen arrows already drawn on the left / right edge
            for (pl in placed) {
                drawPlayerMarker(text, pl, horizonY, maxRangeM, pl.player.snap.id == lockedId, effects.t, edgeSlots)
            }
        }
        with(effects) { drawEffects(text, laserColor, lowHealth, dead, lockedId != null, myPose?.azimuthDeg ?: 0.0, blocked) }
    }
}

/** Pixels per unit tangent for a center-cropped (FILL_CENTER) camera preview of this FOV. */
fun CameraFov.focalPx(width: Float, height: Float): Float = max(
    (height / 2) / tan(Math.toRadians(longDeg / 2)),
    (width / 2) / tan(Math.toRadians(shortDeg / 2)),
).toFloat()

private class Placed(
    val player: ArPlayer,
    val distanceM: Double,
    /** Null when the player is outside the view; then [relDeg] says which side. */
    val screen: Offset?,
    val relDeg: Double,
    /** Position comes from shared-arena camera tracking rather than GPS. */
    val precise: Boolean,
)

/** Walls from the shared map as translucent panels, so players can see what counts as cover. */
private fun DrawScope.drawWalls(walls: List<Wall>, view: ArView) {
    for (w in walls) {
        val corners = listOf(-1.0 to -1.0, 1.0 to -1.0, 1.0 to 1.0, -1.0 to 1.0).map { (su, sv) ->
            view.project(w.center + w.axisU * (su * w.halfU) + w.axisV * (sv * w.halfV), size.width, size.height)
        }
        // Skip walls partly behind the camera rather than drawing them inside-out.
        val pts = corners.filterNotNull()
        if (pts.size < 4) continue
        val path = Path().apply {
            pts.forEachIndexed { i, (x, y) -> if (i == 0) moveTo(x, y) else lineTo(x, y) }
            close()
        }
        drawPath(path, Color(0x1A00E5FF))
        drawPath(path, Color(0x6600E5FF), style = Stroke(1.5f * density))
    }
}

val RandomOrange = Color(0xFFFF9100)

/**
 * Corner brackets around a person found by the on-device detector. Right after a "random" shot
 * ([randomFlash] > 0) the box turns orange and is labelled, so it's clear who got shot.
 */
private fun DrawScope.drawPersonBox(r: ViewRect, randomFlash: Float, text: TextMeasurer) {
    val dp = density
    val l = min(r.width, r.height) * 0.18f
    val col = if (randomFlash > 0f) RandomOrange.copy(alpha = 0.5f + 0.5f * randomFlash) else Color.White.copy(alpha = 0.55f)
    if (randomFlash > 0f) {
        drawRect(RandomOrange.copy(alpha = 0.18f * randomFlash), topLeft = Offset(r.left, r.top), size = Size(r.width, r.height))
        val label = text.measure("NOT A PLAYER", TextStyle(color = RandomOrange.copy(alpha = randomFlash), fontSize = 13.sp, fontWeight = FontWeight.Black))
        drawText(label, topLeft = Offset(r.left + (r.width - label.size.width) / 2, r.top - label.size.height - 4 * dp))
    }
    for ((x, sx) in listOf(r.left to 1f, r.right to -1f)) for ((y, sy) in listOf(r.top to 1f, r.bottom to -1f)) {
        drawLine(col, Offset(x, y), Offset(x + sx * l, y), 2 * dp, StrokeCap.Round)
        drawLine(col, Offset(x, y), Offset(x, y + sy * l), 2 * dp, StrokeCap.Round)
    }
}

private fun DrawScope.drawPlayerMarker(
    text: TextMeasurer,
    placed: Placed,
    horizonY: Float,
    maxRangeM: Double,
    locked: Boolean,
    time: Double,
    edgeSlots: IntArray,
) {
    val dp = density
    val p = placed.player
    val distanceM = placed.distanceM
    val rel = placed.relDeg
    val color = when {
        !p.snap.alive -> Color.Gray
        p.friendly -> BlueTeam
        p.team != Team.NONE -> p.team.color()
        else -> RedTeam
    }
    val inRange = distanceM <= maxRangeM
    val alpha = when {
        !p.snap.alive -> 0.45f
        inRange -> 1f
        else -> 0.55f
    }
    val margin = 24 * dp

    val screen = placed.screen
    if (screen == null) {
        // Off-screen: arrow on the side the player is on.
        val left = rel < 0
        // Stack arrows on the same edge so they never overlap.
        val slot = edgeSlots[if (left) 0 else 1]++
        val horizonY = horizonY + (if (slot % 2 == 0) 1 else -1) * ((slot + 1) / 2) * 34 * dp
        val ax = if (left) margin else size.width - margin
        val dir = if (left) -1f else 1f
        val arrow = Path().apply {
            moveTo(ax + dir * 12 * dp, horizonY)
            lineTo(ax - dir * 6 * dp, horizonY - 12 * dp)
            lineTo(ax - dir * 6 * dp, horizonY + 12 * dp)
            close()
        }
        drawPath(arrow, color.copy(alpha = 0.85f * alpha))
        val label = text.measure("${distanceM.toInt()} m", TextStyle(color = color.copy(alpha = alpha), fontSize = 11.sp, fontWeight = FontWeight.Bold))
        val lx = if (left) ax + 14 * dp else ax - 14 * dp - label.size.width
        drawRoundRectBackdrop(Offset(lx - 4 * dp, horizonY - label.size.height / 2f - 2 * dp), Size(label.size.width + 8 * dp, label.size.height + 4 * dp))
        drawText(label, topLeft = Offset(lx, horizonY - label.size.height / 2f))
        return
    }

    // Marker gets bigger as the player gets closer.
    val scale = (25.0 / distanceM.coerceAtLeast(1.0)).coerceIn(0.55, 2.0).toFloat()
    val s = 16 * dp * scale
    val pos = screen

    if (!p.snap.alive) {
        drawLine(color.copy(alpha = alpha), Offset(pos.x - s, pos.y - s), Offset(pos.x + s, pos.y + s), 3 * dp)
        drawLine(color.copy(alpha = alpha), Offset(pos.x - s, pos.y + s), Offset(pos.x + s, pos.y - s), 3 * dp)
    } else {
        // Glow + diamond for enemies, chevron for teammates.
        drawCircle(Brush.radialGradient(listOf(color.copy(alpha = 0.45f * alpha), Color.Transparent), center = pos, radius = s * 2.2f), s * 2.2f, pos)
        val shape = Path().apply {
            if (p.friendly) {
                moveTo(pos.x - s, pos.y - s * 0.5f); lineTo(pos.x, pos.y + s * 0.5f); lineTo(pos.x + s, pos.y - s * 0.5f)
            } else {
                moveTo(pos.x, pos.y - s); lineTo(pos.x + s, pos.y); lineTo(pos.x, pos.y + s); lineTo(pos.x - s, pos.y); close()
            }
        }
        drawPath(shape, color.copy(alpha = alpha), style = Stroke(3 * dp, cap = StrokeCap.Round))
        if (!p.friendly) drawCircle(color.copy(alpha = alpha), 3 * dp, pos)
    }

    if (locked) {
        // Closing brackets around the locked target.
        val b = s * (1.9f + 0.25f * sin(time * 12).toFloat())
        val l = b * 0.45f
        for (sx in listOf(-1f, 1f)) for (sy in listOf(-1f, 1f)) {
            val corner = Offset(pos.x + sx * b, pos.y + sy * b)
            drawLine(RedTeam, corner, Offset(corner.x - sx * l, corner.y), 3 * dp, StrokeCap.Round)
            drawLine(RedTeam, corner, Offset(corner.x, corner.y - sy * l), 3 * dp, StrokeCap.Round)
        }
    }

    // Name, distance and health bar above the marker.
    val label = text.measure(
        // "AR" = position from shared camera tracking (cm-level), otherwise it's GPS (metres).
        "${if (p.talking) "🔊 " else ""}${p.name}  ${if (distanceM < 10) "%.1f".format(distanceM) else distanceM.toInt()} m${if (placed.precise) "  AR" else ""}",
        TextStyle(color = Color.White.copy(alpha = alpha), fontSize = 12.sp, fontWeight = FontWeight.Bold),
    )
    val top = pos.y - s * 2.2f - label.size.height - 10 * dp
    drawRoundRectBackdrop(Offset(pos.x - label.size.width / 2f - 6 * dp, top - 3 * dp), Size(label.size.width + 12 * dp, label.size.height + 14 * dp))
    drawText(label, topLeft = Offset(pos.x - label.size.width / 2f, top))
    val barW = label.size.width.toFloat()
    val barY = top + label.size.height + 3 * dp
    drawLine(Color.White.copy(alpha = 0.25f), Offset(pos.x - barW / 2, barY), Offset(pos.x + barW / 2, barY), 4 * dp)
    val hp = p.snap.health / Lobby.MAX_HEALTH.toFloat()
    drawLine(
        (if (hp > 0.25f) Color(0xFF4CAF50) else RedTeam).copy(alpha = alpha),
        Offset(pos.x - barW / 2, barY), Offset(pos.x - barW / 2 + barW * hp, barY), 4 * dp,
    )
}

private fun DrawScope.drawRoundRectBackdrop(topLeft: Offset, size: Size) {
    drawRoundRect(
        Color.Black.copy(alpha = 0.4f), topLeft, size,
        androidx.compose.ui.geometry.CornerRadius(6 * density, 6 * density),
    )
}

