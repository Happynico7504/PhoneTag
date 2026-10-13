package com.phonetag.app.ar

import android.app.Activity
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.Image
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.google.ar.core.Anchor
import com.google.ar.core.ArCoreApk
import com.google.ar.core.AugmentedImage
import com.google.ar.core.AugmentedImageDatabase
import com.google.ar.core.Config
import com.google.ar.core.Coordinates2d
import com.google.ar.core.Frame
import com.google.ar.core.LightEstimate
import com.google.ar.core.Plane
import com.google.ar.core.Session
import com.google.ar.core.TrackingFailureReason
import com.google.ar.core.TrackingState
import com.google.ar.core.exceptions.NotYetAvailableException
import com.phonetag.core.ArenaCalibration
import com.phonetag.core.ArenaInfo
import com.phonetag.core.ArenaPose
import com.phonetag.core.Geo
import com.phonetag.core.Quat
import com.phonetag.core.Rigid
import com.phonetag.core.Vec3
import com.phonetag.core.Wall
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.nio.ByteOrder
import java.util.concurrent.Executors
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.abs

enum class ArSupport { CHECKING, SUPPORTED, UNSUPPORTED }

/** Where this phone stands with the lobby's shared arena. */
data class SyncStatus(
    val tracking: Boolean = false,
    val synced: Boolean = false,
    val isReference: Boolean = false,
    /** Calibration samples collected from a marker currently in view (scan progress). */
    val samples: Int = 0,
    val scanningMarkerOf: Int? = null,
    /** Camera has been still long enough for our own marker to be scanned reliably. */
    val steady: Boolean = false,
)

/** Per-frame camera geometry, used to project arena positions into the screen. */
class ArView(
    val viewProj: FloatArray,
    val view: FloatArray,
    val arenaToWorld: Rigid,
    val epoch: Int,
    /** Focal length in pixels (vertical), for vision distance estimates. */
    val focalPx: Float,
)

/**
 * Owns the ARCore session: 6-DoF camera tracking, the shared-arena anchor and marker scanning.
 *
 * Arena sync: the lobby names a reference phone, whose ARCore world (at the moment it syncs)
 * defines the arena frame. Every other phone syncs by scanning the marker on the screen of any
 * already-synced phone, which keeps broadcasting that screen's pose in arena coordinates (a
 * "beacon"). The result is stored as an ARCore [Anchor] at the arena origin, so ARCore keeps
 * correcting it as its map of the room improves.
 */
class ArEngine(private val context: Context) {
    private val _support = MutableStateFlow(ArSupport.CHECKING)
    val support: StateFlow<ArSupport> = _support.asStateFlow()

    private val _sync = MutableStateFlow(SyncStatus())
    val sync: StateFlow<SyncStatus> = _sync.asStateFlow()

    private val _view = MutableStateFlow<ArView?>(null)
    val view: StateFlow<ArView?> = _view.asStateFlow()

    /** Inputs from the game session. */
    @Volatile var arena = ArenaInfo()
    @Volatile var myId = 0
    @Volatile var beacons: Map<Int, Rigid> = emptyMap()

    /** Latest pose of this phone in the arena, or null if not synced / not tracking. */
    @Volatile var arenaPose: ArenaPose? = null
        private set
    /** Pose of this phone's screen in the arena (what we broadcast while showing our marker). */
    @Volatile var screenInArena: Rigid? = null
        private set

    /** Walls (vertical planes) ARCore currently sees, in arena coordinates; [wallsVersion] bumps on change. */
    @Volatile var walls: List<Wall> = emptyList()
        private set
    @Volatile var wallsVersion = 0
        private set

    /** AR depth straight down the crosshair (metres) and when it was measured. */
    @Volatile private var crosshairDepthM: Double? = null
    @Volatile private var crosshairDepthAtMs = 0L

    /** Average camera image brightness 0..1 from ARCore light estimation. */
    @Volatile var cameraBrightness: Float? = null
        private set
    /** Tracking is failing because the scene is too dark. */
    @Volatile var insufficientLight = false
        private set
    /** The session is running (camera open). */
    @Volatile var isRunning = false
        private set
    /** False if this phone's ARCore camera config can't drive the flashlight. */
    @Volatile var torchSupported = true
        private set
    @Volatile private var desiredTorch = false
    private var appliedTorch = false
    private var depthSupported = false

