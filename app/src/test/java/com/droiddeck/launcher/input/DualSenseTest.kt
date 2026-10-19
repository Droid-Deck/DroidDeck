package com.droiddeck.launcher.input

import android.content.Context
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
import java.nio.ByteBuffer
import java.nio.ByteOrder

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [DualSenseTest.RecordingWriter::class]
)
class DualSenseTest {
    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "test-dualsense-${System.nanoTime()}").apply { mkdirs() }
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

    private fun inputDevice(pid: Int, vid: Int = DualSense.VENDOR_SONY, name: String = "DualSense", id: Int = 10, isVirtual: Boolean = false): InputDevice =
        InputDeviceBuilder.newBuilder()
            .setId(if (isVirtual) -1 else id)
            .setName(name)
            .setDescriptor("test-$id")
            .setVendorId(vid)
            .setProductId(pid)
            .setSources(InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_JOYSTICK)
            .setKeyboardType(InputDevice.KEYBOARD_TYPE_NONE)
            .build()

    // -------------------------------------------------------------------------
    // 1. Controller Detection Tests (VID/PID across UsbDevice & InputDevice)
    // -------------------------------------------------------------------------

    @Test
    fun testVidPidDetection() {
        assertTrue(DualSense.isDualSense(DualSense.VENDOR_SONY, DualSense.PRODUCT_DUALSENSE))
        assertTrue(DualSense.isDualSense(DualSense.VENDOR_SONY, DualSense.PRODUCT_DUALSENSE_EDGE))
        assertTrue(DualSense.isEdge(DualSense.PRODUCT_DUALSENSE_EDGE))
        assertFalse(DualSense.isEdge(DualSense.PRODUCT_DUALSENSE))

        // Negative cases
        assertFalse(DualSense.isDualSense(0x045E, 0x028E)) // Xbox 360
        assertFalse(DualSense.isDualSense(0x054C, 0x05C4)) // DualShock 4
        assertFalse(DualSense.isDualSense(0x054C, 0x09CC)) // DualShock 4 v2
        assertFalse(DualSense.isDualSense(0x057E, 0x2006)) // Joy-Con (L)
        assertFalse(DualSense.isDualSense(0x057E, 0x2007)) // Joy-Con (R)
    }

    @Test
    fun testInputDeviceDetection() {
        val dualSense = inputDevice(DualSense.PRODUCT_DUALSENSE)
        val edge = inputDevice(DualSense.PRODUCT_DUALSENSE_EDGE)
        val xbox = inputDevice(0x028E, vid = 0x045E)
        val virtual = inputDevice(DualSense.PRODUCT_DUALSENSE, isVirtual = true)

        assertTrue(DualSense.isDualSense(dualSense))
        assertTrue(DualSense.isDualSense(edge))
        assertTrue(DualSense.isEdge(edge))
        assertFalse(DualSense.isEdge(dualSense))

        assertFalse(DualSense.isDualSense(xbox))
        assertFalse(DualSense.isDualSense(virtual))
        assertFalse(DualSense.isDualSense(null as InputDevice?))
        assertFalse(DualSense.isEdge(null as InputDevice?))
    }

    @Test
    fun testUsbDeviceDetection() {
        val dualSense = usbDevice(DualSense.PRODUCT_DUALSENSE)
        val edge = usbDevice(DualSense.PRODUCT_DUALSENSE_EDGE)
        val xbox = usbDevice(0x028E, vid = 0x045E)

        assertTrue(DualSense.isDualSense(dualSense))
        assertTrue(DualSense.isDualSense(edge))
        assertTrue(DualSense.isEdge(edge))
        assertFalse(DualSense.isEdge(dualSense))

        assertFalse(DualSense.isDualSense(xbox))
        assertFalse(DualSense.isDualSense(null as UsbDevice?))
        assertFalse(DualSense.isEdge(null as UsbDevice?))
    }

    // -------------------------------------------------------------------------
    // 2. 64-Byte USB HID Report 0x01 Parsing
    // -------------------------------------------------------------------------

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
        // Touch inactive (bit 7 set)
        r[33] = 0x80.toByte()
        r[37] = 0x80.toByte()
        return r
    }

    @Test
    fun testInvalidReportRejection() {
        val tooShort = ByteArray(63)
        tooShort[0] = DualSense.REPORT_ID_INPUT
        assertNull(DualSense.parseInputReport(tooShort))

        val wrongId = createNeutralReport()
        wrongId[0] = 0x02 // Output report ID
        assertNull(DualSense.parseInputReport(wrongId))
    }

    @Test
    fun testNeutralReportParsing() {
        val report = createNeutralReport()
        val state = DualSense.parseInputReport(report)
        assertNotNull(state)
        state!!

        assertEquals(0.0f, state.leftX, 0.01f)
        assertEquals(0.0f, state.leftY, 0.01f)
        assertEquals(0.0f, state.rightX, 0.01f)
        assertEquals(0.0f, state.rightY, 0.01f)
        assertEquals(0.0f, state.leftTrigger, 0.001f)
        assertEquals(0.0f, state.rightTrigger, 0.001f)

        assertFalse(state.dpadUp)
        assertFalse(state.dpadRight)
        assertFalse(state.dpadDown)
        assertFalse(state.dpadLeft)

        assertFalse(state.square)
        assertFalse(state.cross)
        assertFalse(state.circle)
        assertFalse(state.triangle)

        assertFalse(state.l1)
        assertFalse(state.r1)
        assertFalse(state.l2Click)
        assertFalse(state.r2Click)
        assertFalse(state.create)
        assertFalse(state.options)
        assertFalse(state.l3)
        assertFalse(state.r3)

        assertFalse(state.ps)
        assertFalse(state.touchpadClick)
        assertFalse(state.mute)
        assertFalse(state.l4)
        assertFalse(state.r4)
        assertFalse(state.l5)
        assertFalse(state.r5)

        assertEquals(0, state.gyroX.toInt())
        assertEquals(0, state.gyroY.toInt())
        assertEquals(0, state.gyroZ.toInt())
        assertEquals(0, state.accelX.toInt())
        assertEquals(0, state.accelY.toInt())
        assertEquals(0, state.accelZ.toInt())

        assertFalse(state.touch0Down)
        assertFalse(state.touch1Down)
    }

    @Test
    fun testAnalogSticksAndTriggers() {
        val report = createNeutralReport()
        // Full extremes
        report[1] = 0 // LX min
        report[2] = 255.toByte() // LY max
        report[3] = 255.toByte() // RX max
        report[4] = 0 // RY min
        report[5] = 255.toByte() // L2 full
        report[6] = 128.toByte() // R2 half

        val state = DualSense.parseInputReport(report)!!
        assertEquals(-1.0f, state.leftX, 0.01f)
        assertEquals(1.0f, state.leftY, 0.01f)
        assertEquals(1.0f, state.rightX, 0.01f)
        assertEquals(-1.0f, state.rightY, 0.01f)
        assertEquals(1.0f, state.leftTrigger, 0.01f)
        assertEquals(128f / 255f, state.rightTrigger, 0.01f)
    }

    @Test
    fun testDpadHatSwitchDirections() {
        val expected = listOf(
            // hat, up, right, down, left
            0 to listOf(true, false, false, false),  // N
            1 to listOf(true, true, false, false),   // NE
            2 to listOf(false, true, false, false),  // E
            3 to listOf(false, true, true, false),   // SE
            4 to listOf(false, false, true, false),  // S
            5 to listOf(false, false, true, true),   // SW
            6 to listOf(false, false, false, true),  // W
            7 to listOf(true, false, false, true),   // NW
            8 to listOf(false, false, false, false)  // Released
        )

        for ((hat, dirs) in expected) {
            val report = createNeutralReport()
            report[8] = hat.toByte()
            val state = DualSense.parseInputReport(report)!!
            assertEquals("Hat $hat Up mismatch", dirs[0], state.dpadUp)
            assertEquals("Hat $hat Right mismatch", dirs[1], state.dpadRight)
            assertEquals("Hat $hat Down mismatch", dirs[2], state.dpadDown)
            assertEquals("Hat $hat Left mismatch", dirs[3], state.dpadLeft)
        }
    }

    @Test
    fun testButtonsParsing() {
        val report = createNeutralReport()
        // Face buttons (Square 0x10, Cross 0x20, Circle 0x40, Triangle 0x80)
        report[8] = (0x08 or 0x10 or 0x20 or 0x40 or 0x80).toByte()
        // Shoulders, thumb, menu
        report[9] = (0x01 or 0x02 or 0x04 or 0x08 or 0x10 or 0x20 or 0x40 or 0x80).toByte()
        // PS, Click, Mute, Edge paddles (L5 0x10, R5 0x20, L4 0x40, R4 0x80)
        report[10] = (0x01 or 0x02 or 0x04 or 0x10 or 0x20 or 0x40 or 0x80).toByte()

        val state = DualSense.parseInputReport(report)!!
        assertTrue(state.square)
        assertTrue(state.cross)
        assertTrue(state.circle)
        assertTrue(state.triangle)

        assertTrue(state.l1)
        assertTrue(state.r1)
        assertTrue(state.l2Click)
        assertTrue(state.r2Click)
        assertTrue(state.create)
        assertTrue(state.options)
        assertTrue(state.l3)
        assertTrue(state.r3)

        assertTrue(state.ps)
        assertTrue(state.touchpadClick)
        assertTrue(state.mute)
        assertTrue(state.l5)
        assertTrue(state.r5)
        assertTrue(state.l4)
        assertTrue(state.r4)
    }

    @Test
    fun testImuParsing() {
        val report = createNeutralReport()
        val bb = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(16, 1234.toShort())   // GyroX
        bb.putShort(18, (-2345).toShort()) // GyroY
        bb.putShort(20, 3456.toShort())   // GyroZ
        bb.putShort(22, (-4567).toShort()) // AccelX
        bb.putShort(24, 5678.toShort())   // AccelY
        bb.putShort(26, (-6789).toShort()) // AccelZ

        val state = DualSense.parseInputReport(report)!!
        assertEquals(1234.toShort(), state.gyroX)
        assertEquals((-2345).toShort(), state.gyroY)
        assertEquals(3456.toShort(), state.gyroZ)
        assertEquals((-4567).toShort(), state.accelX)
        assertEquals(5678.toShort(), state.accelY)
        assertEquals((-6789).toShort(), state.accelZ)
    }

    @Test
    fun testTouchpadParsing() {
        val report = createNeutralReport()
        // Finger 0: ID 5, Down (bit 7 = 0), X = 1200 (0x4B0), Y = 800 (0x320)
        report[33] = 5
        report[34] = 0xB0.toByte()
        report[35] = 0x04
        report[36] = 0x32

        // Finger 1: ID 8, Inactive (bit 7 = 1), X = 1919 (0x77F), Y = 1079 (0x437)
        report[37] = 0x88.toByte()
        report[38] = 0x7F
        report[39] = 0x77
        report[40] = 0x43

        val state = DualSense.parseInputReport(report)!!
        assertTrue(state.touch0Down)
        assertEquals(5, state.touch0Id)
        assertEquals(1200, state.touch0X)
        assertEquals(800, state.touch0Y)

        assertFalse(state.touch1Down)
        assertEquals(8, state.touch1Id)
        assertEquals(1919, state.touch1X)
        assertEquals(1079, state.touch1Y)
    }

    // -------------------------------------------------------------------------
    // 3. Coordinate Transformers Math
    // -------------------------------------------------------------------------

    @Test
    fun testCoordinateTransformers() {
        // Boundaries
        assertEquals(Short.MIN_VALUE, DualSense.touchXToPad(0))
        assertEquals(Short.MAX_VALUE, DualSense.touchXToPad(1919))
        assertEquals(Short.MAX_VALUE, DualSense.touchYToPad(0))
        assertEquals(Short.MIN_VALUE, DualSense.touchYToPad(1079))

        // Center
        val centerX = DualSense.touchXToPad(960)
        val centerY = DualSense.touchYToPad(540)
        assertTrue(Math.abs(centerX.toInt()) < 200)
        assertTrue(Math.abs(centerY.toInt()) < 200)

        // Inverse roundtrip
        for (x in listOf(0, 100, 500, 960, 1500, 1919)) {
            val padX = DualSense.touchXToPad(x)
            val recoveredX = DualSense.padToTouchX(padX)
            assertTrue("Roundtrip X error at $x: expected near $x, got $recoveredX", Math.abs(x - recoveredX) <= 1)
        }

        for (y in listOf(0, 100, 540, 900, 1079)) {
            val padY = DualSense.touchYToPad(y)
            val recoveredY = DualSense.padToTouchY(padY)
            assertTrue("Roundtrip Y error at $y: expected near $y, got $recoveredY", Math.abs(y - recoveredY) <= 1)
        }
    }

    // -------------------------------------------------------------------------
    // 4. DeckControls & FakeInputWriter Methods
    // -------------------------------------------------------------------------

    @Test
    fun testDeckControlsMethods() {
        assertFalse(DeckControls.externalImuActive)
        DeckControls.externalImuActive = true
        assertTrue(DeckControls.externalImuActive)

        // setDualSenseTouch
        DeckControls.setDualSenseTouch(
            x0 = -1000, y0 = 2000, down0 = true,
            x1 = 3000, y1 = -4000, down1 = true,
            click = true
        )

        // setDualSenseExtras
        DeckControls.setDualSenseExtras(
            mute = true,
            l4 = true,
            r4 = false,
            l5 = false,
            r5 = true
        )

        DeckControls.releaseAll()
    }

    @Test
    fun testFakeInputWriterConvenienceMethods() {
        val accel = shortArrayOf(10, 20, 30)
        val gyro = shortArrayOf(40, 50, 60)
        FakeInputWriter.setDeckIMU(0, accel, gyro)

        assertEquals(0, RecordingWriter.lastMotionSlot)
        assertEquals(10.toShort(), RecordingWriter.lastAccel!![0])
        assertEquals(20.toShort(), RecordingWriter.lastAccel!![1])
        assertEquals(30.toShort(), RecordingWriter.lastAccel!![2])
        assertEquals(40.toShort(), RecordingWriter.lastGyro!![0])
        assertEquals(50.toShort(), RecordingWriter.lastGyro!![1])
        assertEquals(60.toShort(), RecordingWriter.lastGyro!![2])

        val pads = shortArrayOf(100, 200, 300, 400)
        FakeInputWriter.setDeckTouch(0, pads, DeckControls.FULL_TOUCHPAD)
        assertEquals(DeckControls.FULL_TOUCHPAD, RecordingWriter.lastControls)
        assertEquals(100.toShort(), RecordingWriter.lastPads!![0])
        assertEquals(200.toShort(), RecordingWriter.lastPads!![1])

        FakeInputWriter.setDeckButtons(0, DeckControls.MUTE or DeckControls.L4)
        assertEquals(DeckControls.MUTE or DeckControls.L4, RecordingWriter.lastControls)
    }

    // -------------------------------------------------------------------------
    // 5. DualSenseManager Primary Slot Routing & Preference Restoration
    // -------------------------------------------------------------------------

    @Test
    fun testPrimarySlotRoutingAndPreferenceRestoration() {
        // Set user preference to xbox360 initially
        SessionPrefs.setSteamController(context, SessionPrefs.CONTROLLER_XBOX360)
        assertEquals(SessionPrefs.CONTROLLER_XBOX360, SessionPrefs.steamController(context))

        val manager = DualSenseManager(context)
        assertFalse(manager.isDualSenseActive)
        assertNull(manager.cachedPreference)

        // Simulate DualSense connection
        val dualSense = usbDevice(DualSense.PRODUCT_DUALSENSE)
        manager.onUsbDeviceAttached(dualSense)

        assertTrue(manager.isDualSenseActive)
        assertTrue(DeckControls.externalImuActive)
        assertEquals(SessionPrefs.CONTROLLER_XBOX360, manager.cachedPreference)
        assertEquals(FakeInputWriter.PAD_TYPE_DUALSENSE, RecordingWriter.lastPadType)

        // Disconnect DualSense
        manager.onUsbDeviceDetached(dualSense)

        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
        assertNull(manager.cachedPreference)
        assertEquals(FakeInputWriter.PAD_TYPE_XBOX, RecordingWriter.lastPadType)
    }

    @Test
    fun testInputReportPipelineInDualSenseManager() {
        val manager = DualSenseManager(context)
        val report = createNeutralReport()

        // Set stick motion, trigger, buttons, IMU, touch
        report[1] = 64 // LX: negative
        report[5] = 200.toByte() // L2
        report[8] = 0x28 // Cross (0x20) | Hat 8
        report[10] = 0x04 // Mute (0x04)

        // IMU: GyroX = 500, GyroY = 400, GyroZ = 300
        // AccelX = 1000, AccelY = 2000, AccelZ = 3000
        val bb = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(16, 500.toShort())
        bb.putShort(18, 400.toShort())
        bb.putShort(20, 300.toShort())
        bb.putShort(22, 1000.toShort())
        bb.putShort(24, 2000.toShort())
        bb.putShort(26, 3000.toShort())

        manager.onInputReport(report)

        assertTrue(manager.isDualSenseActive)
        assertTrue(DeckControls.externalImuActive)

        // Verify transformed IMU written to FakeInputWriter
        assertEquals(0, RecordingWriter.lastMotionSlot)
        assertNotNull(RecordingWriter.lastGyro)
        assertNotNull(RecordingWriter.lastAccel)
        // Transformation:
        // gyro = [gyroX, -gyroZ, gyroY] -> [500, -300, 400]
        assertEquals(500.toShort(), RecordingWriter.lastGyro!![0])
        assertEquals((-300).toShort(), RecordingWriter.lastGyro!![1])
        assertEquals(400.toShort(), RecordingWriter.lastGyro!![2])

        // accel = [accelX * 2, -accelZ * 2, accelY * 2] -> [2000, -6000, 4000]
        assertEquals(2000.toShort(), RecordingWriter.lastAccel!![0])
        assertEquals((-6000).toShort(), RecordingWriter.lastAccel!![1])
        assertEquals(4000.toShort(), RecordingWriter.lastAccel!![2])

        // Clean up
        manager.stop()
        assertFalse(manager.isDualSenseActive)
        assertFalse(DeckControls.externalImuActive)
    }

    // -------------------------------------------------------------------------
    // 6. PadMotion Suppression when external IMU is active
    // -------------------------------------------------------------------------

    @Test
    fun testPadMotionSuppression() {
        DeckControls.externalImuActive = false
        val padMotion = PadMotion(context) { android.view.Surface.ROTATION_0 }

        // Start PadMotion without external IMU
        padMotion.start()

        // Activate external IMU
        DeckControls.externalImuActive = true

        // Stopping PadMotion cleanly
        padMotion.stop()
        assertTrue(DeckControls.externalImuActive)
    }

    // -------------------------------------------------------------------------
    // 7. Bluetooth Input & Output Report Tests
    // -------------------------------------------------------------------------

    @Test
    fun testBluetoothInputReportParsing() {
        val bt = ByteArray(DualSense.REPORT_SIZE_BT_INPUT)
        bt[0] = DualSense.REPORT_ID_BT_INPUT
        bt[1] = 0x01 // Bit 0 must be 1
        bt[2] = 128.toByte() // LX
        bt[3] = 128.toByte() // LY
        bt[4] = 128.toByte() // RX
        bt[5] = 128.toByte() // RY
        bt[9] = 0x08 // Hat None
        bt[34] = 0x80.toByte() // Touch0 inactive
        bt[38] = 0x80.toByte() // Touch1 inactive

        // Without CRC (all zeros), parser accepts
        val stateNoCrc = DualSense.parseInputReport(bt)
        assertNotNull(stateNoCrc)

        // Calculate CRC32 with 0xA1 seed
        val crc = java.util.zip.CRC32()
        crc.update(0xA1)
        crc.update(bt, 0, 74)
        val crcVal = crc.value
        bt[74] = (crcVal and 0xFF).toByte()
        bt[75] = ((crcVal shr 8) and 0xFF).toByte()
        bt[76] = ((crcVal shr 16) and 0xFF).toByte()
        bt[77] = ((crcVal shr 24) and 0xFF).toByte()

        val stateWithCrc = DualSense.parseInputReport(bt)
        assertNotNull(stateWithCrc)

        // Corrupt CRC
        bt[74] = (bt[74].toInt() xor 0xFF).toByte()
        assertNull(DualSense.parseInputReport(bt))

        // Corrupt report header (bit 0 not set)
        bt[74] = (crcVal and 0xFF).toByte() // restore CRC
        bt[1] = 0x00
        assertNull(DualSense.parseInputReport(bt))
    }

    @Test
    fun testOutputReportBuilding() {
        // USB Standard
        val usbReport = DualSense.buildOutputReport(
            leftMotor = 100,
            rightMotor = 200,
            r = 255,
            g = 128,
            b = 64,
            micLed = 1,
            playerLeds = 4,
            isEdge = false
        )
        assertEquals(DualSense.REPORT_ID_OUTPUT, usbReport[0])
        assertEquals(200.toByte(), usbReport[3]) // right motor
        assertEquals(100.toByte(), usbReport[4]) // left motor
        assertEquals(1.toByte(), usbReport[9])   // mic led
        assertEquals(0x07.toByte(), usbReport[39]) // standard USB[39]
        assertEquals(0x00.toByte(), usbReport[41]) // standard USB[41]
        assertEquals(4.toByte(), usbReport[44])  // player leds
        assertEquals(255.toByte(), usbReport[45]) // r
        assertEquals(128.toByte(), usbReport[46]) // g
        assertEquals(64.toByte(), usbReport[47])  // b

        // USB Edge
        val edgeReport = DualSense.buildOutputReport(
            r = 0, g = 255, b = 0,
            isEdge = true
        )
        assertEquals(0xC7.toByte(), edgeReport[39]) // Edge unlock
        assertEquals(0x80.toByte(), edgeReport[41]) // Edge unlock

        // Bluetooth Output Report with CRC32
        val btReport = DualSense.buildBluetoothOutputReport(usbReport, seq = 3, isEdge = true)
        assertEquals(DualSense.REPORT_ID_BT_OUTPUT, btReport[0])
        assertEquals((0x02 or (3 shl 4)).toByte(), btReport[1])
        assertEquals(0xC7.toByte(), btReport[40]) // Edge unlock in BT
        assertEquals(0x80.toByte(), btReport[42]) // Edge unlock in BT
        assertEquals(0x03.toByte(), btReport[49]) // Edge unlock in BT

        // Verify BT CRC32
        val btCrc = java.util.zip.CRC32()
        btCrc.update(0xA2)
        btCrc.update(btReport, 0, 74)
        val expectedCrc = btCrc.value
        val actualCrc = (btReport[74].toLong() and 0xFF) or
                ((btReport[75].toLong() and 0xFF) shl 8) or
                ((btReport[76].toLong() and 0xFF) shl 16) or
                ((btReport[77].toLong() and 0xFF) shl 24)
        assertEquals(expectedCrc, actualCrc)
    }

    @Test
    fun testEdgeTuningFlagParsing() {
        val report = createNeutralReport()
        // Default neutral: not tuning
        report[49] = 0x00
        assertFalse(DualSense.parseInputReport(report)!!.isEdgeTuningActive)

        // Tuning active values: 0x0D, 0x0E, 0x0F
        for (v in listOf(0x0D, 0x0E, 0x0F, 0x1D, 0x2E, 0xFD)) {
            report[49] = v.toByte()
            assertTrue("Expected tuning active for $v", DualSense.parseInputReport(report)!!.isEdgeTuningActive)
        }

        // Non-tuning values: 0x00..0x0C
        for (v in 0..12) {
            report[49] = v.toByte()
            assertFalse("Expected non-tuning for $v", DualSense.parseInputReport(report)!!.isEdgeTuningActive)
        }
    }

    // -------------------------------------------------------------------------
    // Shadow FakeInputWriter for Robolectric Unit Testing
    // -------------------------------------------------------------------------

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
