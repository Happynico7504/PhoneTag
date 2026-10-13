package com.phonetag.app.sensors

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.GeomagneticField
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.location.Location
import android.os.Looper
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import com.phonetag.core.Geo
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/** Where the phone's camera is pointing, relative to true north. */
data class Aim(val azimuthDeg: Double, val pitchDeg: Double, val compassReliable: Boolean)

/**
 * Compass heading of the back camera while the phone is held upright like a viewfinder.
 * Uses the fused rotation-vector sensor (gyro + accel + magnetometer), smoothed, and corrected
 * from magnetic to true north so it matches GPS bearings.
 */
class OrientationTracker(context: Context) : SensorEventListener {
    private val sensorManager = context.getSystemService(Context.SENSOR_SERVICE) as SensorManager
    private val rotationSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)
    private val rotation = FloatArray(9)
    private val remapped = FloatArray(9)
    private val orientation = FloatArray(3)

    private var smoothSin = 0.0
    private var smoothCos = 1.0
    private var declinationDeg = 0.0
    private var reliable = true

    private val _aim = MutableStateFlow(Aim(0.0, 0.0, true))
    val aim: StateFlow<Aim> = _aim.asStateFlow()

    val isAvailable get() = rotationSensor != null

    fun start() {
        rotationSensor?.let { sensorManager.registerListener(this, it, SensorManager.SENSOR_DELAY_GAME) }
    }

    fun stop() = sensorManager.unregisterListener(this)

    fun updateDeclination(location: Location) {
        declinationDeg = GeomagneticField(
            location.latitude.toFloat(), location.longitude.toFloat(), location.altitude.toFloat(), System.currentTimeMillis(),
        ).declination.toDouble()
    }

    override fun onSensorChanged(event: SensorEvent) {
        SensorManager.getRotationMatrixFromVector(rotation, event.values)
        // Phone upright in portrait, camera facing forward: remap so azimuth follows the camera axis.
        SensorManager.remapCoordinateSystem(rotation, SensorManager.AXIS_X, SensorManager.AXIS_Z, remapped)
        SensorManager.getOrientation(remapped, orientation)
        val azimuth = Math.toRadians(Math.toDegrees(orientation[0].toDouble()) + declinationDeg)
        // Low-pass on the unit circle so 359° -> 1° doesn't swing through 180°.
        smoothSin = smoothSin * (1 - ALPHA) + sin(azimuth) * ALPHA
        smoothCos = smoothCos * (1 - ALPHA) + cos(azimuth) * ALPHA
        val deg = Geo.normalizeDeg(Math.toDegrees(atan2(smoothSin, smoothCos)))
        _aim.value = Aim(deg, -Math.toDegrees(orientation[1].toDouble()), reliable)
    }

    override fun onAccuracyChanged(sensor: Sensor, accuracy: Int) {
        reliable = accuracy >= SensorManager.SENSOR_STATUS_ACCURACY_MEDIUM
        _aim.value = _aim.value.copy(compassReliable = reliable)
    }

    private companion object {
        const val ALPHA = 0.25
    }
}

/** High-accuracy GPS fixes via the fused location provider. */
class LocationTracker(context: Context) {
    private val client = LocationServices.getFusedLocationProviderClient(context)
    private val _location = MutableStateFlow<Location?>(null)
    val location: StateFlow<Location?> = _location.asStateFlow()
    private var running = false

    private val callback = object : LocationCallback() {
        override fun onLocationResult(result: LocationResult) {
            result.lastLocation?.let { _location.value = it }
        }
    }

    /** Caller must hold ACCESS_FINE_LOCATION. */
    @SuppressLint("MissingPermission")
    fun start() {
        if (running) return
        val request = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 500)
            .setMinUpdateIntervalMillis(250)
            .setWaitForAccurateLocation(false)
            .build()
        client.requestLocationUpdates(request, callback, Looper.getMainLooper())
        running = true
    }

    fun stop() {
        if (!running) return
        client.removeLocationUpdates(callback)
        running = false
    }
}
