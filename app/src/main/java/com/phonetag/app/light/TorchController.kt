package com.phonetag.app.light

/**
 * Decides when this phone's flashlight (torch) should be on so camera tracking keeps working
 * in the dark. Pure logic, driven by [update] a few times per second; every phone runs its own
 * instance, so nothing is synchronised over the network.
 *
 * Inputs, in order of trust:
 * - the ambient **light sensor** (front side, so our own rear torch barely affects it);
 * - ARCore reporting that tracking failed for **insufficient light**;
 * - the **camera image brightness**, only trusted while the torch is off (the torch lights up
 *   the scene and would otherwise switch itself off again).
 *
 * Hysteresis (separate on/off thresholds), "must hold for a while" timers and a minimum on-time
 * keep it from flickering. Without a light sensor, it periodically turns the torch off for a
 * moment ("probe") to measure the real ambient brightness.
 */
class TorchController(private val t: Thresholds = Thresholds()) {
    data class Thresholds(
        val luxOn: Float = 8f,
        val luxOff: Float = 35f,
        val cameraOn: Float = 0.10f,
        val cameraOff: Float = 0.22f,
        /** A dark/bright condition must persist this long before acting. */
        val onDelayMs: Long = 1_000,
        val offDelayMs: Long = 3_000,
        /** Once on, stay on at least this long. */
        val minOnMs: Long = 6_000,
        /** Without a light sensor: how often to probe, and for how long the torch goes off. */
        val probeEveryMs: Long = 20_000,
        val probeMs: Long = 800,
    )

    data class Input(
        val nowMs: Long,
        /** Ambient light in lux, or null if the phone has no light sensor. */
        val lux: Float?,
        /** Average camera image brightness 0..1, or null if unknown. */
        val cameraBrightness: Float?,
        /** ARCore says tracking is failing because it's too dark. */
        val insufficientLight: Boolean,
    )

    enum class State { OFF, ON, PROBING }

    var state = State.OFF
        private set

    /** What the torch should be right now. */
    val torchOn get() = state == State.ON

    private var darkSince = -1L
    private var brightSince = -1L
    private var onSince = 0L
    private var probeStartedAt = 0L

    fun reset() {
        state = State.OFF
        darkSince = -1L
        brightSince = -1L
    }

    fun update(i: Input): Boolean {
        when (state) {
            State.OFF -> {
                val dark = i.insufficientLight ||
                    (i.lux != null && i.lux < t.luxOn) ||
                    (i.lux == null && i.cameraBrightness != null && i.cameraBrightness < t.cameraOn)
                darkSince = if (dark) (if (darkSince < 0) i.nowMs else darkSince) else -1L
                if (darkSince >= 0 && i.nowMs - darkSince >= t.onDelayMs) turnOn(i.nowMs)
            }
            State.ON -> {
                // Light sensor available: it's not fooled by our torch, so watch it directly.
                val bright = i.lux != null && i.lux > t.luxOff && !i.insufficientLight
                brightSince = if (bright) (if (brightSince < 0) i.nowMs else brightSince) else -1L
                val held = i.nowMs - onSince >= t.minOnMs
                if (held && brightSince >= 0 && i.nowMs - brightSince >= t.offDelayMs) {
                    turnOff()
                } else if (held && i.lux == null && i.nowMs - onSince >= t.probeEveryMs && !i.insufficientLight) {
                    state = State.PROBING
                    probeStartedAt = i.nowMs
                }
            }
            State.PROBING -> {
                // Torch is off for a moment; the camera now sees the real ambient light.
                if (i.insufficientLight) {
                    turnOn(i.nowMs)
                } else if (i.nowMs - probeStartedAt >= t.probeMs) {
                    val b = i.cameraBrightness
                    if (b != null && b > t.cameraOff) turnOff() else turnOn(i.nowMs)
                }
            }
        }
        return torchOn
    }

    private fun turnOn(now: Long) {
        state = State.ON
        onSince = now
        darkSince = -1L
        brightSince = -1L
    }

    private fun turnOff() {
        state = State.OFF
        darkSince = -1L
        brightSince = -1L
    }
}
