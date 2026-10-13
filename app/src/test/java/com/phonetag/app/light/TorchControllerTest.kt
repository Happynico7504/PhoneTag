package com.phonetag.app.light

import com.phonetag.app.light.TorchController.Input
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TorchControllerTest {
    private val c = TorchController()
    private var now = 0L

    /** Feeds the same reading every 200 ms for [ms] and returns the final torch state. */
    private fun run(ms: Long, lux: Float?, cam: Float? = null, dark: Boolean = false): Boolean {
        var on = c.torchOn
        val end = now + ms
        while (now < end) {
            now += 200
            on = c.update(Input(now, lux, cam, dark))
        }
        return on
    }

    @Test
    fun turnsOnInTheDarkAndOffWhenBrightWithHysteresis() {
        assertFalse(run(5_000, lux = 200f))
        assertFalse(run(600, lux = 3f))            // brief shadow: ignored
        assertFalse(run(5_000, lux = 200f))
        assertTrue(run(1_400, lux = 3f))           // sustained darkness: on
        assertTrue(run(10_000, lux = 20f))         // between thresholds: stays on
        assertTrue(run(2_000, lux = 100f))         // bright, but not for long enough yet
        assertFalse(run(1_400, lux = 100f))        // bright for 3 s: off
        assertFalse(run(5_000, lux = 20f))         // between thresholds: stays off
    }

    @Test
    fun respectsMinimumOnTime() {
        assertTrue(run(1_200, lux = 1f))
        assertTrue(run(4_000, lux = 500f))         // still within the 6 s minimum on-time
        assertFalse(run(4_000, lux = 500f))
    }

    @Test
    fun arcoreInsufficientLightForcesTheTorchOnAndKeepsItOn() {
        assertTrue(run(1_400, lux = 50f, dark = true))
        assertTrue(run(10_000, lux = 50f, dark = true))
    }

    @Test
    fun withoutLightSensorItProbesToDecide() {
        // Camera dark while the torch is off → on.
        assertTrue(run(1_400, lux = null, cam = 0.05f))
        // Torch lights the scene: camera looks bright, but that's our own light. Still on until the probe.
        assertTrue(run(15_000, lux = null, cam = 0.5f))
        // At the probe, the torch goes off briefly and the camera sees the real (still dark) scene.
        run(5_000, lux = null, cam = 0.5f)
        assertEquals(TorchController.State.PROBING, c.state)
        assertTrue(run(1_000, lux = null, cam = 0.04f)) // still dark → back on
        // Next probe in a lit room → stays off.
        run(20_000, lux = null, cam = 0.5f)
        assertEquals(TorchController.State.PROBING, c.state)
        assertFalse(run(1_000, lux = null, cam = 0.4f))
    }
}
