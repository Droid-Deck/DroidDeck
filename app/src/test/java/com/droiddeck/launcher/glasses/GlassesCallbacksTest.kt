package com.droiddeck.launcher.glasses

import android.os.Handler
import android.os.Looper
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesCallbacksTest {
    private fun field(name: String) = Glasses::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun generation(value: Long) = field("linkGeneration").setLong(null, value)
    private fun listener(generation: Long): VitureLink.Listener =
        Glasses::class.java.getDeclaredMethod("linkListener", Long::class.javaPrimitiveType).apply { isAccessible = true }
            .invoke(Glasses, generation) as VitureLink.Listener

    @Before fun prepare() {
        field("handler").set(null, Handler(Looper.getMainLooper()))
        generation(1)
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = GlassesState(connected = true, brightness = 2, displayId = 2, width = 640, height = 480, noSignal = true)
        field("imuPackets").setLong(null, 0)
        field("probe").set(null, null)
    }

    private fun brightness(value: Int) = VitureProtocol.Frame(
        VitureProtocol.MAGIC_REPORT, VitureProtocol.EVENT_BRIGHTNESS, byteArrayOf(value.toByte()), true,
    )

    @Test fun queuedDisconnectCannotCloseAReplacementLink() {
        val old = listener(1)
        old.onLost()
        old.onLost()
        generation(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Glasses.state.value.connected)
    }

    @Test fun queuedReportCannotOverwriteReplacementState() {
        listener(1).onFrame(brightness(7))
        generation(2)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, Glasses.state.value.brightness)
        listener(2).onFrame(brightness(4))
        listener(1).onFrame(brightness(8))
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(4, Glasses.state.value.brightness)
    }

    @Test fun oldImuPacketsCannotReachTheNewProbe() {
        var samples = 0
        val probe: (ByteArray?, FloatArray?) -> Unit = { _, _ -> samples++ }
        field("probe").set(null, probe)
        generation(2)
        listener(1).onImu(ByteArray(12), 0)
        assertEquals(0, samples)
        assertEquals(0L, field("imuPackets").getLong(null))
        listener(2).onImu(ByteArray(12), 0)
        assertEquals(1, samples)
        assertEquals(1L, field("imuPackets").getLong(null))
    }

    @Test fun rayNeoPoseUsesTheExistingSinkAndRejectsAnOldConnection() {
        fun rayneo(gen: Long) = Glasses::class.java.getDeclaredMethod("rayNeoListener", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(Glasses, gen) as RayNeoLink.Listener
        var delivered: FloatArray? = null
        var timestamp = 0L
        val sink = Glasses.PoseSink { x, y, z, w, time ->
            delivered = floatArrayOf(x, y, z, w)
            timestamp = time
        }
        field("sink").set(null, sink)
        try {
            val q = floatArrayOf(0f, 0f, 0.6f, 0.8f)
            rayneo(0).onPose(ByteArray(64), q, 99)
            assertEquals(null, delivered)
            rayneo(1).onPose(ByteArray(64), q, 123)
            org.junit.Assert.assertArrayEquals(q, delivered, 0f)
            assertEquals(123L, timestamp)
            assertEquals(1L, field("imuPackets").getLong(null))
            rayneo(0).onLost()
            generation(2)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Glasses.state.value.connected)
        } finally {
            field("sink").set(null, null)
        }
    }

    @Test fun deviceModeReportsCannotConfirmThePhysicalStereoSignal() {
        listener(1).onFrame(VitureProtocol.Frame(
            VitureProtocol.MAGIC_REPORT, VitureProtocol.EVENT_DISPLAY, byteArrayOf(0x32), true,
        ))
        listener(1).onFrame(VitureProtocol.Frame(
            VitureProtocol.MAGIC_REPORT, VitureProtocol.MSG_GET_DISPLAY, byteArrayOf(0, 0x32), true,
        ))
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(Glasses.state.value.sbs)
        assertTrue(Glasses.state.value.noSignal)
        assertEquals(640, Glasses.state.value.width)
    }

    @Test fun cancelledCalibrationCallbacksCannotResurrectOrOverwriteTheProfile() {
        val rayneo = Glasses::class.java.getDeclaredMethod("rayNeoListener", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(Glasses, 1L) as RayNeoLink.Listener
        @Suppress("UNCHECKED_CAST")
        val calibration = field("_calibration").get(null) as MutableStateFlow<GlassesCalibrationState>
        calibration.value = GlassesCalibrationState(supported = true, saved = true, phase = CalibrationPhase.SETTLING)
        field("calibrationRevision").setLong(null, 1)
        rayneo.onCalibrationSaved(1, RayNeoCalibration(Vec3(0f, 0f, 0f), Quaternion.IDENTITY))
        rayneo.onCalibration(1, CalibrationPhase.SAVED, 100)
        field("calibrationRevision").setLong(null, 2)
        calibration.value = calibration.value.copy(phase = CalibrationPhase.IDLE)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(CalibrationPhase.IDLE, calibration.value.phase)
        assertTrue(calibration.value.saved)
    }

    @Test fun sdkCallbacksCannotCrossConnectionGenerationsOrFakeStereoConfirmation() {
        fun sdk(gen: Long) = Glasses::class.java.getDeclaredMethod("sdkListener", Long::class.javaPrimitiveType)
            .apply { isAccessible = true }.invoke(Glasses, gen) as VitureSdkLink.Listener
        var samples = 0
        val probe: (ByteArray?, FloatArray?) -> Unit = { raw, pose ->
            assertEquals(null, raw)
            assertEquals(4, pose!!.size)
            samples++
        }
        field("probe").set(null, probe)
        sdk(0).onPose(floatArrayOf(0f, 0f, 0f, 1f), 100)
        sdk(1).onPose(floatArrayOf(0f, 0f, 0f, 1f), 101)
        assertEquals(1, samples)
        sdk(0).onValue(VitureProtocol.MSG_GET_BRIGHTNESS, 8)
        sdk(1).onValue(VitureProtocol.MSG_GET_DISPLAY, 0x42)
        sdk(0).onLost()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, Glasses.state.value.brightness)
        assertFalse(Glasses.state.value.sbs)
        assertTrue(Glasses.state.value.connected)
        field("probe").set(null, null)
    }

    @Test fun rejectedTrackingStartDoesNotAdvertiseTracking() {
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = state.value.copy(tracking = false)
        val rejected = object : GlassesLink {
            override val hasImu = true
            override fun send(msgId: Int, payload: ByteArray) = false
            override fun close() {}
        }
        field("link").set(null, rejected)
        field("sink").set(null, Glasses.PoseSink { _, _, _, _, _ -> })
        try {
            Glasses::class.java.getDeclaredMethod("updateImu").apply { isAccessible = true }.invoke(Glasses)
            assertFalse(Glasses.state.value.tracking)
        } finally {
            field("link").set(null, null)
            field("sink").set(null, null)
        }
    }
}
