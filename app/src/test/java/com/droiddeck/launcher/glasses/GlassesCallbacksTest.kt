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
        val probe: (ByteArray) -> Unit = { samples++ }
        field("probe").set(null, probe)
        generation(2)
        listener(1).onImu(ByteArray(12), 0)
        assertEquals(0, samples)
        assertEquals(0L, field("imuPackets").getLong(null))
        listener(2).onImu(ByteArray(12), 0)
        assertEquals(1, samples)
        assertEquals(1L, field("imuPackets").getLong(null))
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
}
