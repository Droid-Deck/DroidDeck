package com.droiddeck.launcher.input

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.os.Handler
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers
import org.robolectric.shadows.ShadowBluetoothGatt
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [SteamControllerBleTest.Writer::class])
class SteamControllerBleTest {
    private val keys = ArrayList<Pair<Int, Boolean>>()
    private val router = InputRouter(object : InputOutput {
        override fun key(code: Int, down: Boolean) { keys += code to down }
        override fun button(code: Int, down: Boolean) {}
        override fun pointer(dx: Float, dy: Float) {}
        override fun scroll(steps: Int) {}
        override fun command(command: InputCommand) {}
        override fun requestMotion() {}
    })
    private lateinit var controller: SteamControllerBle
    private lateinit var handler: Handler

    @Before fun setUp() {
        Writer.reports.clear()
        Writer.connections.clear()
        Writer.features = 0
        router.setProfile(InputProfile.defaults(InputProfile.GAME)
            .withBinding(InputRole.PHYSICAL, InputControl.A, InputAction.Keys(listOf(57))))
        controller = SteamControllerBle(RuntimeEnvironment.getApplication(), router)
        handler = ReflectionHelpers.getField(controller, "handler")
        shadowOf(handler.looper).pause()
        controller.start()
    }

    @After fun close() {
        controller.stop()
        handler.looper.quit()
    }

    @Test fun stopReleasesAndDetachesInputBeforeQueuedCallbacksCanPublish() {
        state(ByteArray(45).apply { this[1] = 1 })
        assertEquals(listOf(57 to true), keys)
        handler.post { state(ByteArray(45).apply { this[1] = 1 }) }
        controller.stop()
        assertEquals(listOf(57 to true, 57 to false), keys)
        assertNull(ReflectionHelpers.getField<Any?>(controller, "routedInput"))
        assertTrue(Writer.reports.last().all { it == 0.toByte() })
        val reports = Writer.reports.size
        val connections = Writer.connections.toList()
        shadowOf(handler.looper).idle()
        state(ByteArray(45))
        state(ByteArray(45).apply { this[1] = 1 })
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "release")
        ReflectionHelpers.setField(controller, "featureRead", 7L)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "onFeatureRead", ReflectionHelpers.ClassParameter.from(ByteArray::class.java, ByteArray(2)))
        controller.stop()
        assertEquals(reports, Writer.reports.size)
        assertEquals(connections, Writer.connections)
        assertEquals(0, Writer.features)
        assertEquals(listOf(57 to true, 57 to false), keys)
    }

    @Test fun stoppingBeforeQueuedStartPreservesAnotherSourcesHeldKey() {
        val other = router.channel(InputRole.PRIMARY, InputControl.B, InputAction.Keys(listOf(57)))
        other.update(1f)
        state(ByteArray(45).apply { this[1] = 1 })
        controller.start()
        controller.stop()
        shadowOf(handler.looper).idle()
        assertFalse(ReflectionHelpers.getField<Boolean>(controller, "running"))
        assertEquals(listOf(57 to true), keys)
        other.close()
        assertEquals(listOf(57 to true, 57 to false), keys)
    }

    @Test fun staleControllerCannotClearOrPublishIntoItsReplacementsSlot() {
        state(ByteArray(45).apply { this[1] = 1 })
        assertEquals(listOf(57 to true), keys)
        val replacement = SteamControllerBle(RuntimeEnvironment.getApplication())
        val replacementHandler = ReflectionHelpers.getField<Handler>(replacement, "handler")
        shadowOf(replacementHandler.looper).pause()
        try {
            replacement.start()
            subscribe(replacement)
            val report = ByteArray(45).apply { this[1] = 2 }
            state(report, replacement)
            val reports = Writer.reports.size
            val connections = Writer.connections.toList()
            controller.start()
            shadowOf(handler.looper).idle()
            subscribe(controller)
            state(ByteArray(45))
            ReflectionHelpers.setField(controller, "featureRead", 7L)
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "onFeatureRead",
                ReflectionHelpers.ClassParameter.from(ByteArray::class.java, ByteArray(2)))
            ReflectionHelpers.callInstanceMethod<Unit>(controller, "disconnect",
                ReflectionHelpers.ClassParameter.from(String::class.java, "old link lost"))
            controller.stop()
            shadowOf(handler.looper).idle()
            assertTrue(SteamControllerBle.connected)
            assertEquals(connections, Writer.connections)
            assertEquals(reports, Writer.reports.size)
            assertArrayEquals(report, Writer.reports.last())
            assertEquals(0, Writer.features)
            assertEquals(listOf(57 to true, 57 to false), keys)
            assertNull(ReflectionHelpers.getField<Any?>(controller, "routedInput"))
            replacement.stop()
            assertFalse(SteamControllerBle.connected)
            assertFalse(Writer.connections.last())
            assertTrue(Writer.reports.last().all { it == 0.toByte() })
        } finally {
            replacement.stop()
            replacementHandler.looper.quit()
        }
    }

    @Test fun stoppingAnUnstartedControllerPreservesTheActiveSlot() {
        subscribe(controller)
        val unused = SteamControllerBle(RuntimeEnvironment.getApplication())
        try {
            val reports = Writer.reports.size
            val connections = Writer.connections.toList()
            unused.stop()
            assertTrue(SteamControllerBle.connected)
            assertEquals(connections, Writer.connections)
            assertEquals(reports, Writer.reports.size)
        } finally {
            unused.stop()
            ReflectionHelpers.getField<Handler>(unused, "handler").looper.quit()
        }
    }

    private fun subscribe(target: SteamControllerBle) {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val input = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
        val characteristic = BluetoothGattCharacteristic(input, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val descriptor = BluetoothGattDescriptor(UUID.fromString("00002902-0000-1000-8000-00805f9b34fb"), 0)
        characteristic.addDescriptor(descriptor)
        ReflectionHelpers.setField(target, "gatt", gatt)
        ReflectionHelpers.setField(target, "input", input)
        ReflectionHelpers.setField(target, "reportId", 0x45)
        ReflectionHelpers.getField<BluetoothGattCallback>(target, "callback")
            .onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
    }

    private fun state(bytes: ByteArray, target: SteamControllerBle = controller) = ReflectionHelpers.callInstanceMethod<Unit>(target, "onState",
        ReflectionHelpers.ClassParameter.from(ByteArray::class.java, bytes))

    @Implements(FakeInputWriter::class)
    class Writer {
        companion object {
            val reports = ArrayList<ByteArray>()
            val connections = ArrayList<Boolean>()
            var features = 0
            @JvmStatic @Implementation fun writeTritonState(slot: Int, reportId: Int, report: ByteArray) { reports += report.copyOf() }
            @JvmStatic @Implementation fun setTritonConnected(slot: Int, connected: Boolean) { connections += connected }
            @JvmStatic @Implementation fun writeTritonFeatureReply(slot: Int, request: Long, report: ByteArray?) { features++ }
        }
    }
}