    /** Receives camera frames for person detection; must close the image when done. */
    @Volatile var imageSink: ((Image, rotationDegrees: Int) -> Unit)? = null

    private var session: Session? = null
    private var database: AugmentedImageDatabase? = null
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "ar-setup").apply { isDaemon = true } }
    private val background = BackgroundRenderer()
    private var textureBoundTo: Session? = null
    private var installRequested = false
    private var sensorRotation = 90

    private var localEpoch = -1
    private var anchor: Anchor? = null
    private val samples = ArrayList<Pair<Long, Rigid>>()
    private var lastCamera: Rigid? = null
    private var stillSinceMs = 0L

    init {
        checkSupport()
    }

    private fun checkSupport() {
        val availability = ArCoreApk.getInstance().checkAvailability(context)
        if (availability.isTransient) {
            android.os.Handler(context.mainLooper).postDelayed(::checkSupport, 300)
            return
        }
        _support.value = if (availability.isSupported) ArSupport.SUPPORTED else ArSupport.UNSUPPORTED
    }

    /**
     * Creates (installing Google Play Services for AR if needed) and resumes the session.
     * Returns false if AR can't run right now. Requires the CAMERA permission.
     */
    fun resume(activity: Activity): Boolean {
        if (_support.value != ArSupport.SUPPORTED) return false
        try {
            if (session == null) {
                when (ArCoreApk.getInstance().requestInstall(activity, !installRequested)) {
                    ArCoreApk.InstallStatus.INSTALL_REQUESTED -> {
                        installRequested = true
                        return false
                    }
                    ArCoreApk.InstallStatus.INSTALLED -> Unit
                }
                val s = Session(activity)
                session = s
                sensorRotation = sensorOrientation(s)
                depthSupported = s.isDepthModeSupported(Config.DepthMode.AUTOMATIC)
                torchSupported = hasFlash(s)
                configure(s)
                // Marker database: ARCore's feature extraction is slow, so build it in the background.
                worker.execute {
                    val db = AugmentedImageDatabase(s)
                    for (slot in 0 until SyncMarkers.SLOTS) {
                        runCatching { db.addImage(SyncMarkers.name(slot), SyncMarkers.bitmap(slot), SyncMarkers.WIDTH_M) }
                            .onFailure { Log.w(TAG, "marker $slot rejected: $it") }
                    }
                    database = db
                    if (session === s) runCatching { configure(s) }
                }
            }
            session?.resume()
            isRunning = true
            return true
        } catch (e: Exception) {
            Log.w(TAG, "AR unavailable: $e")
            if (session == null) _support.value = ArSupport.UNSUPPORTED
            return false
        }
    }

    fun pause() {
        isRunning = false
        session?.pause()
        cameraBrightness = null
        insufficientLight = false
        crosshairDepthM = null
        // Not tracking while paused: never report a frozen pose to the server.
        arenaPose = null
        screenInArena = null
        _view.value = null
        _sync.value = _sync.value.copy(tracking = false)
    }

    fun close() {
        anchor?.detach()
        session?.close()
        session = null
        worker.shutdown()
    }

    /** Switches the flashlight; applied on the next camera frame. Independent per phone. */
    fun setTorch(on: Boolean) {
        desiredTorch = on
    }

    /** Latest depth at the crosshair if it's fresh, else null. */
    fun crosshairDepth(): Double? = crosshairDepthM?.takeIf { SystemClock.elapsedRealtime() - crosshairDepthAtMs < 400 }

    @Synchronized
    private fun configure(s: Session) {
        val config = Config(s).apply {
            updateMode = Config.UpdateMode.LATEST_CAMERA_IMAGE
            focusMode = Config.FocusMode.AUTO
            // Vertical planes = walls for the shared obstacle map.
            planeFindingMode = Config.PlaneFindingMode.VERTICAL
            // Average image brightness, for the flashlight controller.
            lightEstimationMode = Config.LightEstimationMode.AMBIENT_INTENSITY
            // Per-pixel depth (from motion or a ToF sensor) for line-of-sight checks.
            depthMode = if (depthSupported) Config.DepthMode.AUTOMATIC else Config.DepthMode.DISABLED
            database?.let { augmentedImageDatabase = it }
        }
        config.flashMode = if (desiredTorch && torchSupported) Config.FlashMode.TORCH else Config.FlashMode.OFF
        s.configure(config)
        appliedTorch = config.flashMode == Config.FlashMode.TORCH
    }

    /** ARCore can only drive the torch if the camera it uses has a flash unit. */
    private fun hasFlash(s: Session): Boolean = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        manager.getCameraCharacteristics(s.cameraConfig.cameraId).get(CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
    }.getOrDefault(false)

    private fun sensorOrientation(s: Session): Int = runCatching {
        val manager = context.getSystemService(CameraManager::class.java)
        manager.getCameraCharacteristics(s.cameraConfig.cameraId).get(CameraCharacteristics.SENSOR_ORIENTATION) ?: 90
    }.getOrDefault(90)

    // ------------------------------------------------------------------ GL renderer

    val renderer = object : GLSurfaceView.Renderer {
        override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
            GLES20.glClearColor(0f, 0f, 0f, 1f)
            background.create()
            textureBoundTo = null // new GL context: rebind the camera texture
        }

        override fun onSurfaceChanged(gl: GL10?, width: Int, height: Int) {
            GLES20.glViewport(0, 0, width, height)
            viewport = width to height
            session?.setDisplayGeometry(Surface.ROTATION_0, width, height) // app is portrait-only
        }

        override fun onDrawFrame(gl: GL10?) {
            GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT or GLES20.GL_DEPTH_BUFFER_BIT)
            val s = session ?: return
            if (desiredTorch != appliedTorch && torchSupported) runCatching { configure(s) }
            if (textureBoundTo !== s) {
                s.setCameraTextureName(background.textureId)
                viewport?.let { (w, h) -> s.setDisplayGeometry(Surface.ROTATION_0, w, h) }
                textureBoundTo = s
            }
            val frame = try {
                s.update()
            } catch (e: Exception) {
                return // paused or camera unavailable this frame
            }
            background.draw(frame)
            runCatching { process(s, frame) }.onFailure { Log.w(TAG, "frame: $it") }
        }
    }

    @Volatile private var viewport: Pair<Int, Int>? = null
    @Volatile private var imageBusy = false
    private var lastImageAtMs = 0L

    // ------------------------------------------------------------------ per-frame logic

    private fun process(s: Session, frame: Frame) {
        val camera = frame.camera
        val tracking = camera.trackingState == TrackingState.TRACKING
        val cameraWorld = camera.displayOrientedPose.toRigid()
        val now = SystemClock.elapsedRealtime()
        insufficientLight = !tracking && camera.trackingFailureReason == TrackingFailureReason.INSUFFICIENT_LIGHT
        frame.lightEstimate.takeIf { it.state == LightEstimate.State.VALID }?.let { cameraBrightness = it.pixelIntensity }

        // Arena reset in the lobby: forget the old anchor.
        val lobbyArena = arena
        if (lobbyArena.epoch != localEpoch) {
            anchor?.detach()
            anchor = null
            samples.clear()
            localEpoch = lobbyArena.epoch
        }

        // ARCore gave up on the anchor (e.g. tracking reset): drop it so we can re-sync.
        if (anchor?.trackingState == TrackingState.STOPPED) {
            anchor?.detach()
            anchor = null
        }

        var scanningOf: Int? = null
        if (anchor == null && tracking && lobbyArena.epoch > 0) {
            if (lobbyArena.referenceId == myId) {
                // We define the arena: origin where we stand, -Z the way we face, +Y up.
                anchor = s.createAnchor(cameraWorld.yawOnly().toArPose())
            } else {
                scanningOf = scan(s, frame, now)
            }
        }

        val arenaToWorld = anchor?.takeIf { it.trackingState == TrackingState.TRACKING }?.pose?.toRigid()
        val steady = updateSteadiness(cameraWorld, now)
        if (tracking && arenaToWorld != null) {
            val worldToArena = arenaToWorld.inverse()
            val camArena = worldToArena * cameraWorld
            arenaPose = ArenaPose(localEpoch, camArena.position, camArena.rotation.rotate(Vec3.FORWARD))
            screenInArena = ArenaCalibration.screenPose(camArena)
            collectWalls(s, worldToArena, now)
        } else {
            arenaPose = null
            screenInArena = null
        }

        val proj = FloatArray(16)
        val viewM = FloatArray(16)
        camera.getProjectionMatrix(proj, 0, 0.05f, 200f)
        camera.getViewMatrix(viewM, 0)
        val vp = FloatArray(16)
        Matrix.multiplyMM(vp, 0, proj, 0, viewM, 0)
        val h = viewport?.second ?: 1
        _view.value = if (tracking && arenaToWorld != null) ArView(vp, viewM, arenaToWorld, localEpoch, proj[5] * h / 2f) else null
        _sync.value = SyncStatus(
            tracking = tracking,
            synced = arenaToWorld != null,
            isReference = lobbyArena.referenceId == myId && anchor != null,
            samples = if (scanningOf != null) samples.size else 0,
            scanningMarkerOf = scanningOf,
            steady = steady,
        )

        if (depthSupported && tracking) sampleCrosshairDepth(frame, now)

        // Hand a CPU copy of the camera image to person detection, ~8 times a second.
        val sink = imageSink
        if (sink != null && !imageBusy && now - lastImageAtMs > 120) {
            try {
                val image = frame.acquireCameraImage()
                imageBusy = true
                lastImageAtMs = now
                sink(image, sensorRotation)
            } catch (_: NotYetAvailableException) {
            } catch (e: Exception) {
                Log.w(TAG, "camera image: $e")
            }
        }
    }

    private var lastWallsAtMs = 0L
    private var lastDepthAtMs = 0L

    /** Every 2 s: the largest vertical planes, as wall rectangles in arena coordinates. */
    private fun collectWalls(s: Session, worldToArena: Rigid, now: Long) {
        if (now - lastWallsAtMs < 2_000) return
        lastWallsAtMs = now
        val found = s.getAllTrackables(Plane::class.java)
            .filter { it.trackingState == TrackingState.TRACKING && it.subsumedBy == null && it.type == Plane.Type.VERTICAL }
            .filter { it.extentX * it.extentZ >= MIN_WALL_AREA_M2 }
            .sortedByDescending { it.extentX * it.extentZ }
            .take(MAX_WALLS)
            .map { plane ->
                val pose = worldToArena * plane.centerPose.toRigid()
                // Plane polygons are convex hulls that overshoot a little; shrink the rectangle.
                Wall(
                    center = pose.position.rounded(),
                    axisU = pose.rotation.rotate(Vec3(1.0, 0.0, 0.0)).rounded(),
                    axisV = pose.rotation.rotate(Vec3(0.0, 0.0, 1.0)).rounded(),
                    halfU = (plane.extentX / 2.0 * 0.9).roundTo3(),
                    halfV = (plane.extentZ / 2.0 * 0.9).roundTo3(),
                )
            }
        if (found != walls) {
            walls = found
            wallsVersion++
        }
    }

    /** ~10 Hz: robust depth of a small patch at the view centre, i.e. what the crosshair is on. */
    private fun sampleCrosshairDepth(frame: Frame, now: Long) {
        if (now - lastDepthAtMs < 100) return
        lastDepthAtMs = now
        val (w, h) = viewport ?: return
        val image = try {
            frame.acquireDepthImage16Bits()
        } catch (_: NotYetAvailableException) {
            return
        }
        try {
            val uv = FloatArray(2)
            frame.transformCoordinates2d(Coordinates2d.VIEW, floatArrayOf(w / 2f, h / 2f), Coordinates2d.IMAGE_NORMALIZED, uv)
            val cx = (uv[0] * image.width).toInt()
            val cy = (uv[1] * image.height).toInt()
            val plane = image.planes[0]
            val buf = plane.buffer.order(ByteOrder.LITTLE_ENDIAN)
            val values = ArrayList<Int>(25)
            for (dy in -2..2) for (dx in -2..2) {
                val x = cx + dx
                val y = cy + dy
                if (x !in 0 until image.width || y !in 0 until image.height) continue
                val mm = buf.getShort(y * plane.rowStride + x * plane.pixelStride).toInt() and 0xFFFF
                if (mm > 0) values += mm
            }
            if (values.size >= 5) {
                values.sort()
                // Lower quartile: the nearest solid thing near the centre, ignoring single-pixel noise.
                crosshairDepthM = values[values.size / 4] / 1000.0
                crosshairDepthAtMs = now
            }
        } finally {
            image.close()
        }
    }

    /** Detection code calls this after closing the image it was handed. */
    fun imageDone() {
        imageBusy = false
    }

    /** Collects calibration samples from any synced player's marker in view. Returns their id. */
    private fun scan(s: Session, frame: Frame, now: Long): Int? {
        samples.removeAll { now - it.first > SAMPLE_WINDOW_MS }
        val known = beacons
        for (img in s.getAllTrackables(AugmentedImage::class.java)) {
            if (img.trackingState != TrackingState.TRACKING || img.trackingMethod != AugmentedImage.TrackingMethod.FULL_TRACKING) continue
            val slot = SyncMarkers.slotOf(img.name) ?: continue
            val (playerId, beacon) = known.entries.firstOrNull { SyncMarkers.slotFor(it.key) == slot } ?: continue
            if (playerId == myId) continue
            samples += now to ArenaCalibration.arenaToWorld(img.centerPose.toRigid(), beacon)
            if (samples.size >= SAMPLES_NEEDED) {
                val avg = ArenaCalibration.average(samples.map { it.second })
                if (avg != null) {
                    anchor = s.createAnchor(avg.toArPose())
                    samples.clear()
                } else {
                    samples.removeAt(0) // inconsistent: slide the window
                }
            }
            return playerId
        }
        return null
    }

    private fun updateSteadiness(camera: Rigid, now: Long): Boolean {
        val last = lastCamera
        lastCamera = camera
        if (last != null) {
            val moved = (camera.position - last.position).length()
            val turned = abs(Geo.signedAngleDeg(Math.toDegrees(last.rotation.yaw()), Math.toDegrees(camera.rotation.yaw())))
            if (moved > 0.006 || turned > 0.6) stillSinceMs = now
        }
        return now - stillSinceMs > 400
    }

    private fun com.google.ar.core.Pose.toRigid() =
        Rigid(Vec3(tx().toDouble(), ty().toDouble(), tz().toDouble()), Quat(qx().toDouble(), qy().toDouble(), qz().toDouble(), qw().toDouble()))

    private fun Rigid.toArPose() = com.google.ar.core.Pose(
        floatArrayOf(position.x.toFloat(), position.y.toFloat(), position.z.toFloat()),
        floatArrayOf(rotation.x.toFloat(), rotation.y.toFloat(), rotation.z.toFloat(), rotation.w.toFloat()),
    )

    private companion object {
        const val TAG = "ArEngine"
        const val SAMPLES_NEEDED = 15
        const val SAMPLE_WINDOW_MS = 2_500L
        const val MAX_WALLS = 32
        const val MIN_WALL_AREA_M2 = 0.6f
    }
}

