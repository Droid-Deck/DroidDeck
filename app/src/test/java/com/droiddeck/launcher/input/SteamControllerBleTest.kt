package com.droiddeck.launcher.input

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.os.Handler
import com.droiddeck.launcher.input.steamcontroller.Sc2015ReportParser
import com.droiddeck.launcher.input.steamcontroller.Sc2015State
import com.droiddeck.launcher.input.steamcontroller.SteamControllerModel
import com.droiddeck.launcher.input.steamcontroller.SteamControllerUuids
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
import java.io.File
import java.util.ArrayDeque
import java.util.UUID
import java.util.concurrent.TimeUnit

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
        Writer.featureReplies.clear()
        Writer.featureRequest = 0L
        Writer.tritonOutput = null
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

    @Test fun recognizesAllSteamControllerBleNames() {
        assertTrue(SteamControllerUuids.isSteamControllerName("Steam Controller"))
        assertTrue(SteamControllerUuids.isSteamControllerName("SteamController"))
        assertTrue(SteamControllerUuids.isSteamControllerName("Steam Ctrl"))
        assertFalse(SteamControllerUuids.isSteamControllerName("Xbox Wireless Controller"))
        assertFalse(SteamControllerUuids.isSteamControllerName(null))
    }

    @Test fun original2015FragmentedNotificationRoutesThroughInputRouterAndTritonState() {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:66")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val input2015 = SteamControllerUuids.INPUT_2015
        val characteristic = BluetoothGattCharacteristic(input2015, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val descriptor = BluetoothGattDescriptor(SteamControllerUuids.CCCD, 0)
        characteristic.addDescriptor(descriptor)
        ReflectionHelpers.setField(controller, "gatt", gatt)
        ReflectionHelpers.setField(controller, "input", input2015)
        ReflectionHelpers.setField(controller, "reportId", 0x45)
        ReflectionHelpers.setField(controller, "model", SteamControllerModel.ORIGINAL_2015)

        val callback = ReflectionHelpers.getField<BluetoothGattCallback>(controller, "callback")
        callback.onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
        assertEquals(SteamControllerModel.ORIGINAL_2015, SteamControllerBle.activeModel)

        val frag0 = byteArrayOf(0x80.toByte(), 0x14, 0x00, 0x80.toByte())
        val frag1 = byteArrayOf(0xC1.toByte(), 0x00, 0x00)
        callback.onCharacteristicChanged(gatt, characteristic, frag0)
        assertTrue(keys.isEmpty())
        callback.onCharacteristicChanged(gatt, characteristic, frag1)
        assertEquals(listOf(57 to true), keys)
        assertEquals(45, Writer.reports.last().size)
    }

    @Test fun fwTimestampPrefersNewerShippedValueAndFallsBack() {
        val cfg = File(RuntimeEnvironment.getApplication().filesDir, "linuxfs/root/.local/share/Steam/bin/hardwareupdater/hardwareupdater.cfg")
        cfg.parentFile!!.mkdirs()
        cfg.writeText("TRITON_FW_TS: 0x7FFFFFFF\n")
        assertEquals(0x7FFFFFFF, ReflectionHelpers.callInstanceMethod<Int>(controller, "resolveFwTimestamp"))
        cfg.writeText("TRITON_FW_TS: 0x00000001\n")
        assertEquals(Sc2015ReportParser.TRITON_FW_TIMESTAMP, ReflectionHelpers.callInstanceMethod<Int>(controller, "resolveFwTimestamp"))
        cfg.delete()
        assertEquals(Sc2015ReportParser.TRITON_FW_TIMESTAMP, ReflectionHelpers.callInstanceMethod<Int>(controller, "resolveFwTimestamp"))
    }

    @Test fun original2015SuppressesRumbleAndCapturesImuAndFeatureCommand() {
        ReflectionHelpers.setField(controller, "model", SteamControllerModel.ORIGINAL_2015)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "sendOutputReport",
            ReflectionHelpers.ClassParameter.from(Boolean::class.java, false),
            ReflectionHelpers.ClassParameter.from(ByteArray::class.java, byteArrayOf(0x80.toByte(), 0x01, 0x02)))
        assertTrue(ReflectionHelpers.getField<ArrayDeque<*>>(controller, "queuedWrites").isEmpty())
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "sendOutputReport",
            ReflectionHelpers.ClassParameter.from(Boolean::class.java, true),
            ReflectionHelpers.ClassParameter.from(ByteArray::class.java, byteArrayOf(0x00, 0x87.toByte(), 0x06, 0x08, 0x07, 0x00, 0x30, 0x18, 0x00)))
        assertEquals(0x87, ReflectionHelpers.getField<Int>(controller, "pendingFeatureCmd2015"))
        assertArrayEquals(byteArrayOf(0xC0.toByte(), 0x87.toByte(), 0x03, 0x30, 0x18, 0x00),
            ReflectionHelpers.getField<ByteArray>(controller, "lastImuModeCmd2015"))
        assertFalse(ReflectionHelpers.getField<ArrayDeque<*>>(controller, "queuedWrites").isEmpty())
    }

    @Test fun original2015AnswersFeatureRequestSynthetically() {
        ReflectionHelpers.setField(controller, "pendingFeatureCmd2015", 0x83)
        Writer.featureRequest = 42L
        connect2015()
        assertEquals(1, Writer.featureReplies.size)
        assertEquals(42L, Writer.featureReplies[0].first)
        val reply = Writer.featureReplies[0].second!!
        assertEquals(62, reply.size)
        assertEquals(0x83, reply[0].toInt() and 0xFF)
    }

    @Test fun subscribeSelects2015AndSubscribesWithNotification() {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:66")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val service = BluetoothGattService(SteamControllerUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val input = BluetoothGattCharacteristic(SteamControllerUuids.INPUT_2015, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val cccd = BluetoothGattDescriptor(SteamControllerUuids.CCCD, 0)
        input.addDescriptor(cccd)
        service.addCharacteristic(input)
        shadowOf(gatt).addDiscoverableService(service)
        shadowOf(gatt).setGattCallback(object : BluetoothGattCallback() {})
        gatt.discoverServices()
        ReflectionHelpers.setField(controller, "gatt", gatt)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "subscribe",
            ReflectionHelpers.ClassParameter.from(BluetoothGatt::class.java, gatt))
        assertEquals(SteamControllerModel.ORIGINAL_2015, ReflectionHelpers.getField<SteamControllerModel>(controller, "model"))
        assertEquals(0x45, ReflectionHelpers.getField<Int>(controller, "reportId"))
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE, cccd.value)
    }

    @Test fun subscribePrefersTritonOver2015AndUsesIndicationWithoutNotify() {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:66")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val service = BluetoothGattService(SteamControllerUuids.SERVICE, BluetoothGattService.SERVICE_TYPE_PRIMARY)
        val triton = BluetoothGattCharacteristic(SteamControllerUuids.INPUT_0X45, BluetoothGattCharacteristic.PROPERTY_INDICATE, 0)
        val tritonCccd = BluetoothGattDescriptor(SteamControllerUuids.CCCD, 0)
        triton.addDescriptor(tritonCccd)
        service.addCharacteristic(triton)
        val input2015 = BluetoothGattCharacteristic(SteamControllerUuids.INPUT_2015, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        input2015.addDescriptor(BluetoothGattDescriptor(SteamControllerUuids.CCCD, 0))
        service.addCharacteristic(input2015)
        shadowOf(gatt).addDiscoverableService(service)
        shadowOf(gatt).setGattCallback(object : BluetoothGattCallback() {})
        gatt.discoverServices()
        ReflectionHelpers.setField(controller, "gatt", gatt)
        ReflectionHelpers.callInstanceMethod<Unit>(controller, "subscribe",
            ReflectionHelpers.ClassParameter.from(BluetoothGatt::class.java, gatt))
        assertEquals(SteamControllerModel.TRITON_2025, ReflectionHelpers.getField<SteamControllerModel>(controller, "model"))
        assertEquals(0x45, ReflectionHelpers.getField<Int>(controller, "reportId"))
        assertArrayEquals(BluetoothGattDescriptor.ENABLE_INDICATION_VALUE, tritonCccd.value)
    }

    @Test fun keepAliveExpiresStationaryPadTouchAndRepublishesCentered() {
        connect2015()
        val state2015 = ReflectionHelpers.getField<Sc2015State>(controller, "state2015")
        state2015.buttons = Sc2015ReportParser.BUTTON_RPAD_TOUCH
        state2015.rightPadX = 20000
        state2015.rightPadY = -20000
        state2015.rightPadSeenMs = 0L
        val reportsBefore = Writer.reports.size
        shadowOf(handler.looper).idleFor(400, TimeUnit.MILLISECONDS)
        assertEquals(0, state2015.buttons and Sc2015ReportParser.BUTTON_RPAD_TOUCH)
        assertTrue(Writer.reports.size > reportsBefore)
        val last = Writer.reports.last()
        assertEquals(0, (last[23].toInt() and 0xFF) or ((last[24].toInt() and 0xFF) shl 8))
    }

    private fun connect2015() {
        val device = BluetoothAdapter.getDefaultAdapter().getRemoteDevice("00:11:22:33:44:66")
        val gatt = ShadowBluetoothGatt.newInstance(device)
        val characteristic = BluetoothGattCharacteristic(SteamControllerUuids.INPUT_2015, BluetoothGattCharacteristic.PROPERTY_NOTIFY, 0)
        val descriptor = BluetoothGattDescriptor(SteamControllerUuids.CCCD, 0)
        characteristic.addDescriptor(descriptor)
        ReflectionHelpers.setField(controller, "gatt", gatt)
        ReflectionHelpers.setField(controller, "input", SteamControllerUuids.INPUT_2015)
        ReflectionHelpers.setField(controller, "reportId", 0x45)
        ReflectionHelpers.setField(controller, "model", SteamControllerModel.ORIGINAL_2015)
        ReflectionHelpers.setField(controller, "running", true)
        ReflectionHelpers.getField<BluetoothGattCallback>(controller, "callback")
            .onDescriptorWrite(gatt, descriptor, BluetoothGatt.GATT_SUCCESS)
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
            val featureReplies = ArrayList<Pair<Long, ByteArray?>>()
            var features = 0
            var featureRequest = 0L
            var tritonOutput: ByteArray? = null
            @JvmStatic @Implementation fun writeTritonState(slot: Int, reportId: Int, report: ByteArray) { reports += report.copyOf() }
            @JvmStatic @Implementation fun setTritonConnected(slot: Int, connected: Boolean) { connections += connected }
            @JvmStatic @Implementation fun writeTritonFeatureReply(slot: Int, request: Long, report: ByteArray?) { features++; featureReplies += request to report?.copyOf() }
            @JvmStatic @Implementation fun readTritonFeatureRequest(slot: Int): Long = featureRequest
            @JvmStatic @Implementation fun readTritonOutput(slot: Int, lastSequence: Long): ByteArray? = tritonOutput
        }
    }
}
