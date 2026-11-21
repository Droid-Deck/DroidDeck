package com.droiddeck.launcher.input

import kotlin.math.acos
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class PenAxesTest {
    private fun cooked(tiltXDeg: Double, tiltYDeg: Double): Pair<Float, Float> {
        val tx = Math.toRadians(tiltXDeg)
        val ty = Math.toRadians(tiltYDeg)
        return acos(cos(tx) * cos(ty)).toFloat() to atan2(-sin(tx), sin(ty)).toFloat()
    }

    @Test fun tiltRecoversTheRawAnglesAndroidCookedIntoTiltAndOrientation() {
        val out = IntArray(2)
        for (x in -60..60 step 5) for (y in -60..60 step 5) {
            if (x == 0 && y == 0) continue
            val (tilt, orientation) = cooked(x.toDouble(), y.toDouble())
            assertTrue("$x,$y", PenAxes.tilt(tilt, orientation, out))
            assertEquals("x of $x,$y", x * 100.0, out[0].toDouble(), 2.0)
            assertEquals("y of $x,$y", y * 100.0, out[1].toDouble(), 2.0)
        }
    }

    @Test fun tiltAlongOneAxisStaysOnThatAxis() {
        val out = IntArray(2)
        val (tilt, orientation) = cooked(30.0, 0.0)
        assertTrue(PenAxes.tilt(tilt, orientation, out))
        assertArrayEquals(intArrayOf(3000, 0), out)
        val (tiltY, orientationY) = cooked(0.0, -45.0)
        assertTrue(PenAxes.tilt(tiltY, orientationY, out))
        assertArrayEquals(intArrayOf(0, -4500), out)
        assertTrue(PenAxes.tilt((Math.PI / 2).toFloat(), (-Math.PI / 2).toFloat(), out))
        assertArrayEquals(intArrayOf(9000, 0), out)
    }

    @Test fun aSampleWithZeroTiltCarriesNoTiltInEveryWindowRotation() {
        val out = intArrayOf(7, 7)
        for (orientation in listOf(0f, (Math.PI / 2).toFloat(), (-Math.PI / 2).toFloat(), Math.PI.toFloat(), 1f)) {
            assertFalse("$orientation", PenAxes.tilt(0f, orientation, out))
        }
        assertArrayEquals(intArrayOf(7, 7), out)
        assertTrue(PenAxes.tilt(Float.NaN, 1f, out))
        assertArrayEquals(intArrayOf(0, 0), out)
    }

    @Test fun pressureCoversTheWholeRange() {
        assertEquals(0, PenAxes.pressure(0f))
        assertEquals(32768, PenAxes.pressure(0.5f))
        assertEquals(PenAxes.AXIS_MAX, PenAxes.pressure(1f))
        assertEquals(PenAxes.AXIS_MAX, PenAxes.pressure(1.3f))
        assertEquals(0, PenAxes.pressure(-0.2f))
        assertEquals(0, PenAxes.pressure(Float.NaN))
        assertEquals(16, PenAxes.pressure(1f / 4095))
    }

    @Test fun distanceIsNormalisedByTheDeviceRange() {
        assertEquals(PenAxes.AXIS_MAX, PenAxes.distance(1f, 0f, 1f))
        assertEquals(0, PenAxes.distance(0f, 0f, 1f))
        assertEquals(32768, PenAxes.distance(127.5f, 0f, 255f))
        assertEquals(PenAxes.AXIS_MAX, PenAxes.distance(300f, 0f, 255f))
        assertEquals(0, PenAxes.distance(0.5f, 0f, 0f))
    }
}