private fun Double.roundTo3() = Math.round(this * 1000) / 1000.0
private fun Vec3.rounded() = Vec3(x.roundTo3(), y.roundTo3(), z.roundTo3())

/** Projects an arena-frame point to screen pixels. Returns null if it's behind the camera. */
fun ArView.project(p: Vec3, width: Float, height: Float): Pair<Float, Float>? {
    val w = arenaToWorld.transform(p)
    val v = floatArrayOf(w.x.toFloat(), w.y.toFloat(), w.z.toFloat(), 1f)
    val clip = FloatArray(4)
    Matrix.multiplyMV(clip, 0, viewProj, 0, v, 0)
    if (clip[3] <= 0.01f) return null
    val x = (clip[0] / clip[3] + 1f) / 2f * width
    val y = (1f - clip[1] / clip[3]) / 2f * height
    return x to y
}

/** Horizontal angle (degrees, + = right) of an arena point relative to where the camera looks. */
fun ArView.relativeAngleDeg(p: Vec3): Double {
    val w = arenaToWorld.transform(p)
    val v = floatArrayOf(w.x.toFloat(), w.y.toFloat(), w.z.toFloat(), 1f)
    val eye = FloatArray(4)
    Matrix.multiplyMV(eye, 0, view, 0, v, 0)
    return Math.toDegrees(kotlin.math.atan2(eye[0].toDouble(), -eye[2].toDouble()))
}
