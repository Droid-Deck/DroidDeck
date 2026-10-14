package com.droiddeck.launcher.glasses

import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class RayNeoCalibrationTest {
    private val axes = RayNeoSensorAxes(0f, false)
    private fun sample(a: FloatArray = floatArrayOf(0f, -9.81f, 0f), g: FloatArray = floatArrayOf(0.5f, -0.3f, 0.2f)) =
        RayNeoSensorSample(a, g, 0f, FloatArray(3), 0f, 0f, 0)

    private fun finish(c: RayNeoCalibrationCollector, start: Long = 0, sample: RayNeoSensorSample = sample()): RayNeoCalibration? {
        var result: RayNeoCalibration? = null
        for (ms in start..(start + 8100) step 10) c.update(sample, ms)?.let { result = it }
        return result
    }

    @Test fun upsideDownStillPoseMeasuresBiasAfterSettlingAndFiveSeconds() {
        val c = RayNeoCalibrationCollector(axes, 0)
        for (ms in 0L..7990 step 10) assertNull(c.update(sample(), ms))
        val result = c.update(sample(), 8000)!!
        assertEquals(CalibrationPhase.SAVED, c.phase)
        assertEquals(100, c.progress)
        assertEquals(Vec3(0.5f, -0.3f, 0.2f), result.gyroBiasDps)
        assertEquals(Quaternion.IDENTITY, result.level)
    }

    @Test fun uprightOrSidewaysCannotSave() {
        for (a in listOf(floatArrayOf(0f, 9.81f, 0f), floatArrayOf(9.81f, 0f, 0f))) {
            val c = RayNeoCalibrationCollector(axes, 0)
            assertNull(finish(c, sample = sample(a)))
            assertEquals(CalibrationPhase.TURN_OVER, c.phase)
        }
    }

    @Test fun movementRestartsTheWindowAndAllowsRecovery() {
        val c = RayNeoCalibrationCollector(axes, 0)
        for (ms in 0L..6000 step 10) c.update(sample(), ms)
        c.update(sample(g = floatArrayOf(20f, 0f, 0f)), 6010)
        assertEquals(CalibrationPhase.MOVING, c.phase)
        assertEquals(0, c.progress)
        assertNotNull(finish(c, 6020))
    }

    @Test fun VibrationIsRejectedEvenBelowTheInstantSpeedLimit() {
        val c = RayNeoCalibrationCollector(axes, 0)
        var result: RayNeoCalibration? = null
        for (ms in 0L..12000 step 10) {
            val gyro = if (ms % 20 == 0L) 4f else -4f
            c.update(sample(g = floatArrayOf(gyro, 0f, 0f)), ms)?.let { result = it }
        }
        assertNull(result)
    }

    @Test fun missingSamplesCannotCompleteAStationaryWindow() {
        val c = RayNeoCalibrationCollector(axes, 0)
        for (ms in 0L..6000 step 10) c.update(sample(), ms)
        assertNull(c.update(sample(), 9000))
        assertEquals(CalibrationPhase.MOVING, c.phase)
        assertNull(c.update(sample(), 30_000))
        assertEquals(CalibrationPhase.FAILED, c.phase)
    }

    @Test fun nonFiniteOrInvalidGravityCannotSave() {
        for (a in listOf(floatArrayOf(Float.NaN, -9.81f, 0f), FloatArray(3), floatArrayOf(0f, -20f, 0f))) {
            assertNull(finish(RayNeoCalibrationCollector(axes, 0), sample = sample(a)))
        }
    }

    @Test fun levelCorrectionAlignsBothInvertedAndUprightGravity() {
        val c = RayNeoCalibrationCollector(axes, 0)
        val result = finish(c, sample = sample(floatArrayOf(0.85f, -9.735f, 0.85f)))!!
        val up = result.level.rotate(Vec3(-0.85f, 9.735f, -0.85f).normalized())
        assertEquals(0f, up.x, 0.001f); assertEquals(1f, up.y, 0.001f); assertEquals(0f, up.z, 0.001f)
        val filter = RayNeoOrientationFilter(0f, calibration = result)
        var q = FloatArray(4)
        repeat(1000) { q = filter.update(sample(floatArrayOf(-0.85f, 9.735f, -0.85f)).copy(deviceTick100us = it * 100L)) }
        assertArrayEquals(floatArrayOf(1f, 0f, 0f, 0f), q, 0.001f)
    }

    @Test fun profilePersistenceIsIsolatedByVendorProductAndSerialAndCanReset() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("cal-test", Context.MODE_PRIVATE)
        val store = RayNeoCalibrationStore(prefs)
        val value = RayNeoCalibration(Vec3(0.1f, 0.2f, 0.3f), Quaternion.IDENTITY)
        val key = store.key(0x3941, 0xaf50, "first")
        store.save(key, value)
        assertEquals(value, store.load(key))
        for (other in listOf(store.key(0x3941, 0xaf50, "second"), store.key(0x1bbb, 0xaf50, "first"), store.key(0x3941, 1, "first"))) {
            assertNull(store.load(other))
        }
        store.save(key, null)
        assertNull(store.load(key))
    }

    @Test fun corruptOrExcessiveProfilesFallBackToDefaults() {
        assertNull(RayNeoCalibration.decode("bad"))
        assertNull(RayNeoCalibration.decode("{\"version\":2}"))
        assertNull(RayNeoCalibration.decode(RayNeoCalibration(Vec3(10f, 0f, 0f), Quaternion.IDENTITY).encode()))
        assertNull(RayNeoCalibration.decode(RayNeoCalibration(Vec3(0f, 0f, 0f), Quaternion(0f, 1f, 0f, 0f)).encode()))
    }
}
