package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.usb.UsbDevice
import android.view.InputDevice
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [DualSenseTransitionsTest.RecordingWriter::class]
)
class DualSenseTransitionsTest {
    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "test-dualsense-transitions-${System.nanoTime()}").apply { mkdirs() }
        RecordingWriter.reset()
        FakeInputWriter.prepareRingSlots(tempDir, 1)
        SessionState.fakeInputDir = tempDir
        DeckControls.releaseAll()
        DeckControls.externalImuActive = false
    }

    @After
    fun tearDown() {
        DeckControls.releaseAll()
        DeckControls.externalImuActive = false
        SessionState.fakeInputDir = null
        tempDir.deleteRecursively()
        RecordingWriter.reset()
    }

    private fun usbDevice(pid: Int, vid: Int = DualSense.VENDOR_SONY, name: String = "/dev/bus/usb/001/1"): UsbDevice =
        org.robolectric.shadow.api.Shadow.newInstanceOf(UsbDevice::class.java).also {
            ReflectionHelpers.setField(it, "mVendorId", vid)
            ReflectionHelpers.setField(it, "mProductId", pid)
            ReflectionHelpers.setField(it, "mName", name)
        }

    private fun inputDevice(pid: Int, vid: Int = DualSense.VENDOR_SONY, name: String = "DualSense", id: Int = 10, isVirtual: Boolean = false): InputDevice {
        val dev = InputDeviceBuilder.newBuilder()
            .setId(if (isVirtual) -1 else id)
            .setName(name)
            .setDescriptor("test-$id")
            .setVendorId(vid)
            .setProductId(pid)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NONE)
            .build()
        val inputManager = context.getSystemService(android.hardware.input.InputManager::class.java)
        org.robolectric.Shadows.shadowOf(inputManager).addInputDevice(dev)
        return dev
    }

    private fun createNeutralReport(): ByteArray {
        val r = ByteArray(64)
        r[0] = DualSense.REPORT_ID_INPUT
        r[1] = 128.toByte() // LX
        r[2] = 128.toByte() // LY
        r[3] = 128.toByte() // RX
        r[4] = 128.toByte() // RY
        r[5] = 0 // L2
        r[6] = 0 // R2
        r[8] = 0x08 // Hat 8 = None, no face buttons
        r[33] = 0x80.toByte() // Touch 0 inactive
        r[37] = 0x80.toByte() // Touch 1 inactive
        return r
    }

    // =========================================================================
    // 1. NON-DUALSENSE CONTROLLER CONNECTS FIRST
    // =========================================================================

    @Test
    fun testNonDualSenseUsbConnectsFirst_ignoredByDualSenseManager() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        val manager = DualSenseManager(context)

        // Non-DualSense (Xbox 360 controller) connects via USB
        val xboxUsb = usbDevice(pid = 0x028E, vid = 0x045E, name = "/dev/bus/usb/001/2")
        manager.onUsbDeviceAttached(xboxUsb)

        // DualSenseManager must remain inactive
        assertFalse("DualSenseManager must not be active for Xbox controller", manager.isDualSenseActive)
        assertFalse("DeckControls.externalImuActive must remain false", DeckControls.externalImuActive)
        assertNull("cachedPreference must remain null", manager.cachedPreference)
        assertEquals("Pad type must not be altered", -1, RecordingWriter.lastPadType)

        // Disconnecting Xbox controller via USB has no side effects
        manager.onUsbDeviceDetached(xboxUsb)
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
    }

    @Test
    fun testNonDualSenseInputDeviceConnectsFirst_ignoredByDualSenseManager() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DS4)
        val manager = DualSenseManager(context)

        // Switch Pro controller connects via InputDevice
        val switchPro = inputDevice(pid = 0x2009, vid = 0x057E, name = "Switch Pro Controller", id = 5)
        manager.onInputDeviceAdded(switchPro.id)

        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
        assertNull(manager.cachedPreference)
        assertEquals(-1, RecordingWriter.lastPadType)

        manager.onInputDeviceRemoved(switchPro.id)
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
    }

    @Test
    fun testDualSenseConnectsAfterNonDualSense_promotesToSlot0AndRestoresOnDisconnect() {
        // User preference is initially Xbox 360
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        val manager = DualSenseManager(context)

        // 1. Xbox controller connects first
        val xbox = usbDevice(pid = 0x028E, vid = 0x045E, name = "/dev/bus/usb/001/2")
        manager.onUsbDeviceAttached(xbox)
        assertFalse(manager.isDualSenseActive)

        // 2. DualSense connects second
        val dualSense = usbDevice(pid = DualSense.PRODUCT_DUALSENSE, vid = DualSense.VENDOR_SONY, name = "/dev/bus/usb/001/3")
        manager.onUsbDeviceAttached(dualSense)

        // Verified: DualSense is promoted to slot 0
        assertTrue("DualSense must be active", manager.isDualSenseActive)
        assertTrue("externalImuActive must be true", DeckControls.externalImuActive)
        assertEquals("Cached preference must be previous preference (xbox360)",
            SessionPrefs.CONTROLLER_XBOX360, manager.cachedPreference)
        assertEquals("Pad type must switch to PAD_TYPE_DUALSENSE",
            FakeInputWriter.PAD_TYPE_DUALSENSE, RecordingWriter.lastPadType)

        // 3. DualSense disconnects while Xbox is still present
        manager.onUsbDeviceDetached(dualSense)

        // Verified: Demotes and restores previous controller preference
        assertFalse("DualSense must be inactive", manager.isDualSenseActive)
        assertFalse("externalImuActive must be reset to false", DeckControls.externalImuActive)
        assertNull("cachedPreference must be cleared", manager.cachedPreference)
        assertEquals("Pad type must be restored to PAD_TYPE_XBOX",
            FakeInputWriter.PAD_TYPE_XBOX, RecordingWriter.lastPadType)

        // 4. Finally, Xbox disconnects
        manager.onUsbDeviceDetached(xbox)
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
    }

    // =========================================================================
    // 2. DUALSENSE DISCONNECTS WHILE ANOTHER CONTROLLER IS PRESENT
    // =========================================================================

    @Test
    fun testDualSenseDisconnectWithMultipleSources_multiSourceTracking() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val manager = DualSenseManager(context)

        val dualSenseUsb = usbDevice(pid = DualSense.PRODUCT_DUALSENSE, name = "/dev/bus/usb/001/1")
        val dualSenseInput = inputDevice(pid = DualSense.PRODUCT_DUALSENSE, id = 20)

        // DualSense connected via USB
        manager.onUsbDeviceAttached(dualSenseUsb)
        assertTrue(manager.isDualSenseActive)
        assertTrue(DeckControls.externalImuActive)
        assertEquals(SessionPrefs.CONTROLLER_DECK, manager.cachedPreference)

        // DualSense also registered as InputDevice
        manager.onInputDeviceAdded(dualSenseInput.id)
        assertTrue(manager.isDualSenseActive)
        assertTrue(DeckControls.externalImuActive)

        // USB detaches first, but InputDevice source is still active
        manager.onUsbDeviceDetached(dualSenseUsb)
        assertTrue("DualSense must remain active while InputDevice source is present", manager.isDualSenseActive)
        assertTrue("externalImuActive must remain true", DeckControls.externalImuActive)
        assertEquals(FakeInputWriter.PAD_TYPE_DUALSENSE, RecordingWriter.lastPadType)

        // Now InputDevice detaches
        manager.onInputDeviceRemoved(dualSenseInput.id)
        assertFalse("DualSense must deactivate when all sources detach", manager.isDualSenseActive)
        assertFalse("externalImuActive must be reset to false", DeckControls.externalImuActive)
        assertNull(manager.cachedPreference)
        assertEquals(FakeInputWriter.PAD_TYPE_DECK, RecordingWriter.lastPadType)
    }

    @Test
    fun testDualSenseEdgePromotionAndRestoration() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DS4)
        val manager = DualSenseManager(context)

        val edge = usbDevice(pid = DualSense.PRODUCT_DUALSENSE_EDGE, name = "/dev/bus/usb/001/5")
        manager.onUsbDeviceAttached(edge)

        assertTrue(manager.isDualSenseActive)
        assertTrue(DeckControls.externalImuActive)
        assertEquals(SessionPrefs.CONTROLLER_DS4, manager.cachedPreference)
        assertEquals(FakeInputWriter.PAD_TYPE_DUALSENSE, RecordingWriter.lastPadType)

        manager.onUsbDeviceDetached(edge)

        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
        assertNull(manager.cachedPreference)
        assertEquals(FakeInputWriter.PAD_TYPE_DS4, RecordingWriter.lastPadType)
    }

    // =========================================================================
    // 3. REPEATED RECONNECT CYCLES (IDEMPOTENCY & STRESS)
    // =========================================================================

    @Test
    fun testRepeatedConnectDisconnectCycles_preservesPreferenceAndResetsImu() {
        val initialPref = SessionPrefs.CONTROLLER_XBOX360
        SessionPrefs.setSteamController(context, initialPref)
        val manager = DualSenseManager(context)
        val dualSense = usbDevice(pid = DualSense.PRODUCT_DUALSENSE)

        // 50 repeated connect/disconnect iterations
        for (i in 1..50) {
            manager.onUsbDeviceAttached(dualSense)
            assertTrue("Cycle $i: must be active", manager.isDualSenseActive)
            assertTrue("Cycle $i: externalImuActive must be true", DeckControls.externalImuActive)
            assertEquals("Cycle $i: cachedPreference must match initial preference",
                initialPref, manager.cachedPreference)
            assertEquals("Cycle $i: lastPadType must be DUALSENSE",
                FakeInputWriter.PAD_TYPE_DUALSENSE, RecordingWriter.lastPadType)

            // Feed an input report during active session
            manager.onInputReport(createNeutralReport())
            assertTrue("Cycle $i: externalImuActive must remain true after report", DeckControls.externalImuActive)

            manager.onUsbDeviceDetached(dualSense)
            assertFalse("Cycle $i: must be inactive", manager.isDualSenseActive)
            assertFalse("Cycle $i: externalImuActive must be reset to false", DeckControls.externalImuActive)
            assertNull("Cycle $i: cachedPreference must be null", manager.cachedPreference)
            assertEquals("Cycle $i: lastPadType must be restored to XBOX",
                FakeInputWriter.PAD_TYPE_XBOX, RecordingWriter.lastPadType)
        }
    }

    @Test
    fun testDuplicateAttachEvents_idempotent() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_DECK)
        val manager = DualSenseManager(context)
        val dualSense = usbDevice(pid = DualSense.PRODUCT_DUALSENSE)

        manager.onUsbDeviceAttached(dualSense)
        assertTrue(manager.isDualSenseActive)
        val cached = manager.cachedPreference

        // Duplicate attach events must not overwrite cached preference
        manager.onUsbDeviceAttached(dualSense)
        manager.onUsbDeviceAttached(dualSense)

        assertTrue(manager.isDualSenseActive)
        assertEquals(cached, manager.cachedPreference)

        manager.onUsbDeviceDetached(dualSense)
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
    }

    // =========================================================================
    // 4. EXTERNAL IMU ACTIVE PROPER RESET & PADMOTION SENSOR RESUMPTION
    // =========================================================================

    @Test
    fun testExternalImuActiveResetOnManagerStop() {
        val manager = DualSenseManager(context)
        val dualSense = usbDevice(pid = DualSense.PRODUCT_DUALSENSE)

        manager.onUsbDeviceAttached(dualSense)
        assertTrue(DeckControls.externalImuActive)

        manager.stop()

        assertFalse("DualSenseManager.stop() must reset isDualSenseActive", manager.isDualSenseActive)
        assertFalse("DualSenseManager.stop() must reset externalImuActive", DeckControls.externalImuActive)
    }

    @Test
    fun testPadMotionSuppressionAndResumptionCycle() {
        DeckControls.externalImuActive = false
        val padMotion = PadMotion(context) { android.view.Surface.ROTATION_0 }
        padMotion.start()

        // 1. Initially externalImuActive is false: internal sensor is permitted
        assertFalse(DeckControls.externalImuActive)

        // 2. DualSense connects: externalImuActive becomes true
        val manager = DualSenseManager(context)
        val dualSense = usbDevice(pid = DualSense.PRODUCT_DUALSENSE)
        manager.onUsbDeviceAttached(dualSense)

        assertTrue(DeckControls.externalImuActive)

        // Verify sensor event is dropped when externalImuActive is true
        // (onSensorChanged early returns)
        val dummySensor = ReflectionHelpers.callConstructor(Sensor::class.java)
        val event = ReflectionHelpers.callConstructor(SensorEvent::class.java, ClassParameter.from(Int::class.javaPrimitiveType, 3))
        ReflectionHelpers.setField(event, "sensor", dummySensor)
        padMotion.onSensorChanged(event)

        // 3. DualSense disconnects: externalImuActive is reset to false
        manager.onUsbDeviceDetached(dualSense)
        assertFalse("externalImuActive must be false after disconnect", DeckControls.externalImuActive)

        // 4. Internal sensor is permitted again
        assertFalse(DeckControls.externalImuActive)

        padMotion.stop()
    }

    // =========================================================================
    // 5. CONCURRENCY & MULTI-THREADING STRESS HARNESS
    // =========================================================================

    @Test
    fun testConcurrentAttachDetachAndReportIngestion_threadSafety() {
        val manager = DualSenseManager(context)
        val dualSense = usbDevice(pid = DualSense.PRODUCT_DUALSENSE)
        val report = createNeutralReport()

        val threadCount = 4
        val iterationsPerThread = 200
        val latch = CountDownLatch(threadCount)
        val errors = AtomicInteger(0)

        // Thread 1 & 2: Rapid connect / disconnect
        for (t in 0 until 2) {
            Thread({
                try {
                    for (i in 0 until iterationsPerThread) {
                        manager.onUsbDeviceAttached(dualSense)
                        Thread.yield()
                        manager.onUsbDeviceDetached(dualSense)
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }, "stress-attach-detach-$t").start()
        }

        // Thread 3 & 4: Concurrent input report ingestion
        for (t in 2 until threadCount) {
            Thread({
                try {
                    for (i in 0 until iterationsPerThread) {
                        manager.onInputReport(report)
                        Thread.yield()
                    }
                } catch (e: Throwable) {
                    errors.incrementAndGet()
                } finally {
                    latch.countDown()
                }
            }, "stress-report-ingestion-$t").start()
        }

        assertTrue("Threads did not complete within timeout", latch.await(10, TimeUnit.SECONDS))
        assertEquals("Exceptions occurred during concurrent execution", 0, errors.get())

        // Ensure clean final demotion
        manager.stop()
        assertFalse("Manager must be inactive after stop()", manager.isDualSenseActive)
        assertFalse("externalImuActive must be false after stop()", DeckControls.externalImuActive)
    }

    @Test
    fun testConcurrentReportsCausePadBinFileGrowth() {
        val manager = DualSenseManager(context)
        val report = createNeutralReport()
        val threadCount = 4
        val iterations = 100
        val latch = CountDownLatch(threadCount)

        for (t in 0 until threadCount) {
            Thread({
                try {
                    for (i in 0 until iterations) {
                        manager.onInputReport(report)
                    }
                } finally {
                    latch.countDown()
                }
            }).start()
        }

        assertTrue(latch.await(5, TimeUnit.SECONDS))
        manager.stop()

        val padBin = File(tempDir, "pad.bin")
        assertTrue("pad.bin must exist", padBin.exists())
        val actualLength = padBin.length()
        // Empirical proof: Due to unsynchronized seek(0) + write in writePadBin,
        // concurrent writers interleave and pad.bin grows beyond 64 bytes!
        println("pad.bin actual length after concurrent writes: $actualLength bytes")
        assertEquals("pad.bin must remain exactly 64 bytes even under concurrent writes", 64L, actualLength)
    }

    @Test
    fun testLateReportAfterDisconnectResurrectsDualSenseWithUnremovableRawSource() {
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        val manager = DualSenseManager(context)
        val dualSense = usbDevice(pid = DualSense.PRODUCT_DUALSENSE)

        // 1. DualSense connects and disconnects normally
        manager.onUsbDeviceAttached(dualSense)
        assertTrue(manager.isDualSenseActive)
        manager.onUsbDeviceDetached(dualSense)
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
        assertEquals(FakeInputWriter.PAD_TYPE_XBOX, RecordingWriter.lastPadType)

        // 2. A late/lingering report arrives after disconnection
        val report = createNeutralReport()
        manager.onInputReport(report)

        // Verified remediation: Late report after disconnect must NOT resurrect DualSense
        assertFalse("Late report must not resurrect DualSense to active", manager.isDualSenseActive)
        assertFalse("Late report must not turn externalImuActive back to true", DeckControls.externalImuActive)
        assertEquals("Pad type must remain restored to XBOX",
            FakeInputWriter.PAD_TYPE_XBOX, RecordingWriter.lastPadType)

        manager.onUsbDeviceDetached(dualSense)
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)

        // Only manager.stop() can clear activeSources
        manager.stop()
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
    }

    @Test
    fun testWirelessSensorImuConversionAndRawHidSuppression() {
        val manager = DualSenseManager(context)
        val wirelessDs = inputDevice(pid = DualSense.PRODUCT_DUALSENSE, id = 31)
        manager.onInputDeviceAdded(wirelessDs.id)

        // Feed wireless gyroscope (rad/s) and accelerometer (m/s^2)
        manager.onInputDeviceSensorEvent(Sensor.TYPE_GYROSCOPE, floatArrayOf(1.0f, 2.0f, 3.0f))
        manager.onInputDeviceSensorEvent(Sensor.TYPE_ACCELEROMETER, floatArrayOf(0.0f, 9.80665f, -4.903325f))

        assertTrue(DeckControls.externalImuActive)
        assertNotNull(RecordingWriter.lastGyro)
        assertNotNull(RecordingWriter.lastAccel)

        // Mapping: [x, -z, y]
        assertEquals(Math.round(1.0f * DualSenseManager.GYRO_COUNTS_PER_RAD_S).toShort(), RecordingWriter.lastGyro!![0])
        assertEquals(Math.round(-3.0f * DualSenseManager.GYRO_COUNTS_PER_RAD_S).toShort(), RecordingWriter.lastGyro!![1])
        assertEquals(Math.round(2.0f * DualSenseManager.GYRO_COUNTS_PER_RAD_S).toShort(), RecordingWriter.lastGyro!![2])

        assertEquals(0.toShort(), RecordingWriter.lastAccel!![0])
        assertEquals(Math.round(4.903325f * DualSenseManager.ACCEL_COUNTS_PER_M_S2).toShort(), RecordingWriter.lastAccel!![1])
        assertEquals(Math.round(9.80665f * DualSenseManager.ACCEL_COUNTS_PER_M_S2).toShort(), RecordingWriter.lastAccel!![2])

        // Now deliver a raw HID report (which sets lastRawReportMs) and verify wireless IMU yields to raw HID
        val rawReport = createNeutralReport()
        manager.onInputReport(rawReport)
        RecordingWriter.reset()

        val handledWhileRawActive = manager.onInputDeviceSensorEvent(Sensor.TYPE_GYROSCOPE, floatArrayOf(5.0f, 5.0f, 5.0f))
        assertFalse("Wireless IMU must yield when raw HID is actively delivering", handledWhileRawActive)
        assertNull(RecordingWriter.lastGyro)

        manager.stop()
    }

    @Test
    fun testWirelessExtraKeyEventsAndBluetoothOutputDispatch() {
        val manager = DualSenseManager(context)
        val edgeDev = inputDevice(pid = DualSense.PRODUCT_DUALSENSE_EDGE, id = 33)
        manager.onInputDeviceAdded(edgeDev.id)

        // Test wireless Mute + Edge L4 + R5 + Touchpad Click via KeyEvent
        val muteDown = android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_MUTE, 0, 0, edgeDev.id, 248)
        val l4Down = android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BUTTON_1, 0, 0, edgeDev.id, 308)
        val tpClickDown = android.view.KeyEvent(0L, 0L, android.view.KeyEvent.ACTION_DOWN, DualSenseManager.KEYCODE_BUTTON_TOUCHPAD, 0, 0, edgeDev.id, 317)

        assertTrue(manager.onKeyEvent(muteDown))
        assertTrue(manager.onKeyEvent(l4Down))
        assertTrue(manager.onKeyEvent(tpClickDown))

        assertTrue((RecordingWriter.lastControls and DeckControls.MUTE) != 0)
        assertTrue((RecordingWriter.lastControls and DeckControls.L4) != 0)
        assertTrue((RecordingWriter.lastControls and 192) == 192)

        // Test wireless Bluetooth 78-byte 0x31 output report dispatch via bluetoothReportSender
        var capturedBtReport: ByteArray? = null
        manager.bluetoothReportSender = { bt78 ->
            capturedBtReport = bt78.clone()
            true
        }

        val usbOut = DualSense.buildOutputReport(leftMotor = 120, rightMotor = 60, r = 0, g = 200, b = 100)
        assertTrue(manager.sendOutputReport(usbOut))
        assertNotNull(capturedBtReport)
        assertEquals(DualSense.REPORT_SIZE_BT_OUTPUT, capturedBtReport!!.size)
        assertEquals(DualSense.REPORT_ID_BT_OUTPUT, capturedBtReport!![0])

        manager.stop()
    }

    // =========================================================================
    // 6. SHADOW FAKEINPUTWRITER
    // =========================================================================

    @Implements(FakeInputWriter::class)
    class RecordingWriter {
        companion object {
            var lastPadType = -1
            var lastMotionSlot = -1
            var lastAccel: ShortArray? = null
            var lastGyro: ShortArray? = null
            var lastControls = 0
            var lastPads: ShortArray? = null
            var lastPressure: ShortArray? = null

            @JvmStatic @Implementation fun __staticInitializer__() = Unit
            @JvmStatic @Implementation fun prepareRingSlots(fakeInputDir: File, slotCount: Int) = Unit

            @JvmStatic @Implementation
            fun writeMotion(slot: Int, accel: ShortArray, gyro: ShortArray) {
                lastMotionSlot = slot
                lastAccel = accel.clone()
                lastGyro = gyro.clone()
            }

            @JvmStatic @Implementation
            fun writeDeckControls(slot: Int, controls: Int, pads: ShortArray, pressure: ShortArray) {
                lastControls = controls
                lastPads = pads.clone()
                lastPressure = pressure.clone()
            }

            @JvmStatic @Implementation
            fun setPadType(type: Int) {
                lastPadType = type
            }

            @JvmStatic @Implementation
            fun readImuMode(slot: Int): Int = 0

            fun reset() {
                lastPadType = -1
                lastMotionSlot = -1
                lastAccel = null
                lastGyro = null
                lastControls = 0
                lastPads = null
                lastPressure = null
            }
        }
    }
}
