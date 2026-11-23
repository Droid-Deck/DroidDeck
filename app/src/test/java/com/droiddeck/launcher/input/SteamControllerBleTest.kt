package com.droiddeck.launcher.input

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
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
import org.robolectric.shadow.api.Shadow
import org.robolectric.shadows.ShadowBluetoothGatt
import java.time.Duration
import java.util.UUID

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE, shadows = [SteamControllerBleTest.Writer::class, SteamControllerBleTest.BusyGatt::class])
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

    /** Runs what stop() posted unless stop() already quit the looper, then quits it. */
    private fun drain(looper: android.os.Looper) {
        runCatching { shadowOf(looper).idle() }
        looper.quit()
    }

    @After fun close() {
        controller.stop()
        // The stop posts a disconnect; run it now, while Robolectric's environment still exists.
        drain(handler.looper)
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
            drain(replacementHandler.looper)
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
            drain(ReflectionHelpers.getField<Handler>(unused, "handler").looper)
        }
    }

    @Test fun unansweredSubscriptionDropsTheLink() {
        val link = ValveLink()
        link.start()
        assertEquals(1, link.shadow.descriptorWrites)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(2_500))
        assertEquals(1, link.shadow.descriptorWrites)
        assertEquals(2, link.shadow.refused)
        assertSame(link.gatt, ReflectionHelpers.getField<Any?>(controller, "gatt"))
        shadowOf(handler.looper).idleFor(Duration.ofMillis(1_000))
        assertNull(ReflectionHelpers.getField<Any?>(controller, "gatt"))
        assertFalse(SteamControllerBle.connected)
        assertEquals(0, link.shadow.lizardWrites)
    }

    @Test fun failedSubscriptionIsWrittenAgain() {
        val link = ValveLink()
        link.start()
        link.answer(BluetoothGatt.GATT_FAILURE)
        assertFalse(SteamControllerBle.connected)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(1_500))
        assertEquals(2, link.shadow.descriptorWrites)
        link.answer()
        assertTrue(SteamControllerBle.connected)
        assertEquals(1, link.shadow.lizardWrites)
        link.answer()
        shadowOf(handler.looper).idleFor(Duration.ofMillis(2_000))
        assertEquals(2, link.shadow.descriptorWrites)
        assertSame(link.gatt, ReflectionHelpers.getField<Any?>(controller, "gatt"))
        assertTrue(SteamControllerBle.connected)
    }

    @Test fun inputBeforeTheSubscriptionReplyTurnsLizardModeOffOnceTheReplyArrives() {
        val link = ValveLink()
        link.start()
        link.input()
        assertTrue(SteamControllerBle.connected)
        assertTrue(Writer.connections.last())
        shadowOf(handler.looper).idleFor(Duration.ofMillis(500))
        assertEquals(0, link.shadow.lizardWrites)
        link.answer()
        shadowOf(handler.looper).idleFor(Duration.ofMillis(20))
        assertEquals(1, link.shadow.lizardWrites)
        link.answer()
        shadowOf(handler.looper).idleFor(Duration.ofMillis(3_000))
        assertEquals(1, link.shadow.descriptorWrites)
        assertSame(link.gatt, ReflectionHelpers.getField<Any?>(controller, "gatt"))
        assertTrue(SteamControllerBle.connected)
    }

    @Test fun inputWithoutALizardModeReplyDropsTheLink() {
        val link = ValveLink()
        link.start()
        link.input()
        assertTrue(SteamControllerBle.connected)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(2_500))
        assertEquals(1, link.shadow.descriptorWrites)
        assertEquals(0, link.shadow.lizardWrites)
        assertSame(link.gatt, ReflectionHelpers.getField<Any?>(controller, "gatt"))
        shadowOf(handler.looper).idleFor(Duration.ofMillis(1_000))
        assertNull(ReflectionHelpers.getField<Any?>(controller, "gatt"))
        assertFalse(SteamControllerBle.connected)
        assertFalse(Writer.connections.last())
    }

    @Test fun answeredSubscriptionStartsOneLizardModeRefresh() {
        val link = ValveLink()
        link.start()
        link.answer()
        assertTrue(SteamControllerBle.connected)
        assertEquals(1, link.shadow.lizardWrites)
        link.answer()
        link.input()
        assertEquals(1, link.shadow.lizardWrites)
        shadowOf(handler.looper).idleFor(Duration.ofMillis(3_500))
        assertEquals(1, link.shadow.descriptorWrites)
        assertEquals(2, link.shadow.lizardWrites)
    }

    private inner class ValveLink {
        val gatt: BluetoothGatt = ShadowBluetoothGatt.newInstance(
            BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:66"))
        val shadow = Shadow.extract<BusyGatt>(gatt)
        private val characteristic = BluetoothGattCharacteristic(INPUT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0).apply {
            addDescriptor(BluetoothGattDescriptor(CCCD, 0))
        }
        private val callback = ReflectionHelpers.getField<BluetoothGattCallback>(controller, "callback")

        init {
            val service = BluetoothGattService(SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
            service.addCharacteristic(characteristic)
            service.addCharacteristic(BluetoothGattCharacteristic(REPORT,
                BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_READ, 0))
            shadow.addDiscoverableService(service)
            shadow.setGattCallback(object : BluetoothGattCallback() {})
            gatt.discoverServices()
        }

        fun start() {
            ReflectionHelpers.setField(controller, "gatt", gatt)
            callback.onMtuChanged(gatt, 517, BluetoothGatt.GATT_SUCCESS)
        }

        fun input() = callback.onCharacteristicChanged(gatt, characteristic, ByteArray(45))

        fun answer(status: Int = BluetoothGatt.GATT_SUCCESS) {
            val op = shadow.pending
            shadow.pending = null
            when (op) {
                is BluetoothGattDescriptor -> callback.onDescriptorWrite(gatt, op, status)
                is BluetoothGattCharacteristic -> callback.onCharacteristicWrite(gatt, op, status)
                else -> fail("no write is waiting for a reply")
            }
        }
    }

    private fun subscribe(target: SteamControllerBle) {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:55")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val characteristic = BluetoothGattCharacteristic(INPUT, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val descriptor = BluetoothGattDescriptor(CCCD, 0)
        characteristic.addDescriptor(descriptor)
        ReflectionHelpers.setField(target, "gatt", gatt)
        ReflectionHelpers.setField(target, "input", INPUT)
        ReflectionHelpers.setField(target, "reportId", 0x45)
        ReflectionHelpers.getField<BluetoothGattCallback>(target, "callback")
            .onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
    }

    private fun state(bytes: ByteArray, target: SteamControllerBle = controller) = ReflectionHelpers.callInstanceMethod<Unit>(target, "onState",
        ReflectionHelpers.ClassParameter.from(ByteArray::class.java, bytes))

    private companion object {
        val SERVICE: UUID = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
        val INPUT: UUID = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
        val REPORT: UUID = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
        val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
    }

    @Implements(BluetoothGatt::class)
    class BusyGatt : ShadowBluetoothGatt() {
        var pending: Any? = null
        var descriptorWrites = 0
        var lizardWrites = 0
        var refused = 0

        @Implementation
        override fun writeDescriptor(descriptor: BluetoothGattDescriptor): Boolean {
            if (!accept(descriptor)) return false
            descriptorWrites++
            return true
        }

        @Implementation
        override fun writeCharacteristic(characteristic: BluetoothGattCharacteristic): Boolean {
            if (!accept(characteristic)) return false
            @Suppress("DEPRECATION")
            val value = characteristic.value
            if (characteristic.uuid == REPORT && value[0] == 0x87.toByte() && value[2] == 9.toByte()) lizardWrites++
            return true
        }

        private fun accept(op: Any): Boolean {
            if (pending != null) {
                refused++
                return false
            }
            pending = op
            return true
        }
    }

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
