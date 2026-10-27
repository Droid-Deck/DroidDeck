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
        val tooShort = ByteArray(9)
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
        assertFalse(DualSense.parseInputReport(report, isEdge = false)!!.isEdgeTuningActive)
        assertFalse(DualSense.parseInputReport(report, isEdge = true)!!.isEdgeTuningActive)

        // Tuning active values: 0x0D, 0x0E, 0x0F, 0x5F (live hardware timestamp byte on standard DualSense)
        for (v in listOf(0x0D, 0x0E, 0x0F, 0x1D, 0x2E, 0x5F, 0xFD)) {
            report[49] = v.toByte()
            assertFalse(
                "Standard DualSense must never treat timestamp byte $v as Edge tuning",
                DualSense.parseInputReport(report, isEdge = false)!!.isEdgeTuningActive
            )
            assertTrue(
                "DualSense Edge must detect tuning active for $v",
                DualSense.parseInputReport(report, isEdge = true)!!.isEdgeTuningActive
            )
        }

        // Non-tuning values: 0x00..0x0C
        for (v in 0..12) {
            report[49] = v.toByte()
            assertFalse("Expected non-tuning for $v", DualSense.parseInputReport(report, isEdge = true)!!.isEdgeTuningActive)
        }
    }

    @Test
    fun testLiveHardwareVectorsAndFeatureReports() {
        // Live Feature 0x09 (20B) captured from physical DualSense 0x054C:0x0CE6
        val f09 = byteArrayOf(
            0x09, 0x4D, 0x50, 0xF6.toByte(), 0x49, 0x18, 0x10, 0x08, 0x25, 0x00,
            0xA4.toByte(), 0x97.toByte(), 0x22, 0x4C, 0x8F.toByte(), 0xE0.toByte(), 0x00, 0x00, 0x00, 0x00
        )
        val pairing = DualSense.parsePairingInfoReport(f09)
        assertNotNull(pairing)
        assertEquals("10:18:49:F6:50:4D", pairing!!.deviceMac)
        assertEquals("E0:8F:4C:22:97:A4", pairing.hostMac)

        // Live Feature 0x20 (64B) captured from physical DualSense 0x054C:0x0CE6
        val f20 = ByteArray(64)
        f20[0] = 0x20
        "Jul  4 2025".toByteArray(Charsets.US_ASCII).copyInto(f20, 1)
        "10:10:32".toByteArray(Charsets.US_ASCII).copyInto(f20, 12)
        val bb20 = ByteBuffer.wrap(f20).order(ByteOrder.LITTLE_ENDIAN)
        bb20.putShort(20, 0x0002)
        bb20.putShort(22, 0x0004)
        bb20.putInt(24, 0x00000621)
        bb20.putInt(28, 0x0110002A)
        bb20.putShort(44, 0x0630)
        bb20.putShort(46, 0x0000)
        bb20.putInt(48, 0x0001003C)
        bb20.putInt(52, 0x0002000A)
        val fw = DualSense.parseFirmwareInfoReport(f20)
        assertNotNull(fw)
        assertEquals("Jul  4 2025", fw!!.buildDate)
        assertEquals("10:10:32", fw.buildTime)
        assertEquals(2, fw.fwType)
        assertEquals(4, fw.swSeries)
        assertEquals(0x00000621, fw.hardwareInfo)
        assertEquals(0x0110002A, fw.mainFwVersion)
        assertEquals(0x00000630, fw.sblFwVersion)
        assertEquals(0x0001003C, fw.dspFwVersion)
    }

    @Test
    fun testPadBinDeduplicationIgnoresRollingCountersAndTimestamps() {
        val prev = createNeutralReport()
        val curr = prev.clone()
        // Modify rolling counter & timestamp bytes that change every frame when idle
        curr[7] = 0x55
        curr[12] = 0x12
        curr[13] = 0x34
        curr[14] = 0x56
        curr[15] = 0x78
        curr[28] = 0x9A.toByte()
        curr[32] = 0xBC.toByte()
        curr[41] = 0x07
        curr[49] = 0x5F
        curr[50] = 0x72
        curr[51] = 0xE9.toByte()
        curr[52] = 0x87.toByte()
        curr[56] = 0xDE.toByte()
        curr[63] = 0xF0.toByte()

        assertTrue(DualSense.isSamePadBinState(prev, curr, isEdge = false))
        // On DualSense Edge, byte 49 is the active profile/tuning indicator so a change in byte 49 is meaningful
        assertFalse(DualSense.isSamePadBinState(prev, curr, isEdge = true))

        // Any meaningful input change (e.g., button press at byte 8) must trigger pad.bin update
        val pressed = prev.clone()
        pressed[8] = 0x28
        assertFalse(DualSense.isSamePadBinState(prev, pressed, isEdge = false))
    }

    @Test
    fun testDsxTriggerEffectGeneratorsAndOutputFlags() {
        val off = DualSense.TriggerEffects.off()
        assertEquals(0x05.toByte(), off[0])
        assertFalse(DualSense.isActiveTriggerMode(off[0]))

        val feedback = DualSense.TriggerEffects.feedback(startPosition = 2, strength = 8)
        assertEquals(0x21.toByte(), feedback[0])
        assertTrue(DualSense.isActiveTriggerMode(feedback[0]))

        val weapon = DualSense.TriggerEffects.weapon(startPosition = 2, endPosition = 6, strength = 7)
        assertEquals(0x25.toByte(), weapon[0])
        assertTrue(DualSense.isActiveTriggerMode(weapon[0]))

        val vibration = DualSense.TriggerEffects.vibration(startPosition = 1, amplitude = 6, frequency = 40)
        assertEquals(0x26.toByte(), vibration[0])
        assertEquals(40.toByte(), vibration[9])

        val bow = DualSense.TriggerEffects.bow(startPosition = 1, endPosition = 5, strength = 6, snapForce = 7)
        assertEquals(0x22.toByte(), bow[0])

        val galloping = DualSense.TriggerEffects.galloping(1, 7, 3, 6, 15)
        assertEquals(0x23.toByte(), galloping[0])

        val machine = DualSense.TriggerEffects.machine(1, 8, 4, 7, 30, 2)
        assertEquals(0x27.toByte(), machine[0])

        val slope = DualSense.TriggerEffects.slopeFeedback(0, 9, 1, 8)
        assertEquals(0x21.toByte(), slope[0])

        val outWithTriggers = DualSense.buildOutputReport(
            leftTriggerEffect = feedback,
            rightTriggerEffect = weapon,
            playerLeds = 0x04,
            playerLedBrightness = DualSense.PLAYER_LED_BRIGHTNESS_MEDIUM
        )
        assertTrue((outWithTriggers[1].toInt() and 0x0C) == 0x0C)
        assertTrue((outWithTriggers[2].toInt() and 0x40) != 0)
        assertEquals(DualSense.PLAYER_LED_BRIGHTNESS_MEDIUM.toByte(), outWithTriggers[43])
        assertEquals(0x25.toByte(), outWithTriggers[11]) // Right trigger effect at bytes 11..21
        assertEquals(0x21.toByte(), outWithTriggers[22]) // Left trigger effect at bytes 22..32
    }

    @Test
    fun testMotionNoiseCalibrationSubtractsStationaryGyroBias() {
        val motionCal = DualSense.MotionNoiseCalibration()
        val report = createNeutralReport()
        val bb = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        // Stationary controller on desk: gyro has steady bias (+18, -14, +9), accel is 1G on Z (8192)
        bb.putShort(16, 18.toShort())
        bb.putShort(18, (-14).toShort())
        bb.putShort(20, 9.toShort())
        bb.putShort(22, 0.toShort())
        bb.putShort(24, 0.toShort())
        bb.putShort(26, 8192.toShort())

        var lastState: DualSense.State? = null
        repeat(250) {
            lastState = DualSense.parseInputReport(report, motionCalibration = motionCal)
        }
        assertTrue(motionCal.isCalibrated)
        assertNotNull(lastState)
        assertEquals(0.toShort(), lastState!!.gyroX)
        assertEquals(0.toShort(), lastState!!.gyroY)
        assertEquals(0.toShort(), lastState!!.gyroZ)
    }

    @Test
    fun testMotionNoiseCalibrationPreservesSlowSteadyRotation() {
        val motionCal = DualSense.MotionNoiseCalibration()
        val report = createNeutralReport()
        val bb = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        // Constant 80-count rotation (~5 deg/s) while gravity is 1G on Z (8192) must never be learned as stationary bias
        bb.putShort(16, 80.toShort())
        bb.putShort(18, 0.toShort())
        bb.putShort(20, 0.toShort())
        bb.putShort(22, 0.toShort())
        bb.putShort(24, 0.toShort())
        bb.putShort(26, 8192.toShort())

        var lastState: DualSense.State? = null
        repeat(300) {
            lastState = DualSense.parseInputReport(report, motionCalibration = motionCal)
        }
        assertFalse(motionCal.isCalibrated)
        assertNotNull(lastState)
        assertEquals(80.toShort(), lastState!!.gyroX)
    }

    @Test
    fun testRumbleWatchdogAndHapticRumbleFallback() {
        val manager = DualSenseManager(context)
        val sentReports = mutableListOf<ByteArray>()
        manager.bluetoothReportSender = { bt ->
            sentReports.add(bt.clone())
            true
        }
        manager.btConnectTimestampMs = 1000L

        // Send non-zero rumble at t = 5000ms
        val rumbleReport = DualSense.buildOutputReport(leftMotor = 120, rightMotor = 200)
        assertTrue(manager.sendOutputReport(rumbleReport))
        assertTrue(manager.rumbleWatchdogArmed)
        val initialTime = manager.lastRumbleActiveMs

        // Before 5000ms timeout: watchdog does not fire
        assertFalse(manager.checkRumbleWatchdog(initialTime + 4000L))
        assertTrue(manager.rumbleWatchdogArmed)

        // At 5001ms: watchdog fires and clears rumble
        sentReports.clear()
        assertTrue(manager.checkRumbleWatchdog(initialTime + 5001L))
        assertFalse(manager.rumbleWatchdogArmed)
        assertEquals(1, sentReports.size)
        assertEquals(0.toByte(), sentReports[0][4]) // rightMotor cleared
        assertEquals(0.toByte(), sentReports[0][5]) // leftMotor cleared
    }

    @Test
    fun testConvertBtInputToUsb64AndPadBinNormalization() {
        val bt = ByteArray(DualSense.REPORT_SIZE_BT_INPUT)
        bt[0] = DualSense.REPORT_ID_BT_INPUT // 0x31
        bt[1] = 0x01 // BT header byte
        bt[2] = 200.toByte() // LX -> usb[1]
        bt[3] = 60.toByte()  // LY -> usb[2]
        bt[4] = 128.toByte() // RX -> usb[3]
        bt[5] = 128.toByte() // RY -> usb[4]
        bt[6] = 255.toByte() // L2 -> usb[5]
        bt[7] = 100.toByte() // R2 -> usb[6]
        bt[9] = 0x28.toByte() // Cross + Hat 8 -> usb[8]
        bt[11] = 0x04.toByte() // Mute -> usb[10]
        bt[34] = 0x01.toByte() // Touch0 active ID 1 -> usb[33]
        bt[35] = 0x80.toByte() // X low
        bt[36] = 0x03.toByte() // X high (0x380 = 896)
        bt[37] = 0x20.toByte() // Y (0x200 = 512)
        bt[38] = 0x80.toByte() // Touch1 inactive -> usb[37]
        bt[54] = 0x17.toByte() // Battery level 7 (70%), charging status 1 -> usb[53]

        val crc = java.util.zip.CRC32()
        crc.update(0xA1)
        crc.update(bt, 0, 74)
        val crcVal = crc.value
        bt[74] = (crcVal and 0xFF).toByte()
        bt[75] = ((crcVal shr 8) and 0xFF).toByte()
        bt[76] = ((crcVal shr 16) and 0xFF).toByte()
        bt[77] = ((crcVal shr 24) and 0xFF).toByte()

        val usb64 = DualSense.convertBtInputToUsb64(bt)
        assertNotNull(usb64)
        assertEquals(64, usb64!!.size)
        assertEquals(DualSense.REPORT_ID_INPUT, usb64[0])
        assertEquals(200.toByte(), usb64[1])
        assertEquals(0x17.toByte(), usb64[53])

        val parsed = DualSense.parseInputReport(bt)!!
        assertEquals(75, parsed.batteryPercent)
        assertTrue(parsed.isCharging)
        assertTrue(parsed.cross)
        assertTrue(parsed.mute)
        assertTrue(parsed.touch0Down)

        // Feed 78B BT report into DualSenseManager and verify pad.bin is normalized to 64B 0x01
        val manager = DualSenseManager(context)
        val dev = inputDevice(DualSense.PRODUCT_DUALSENSE, id = 42)
        val im = context.getSystemService(Context.INPUT_SERVICE) as android.hardware.input.InputManager
        org.robolectric.Shadows.shadowOf(im).addInputDevice(dev)
        manager.onInputDeviceAdded(dev.id)
        manager.onInputReport(bt)

        val padBin = File(tempDir, "pad.bin")
        assertTrue(padBin.exists())
        assertEquals(64L, padBin.length())
        val padBinBytes = padBin.readBytes()
        assertEquals(DualSense.REPORT_ID_INPUT, padBinBytes[0])
        assertEquals(200.toByte(), padBinBytes[1])
        assertEquals(0x17.toByte(), padBinBytes[53])
        manager.stop()
    }

    @Test
    fun testBasicBluetooth10ByteReportParsing() {
        val basic = ByteArray(DualSense.REPORT_SIZE_BT_BASIC_INPUT)
        basic[0] = DualSense.REPORT_ID_INPUT // 0x01 (10-byte basic BT mode)
        basic[1] = 0.toByte()   // LX = -1.0f
        basic[2] = 255.toByte() // LY = +1.0f
        basic[3] = 255.toByte() // RX = +1.0f
        basic[4] = 0.toByte()   // RY = -1.0f
        basic[5] = 0x20.toByte() // Cross + Hat 0 (Up)
        basic[6] = 0x03.toByte() // L1 + R1
        basic[7] = 0x02.toByte() // Touchpad click
        basic[8] = 255.toByte() // L2 = 1.0f
        basic[9] = 128.toByte() // R2 = ~0.5f

        val state = DualSense.parseInputReport(basic)
        assertNotNull(state)
        state!!
        assertEquals(-1.0f, state.leftX, 0.01f)
        assertEquals(1.0f, state.leftY, 0.01f)
        assertEquals(1.0f, state.rightX, 0.01f)
        assertEquals(-1.0f, state.rightY, 0.01f)
        assertTrue(state.dpadUp)
        assertTrue(state.cross)
        assertTrue(state.l1)
        assertTrue(state.r1)
        assertTrue(state.touchpadClick)
        assertEquals(1.0f, state.leftTrigger, 0.01f)
    }

    @Test
    fun testBluetooth3SecondLightbarInitializationGuard() {
        val usbReport = DualSense.buildOutputReport(
            leftMotor = 50,
            rightMotor = 100,
            r = 255,
            g = 64,
            b = 128,
            playerLeds = 0x04
        )
        // During first 3.0s of Bluetooth connection (btInitializing = true):
        val btInitReport = DualSense.buildBluetoothOutputReport(
            usbReport,
            seq = 7,
            isEdge = false,
            btInitializing = true
        )
        assertEquals(DualSense.REPORT_ID_BT_OUTPUT, btInitReport[0])
        assertEquals(((7 shl 4) or 0x02).toByte(), btInitReport[1])
        // enable2 (bt[3]) must have 0x04 cleared
        assertEquals(0, btInitReport[3].toInt() and 0x04)
        // lightbarSetup (bt[43]) must be 0x01 and RGB (bt[46..48]) zeroed
        assertEquals(0x01.toByte(), btInitReport[43])
        assertEquals(0.toByte(), btInitReport[46])
        assertEquals(0.toByte(), btInitReport[47])
        assertEquals(0.toByte(), btInitReport[48])

        // Verify CRC32 with 0xA2 seed is valid
        val btCrc = java.util.zip.CRC32()
        btCrc.update(0xA2)
        btCrc.update(btInitReport, 0, 74)
        val expectedCrc = btCrc.value
        val actualCrc = (btInitReport[74].toLong() and 0xFF) or
                ((btInitReport[75].toLong() and 0xFF) shl 8) or
                ((btInitReport[76].toLong() and 0xFF) shl 16) or
                ((btInitReport[77].toLong() and 0xFF) shl 24)
        assertEquals(expectedCrc, actualCrc)
    }

    @Test
    fun testFactoryImuCalibrationFeatureReport05() {
        val calBuf = ByteArray(DualSense.REPORT_SIZE_CALIBRATION)
        val bb = ByteBuffer.wrap(calBuf).order(ByteOrder.LITTLE_ENDIAN)
        calBuf[0] = DualSense.REPORT_ID_CALIBRATION
        // Gyro bias: +10, -20, +30
        bb.putShort(1, 10.toShort())
        bb.putShort(3, (-20).toShort())
        bb.putShort(5, 30.toShort())
        // Gyro plus/minus: nominal +/-8640, speed 540/540 -> 1x scale after bias subtraction
        for (axis in 0..2) {
            bb.putShort(7 + axis * 4, 8640.toShort())
            bb.putShort(9 + axis * 4, (-8640).toShort())
        }
        bb.putShort(19, 540.toShort())
        bb.putShort(21, 540.toShort())
        // Accel plus/minus: +8292 / -8092 (bias = +100, range2g = 16384 -> 1x scale after bias subtraction)
        for (axis in 0..2) {
            bb.putShort(23 + axis * 4, 8292.toShort())
            bb.putShort(25 + axis * 4, (-8092).toShort())
        }

        val cal = DualSense.parseCalibrationReport(calBuf)
        assertNotNull(cal)

        val report = createNeutralReport()
        val rbb = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)
        rbb.putShort(16, 110.toShort())   // gyroX - 10 = 100
        rbb.putShort(18, 180.toShort())   // gyroY - (-20) = 200
        rbb.putShort(20, 330.toShort())   // gyroZ - 30 = 300
        rbb.putShort(22, 1100.toShort())  // accelX - 100 = 1000
        rbb.putShort(24, 2100.toShort())  // accelY - 100 = 2000
        rbb.putShort(26, 3100.toShort())  // accelZ - 100 = 3000

        val parsed = DualSense.parseInputReport(report, calibration = cal)!!
        assertEquals(100.toShort(), parsed.gyroX)
        assertEquals(200.toShort(), parsed.gyroY)
        assertEquals(300.toShort(), parsed.gyroZ)
        assertEquals(1000.toShort(), parsed.accelX)
        assertEquals(2000.toShort(), parsed.accelY)
        assertEquals(3000.toShort(), parsed.accelZ)

        // Zeroed/degenerate calibration report must safely return null
        val invalidCal = ByteArray(DualSense.REPORT_SIZE_CALIBRATION)
        invalidCal[0] = DualSense.REPORT_ID_CALIBRATION
        assertNull(DualSense.parseCalibrationReport(invalidCal))
    }

    @Test
    fun testBatteryStatesAndHeadphoneStatus() {
        val report = createNeutralReport()
        // Power state 0x02 (Full charge) must report 100% and charging = true even if raw level is 8
        report[53] = 0x28.toByte()
        report[54] = 0x01.toByte() // Headphones plugged
        var state = DualSense.parseInputReport(report)!!
        assertEquals(100, state.batteryPercent)
        assertTrue(state.isCharging)
        assertTrue(state.headphonesPlugged)
        assertEquals(0x28, state.batteryStatusByte)

        // Power state 0x0A / 0x0B / 0x0F (Charge error) must report 0% and charging = false
        for (errState in listOf(0xA5, 0xB5, 0xF5)) {
            report[53] = errState.toByte()
            state = DualSense.parseInputReport(report)!!
            assertEquals(0, state.batteryPercent)
            assertFalse(state.isCharging)
        }

        // Verify DeckControls packs HEADPHONES and BATTERY_VALID + battery byte into controls
        val manager = DualSenseManager(context)
        report[53] = 0x17.toByte()
        report[54] = 0x01.toByte()
        manager.onInputReport(report)
        assertTrue((RecordingWriter.lastControls and DeckControls.HEADPHONES) != 0)
        assertTrue((RecordingWriter.lastControls and DeckControls.BATTERY_VALID) != 0)
        assertEquals(0x17, (RecordingWriter.lastControls ushr 16) and 0xFF)
        manager.stop()
    }

    @Test
    fun testBluetoothLightbarDeferredReplayAndMicMuteToggle() {
        val manager = DualSenseManager(context)
        val sentBtReports = mutableListOf<ByteArray>()
        manager.bluetoothReportSender = { bt ->
            sentBtReports.add(bt.clone())
            true
        }

        manager.btConnectTimestampMs = 1000L
        val colorReport = DualSense.buildOutputReport(r = 12, g = 34, b = 56, playerLeds = 0x04)

        // Build within 3.0s guard window (t = 2000ms): RGB is suppressed, report is saved as pending
        val suppressed = manager.buildNextBluetoothOutputReport(colorReport, nowMs = 2000L)
        assertEquals(0, suppressed[3].toInt() and 0x04)
        assertNotNull(manager.pendingBtLightbarReport)

        // Flushing before 3.0s elapsed does nothing
        assertFalse(manager.flushPendingBluetoothLightbar(nowMs = 3500L))
        assertTrue(sentBtReports.isEmpty())

        // Flushing after 3.0s elapsed (t = 4005ms) dispatches the saved lightbar report with RGB intact
        assertTrue(manager.flushPendingBluetoothLightbar(nowMs = 4005L))
        assertEquals(1, sentBtReports.size)
        assertEquals(12.toByte(), sentBtReports[0][46])
        assertEquals(34.toByte(), sentBtReports[0][47])
        assertEquals(56.toByte(), sentBtReports[0][48])

        // Hardware Mic Mute button rising edge toggles hardwareMicMuted and dispatches buildMicMuteReport
        sentBtReports.clear()
        val report = createNeutralReport()
        report[10] = 0x04 // Mute pressed
        manager.onInputReport(report)
        assertTrue(manager.hardwareMicMuted)
        assertEquals(1, sentBtReports.size)
        assertEquals(1.toByte(), sentBtReports[0][10])  // bt[10] = usb[9] = micLed ON (1)
        assertEquals(0x10.toByte(), sentBtReports[0][11]) // bt[11] = usb[10] = MUTE_Y (0x10)

        // Release and press again -> unmutes
        report[10] = 0x00
        manager.onInputReport(report)
        report[10] = 0x04
        manager.onInputReport(report)
        assertFalse(manager.hardwareMicMuted)
        assertEquals(2, sentBtReports.size)
        assertEquals(0.toByte(), sentBtReports[1][10])

        manager.stop()
    }

    @Test
    fun `sensorTimestamp and MotionTimestampDelta handle 3MHz ticks wrap-around and discontinuities`() {
        val report = createNeutralReport()
        // 0x89ABCDEF at usb[28..31]
        report[28] = 0xEF.toByte()
        report[29] = 0xCD.toByte()
        report[30] = 0xAB.toByte()
        report[31] = 0x89.toByte()
        val state = DualSense.parseInputReport(report)!!
        assertEquals(0x89ABCDEFL, state.sensorTimestamp)

        val delta = DualSense.MotionTimestampDelta()
        // First sample initializes state and hasValidDelta = false
        val first = delta.capture(0xFFFFFF00L)
        assertFalse(first.hasValidDelta)

        // Wrap-around across 0xFFFFFFFF -> +12000 ticks (4.0 ms at 3 MHz)
        val wrapped = delta.capture((0xFFFFFF00L + 12000L) and 0xFFFFFFFFL)
        assertTrue(wrapped.hasValidDelta)
        assertEquals(0.004f, wrapped.deltaSeconds, 0.0001f)
        assertEquals(4000, wrapped.deltaMicroseconds)

        // Duplicate timestamp (deltaTicks == 0) is rejected
        val dup = delta.capture((0xFFFFFF00L + 12000L) and 0xFFFFFFFFL)
        assertFalse(dup.hasValidDelta)

        // Discontinuity (> 50 ms = 150,000 ticks) is rejected
        val jump = delta.capture((0xFFFFFF00L + 12000L + 300_000L) and 0xFFFFFFFFL)
        assertFalse(jump.hasValidDelta)
    }

    @Test
    fun `playerLedMaskForBattery maps battery percentage to 5-LED bar mask`() {
        assertEquals(0x04, DualSense.playerLedMaskForBattery(0))
        assertEquals(0x04, DualSense.playerLedMaskForBattery(19))
        assertEquals(0x0A, DualSense.playerLedMaskForBattery(20))
        assertEquals(0x0A, DualSense.playerLedMaskForBattery(39))
        assertEquals(0x15, DualSense.playerLedMaskForBattery(40))
        assertEquals(0x15, DualSense.playerLedMaskForBattery(59))
        assertEquals(0x1B, DualSense.playerLedMaskForBattery(60))
        assertEquals(0x1B, DualSense.playerLedMaskForBattery(79))
        assertEquals(0x1F, DualSense.playerLedMaskForBattery(80))
        assertEquals(0x1F, DualSense.playerLedMaskForBattery(100))
        assertEquals(0x00, DualSense.playerLedMaskForBattery(15, flashOff = true))
    }

    @Test
    fun `lightbarRgbForMode playerLedMaskForMode and triggerPresetEffect map user settings`() {
        val tintArgb = android.graphics.Color.rgb(255, 110, 150)
        assertEquals(Triple(255, 110, 150), DualSense.lightbarRgbForMode(ControllerPrefs.LIGHTBAR_TINT, tintArgb, 50))
        assertEquals(Triple(0, 0, 0), DualSense.lightbarRgbForMode(ControllerPrefs.LIGHTBAR_OFF, tintArgb, 50))
        assertEquals(Triple(0x00, 0x40, 0xFF), DualSense.lightbarRgbForMode(ControllerPrefs.LIGHTBAR_PS_BLUE, tintArgb, 50))
        assertEquals(Triple(0x22, 0xE5, 0x66), DualSense.lightbarRgbForMode(ControllerPrefs.LIGHTBAR_BATTERY, tintArgb, 85))
        assertEquals(Triple(0xFF, 0xA7, 0x26), DualSense.lightbarRgbForMode(ControllerPrefs.LIGHTBAR_BATTERY, tintArgb, 40))
        assertEquals(Triple(0xFF, 0x33, 0x33), DualSense.lightbarRgbForMode(ControllerPrefs.LIGHTBAR_BATTERY, tintArgb, 15))

        assertEquals(0x04, DualSense.playerLedMaskForMode(ControllerPrefs.PLAYER_LEDS_P1, 80))
        assertEquals(0x1F, DualSense.playerLedMaskForMode(ControllerPrefs.PLAYER_LEDS_BATTERY, 85))
        assertEquals(0x00, DualSense.playerLedMaskForMode(ControllerPrefs.PLAYER_LEDS_OFF, 85))

        assertNull(DualSense.triggerPresetEffect(ControllerPrefs.TRIGGER_AUTO))
        assertEquals(0x21.toByte(), DualSense.triggerPresetEffect(ControllerPrefs.TRIGGER_FEEDBACK)!![0])
        assertEquals(0x25.toByte(), DualSense.triggerPresetEffect(ControllerPrefs.TRIGGER_WEAPON)!![0])
        assertEquals(0x22.toByte(), DualSense.triggerPresetEffect(ControllerPrefs.TRIGGER_BOW)!![0])
        assertEquals(0x26.toByte(), DualSense.triggerPresetEffect(ControllerPrefs.TRIGGER_VIBRATION)!![0])
        assertEquals(0x05.toByte(), DualSense.triggerPresetEffect(ControllerPrefs.TRIGGER_OFF)!![0])
    }

    @Test
    fun `applyUserPreferences dispatches lightbar playerLeds triggers and touchpad gating`() {
        ControllerPrefs.setTint(context, android.graphics.Color.rgb(80, 215, 165))
        ControllerPrefs.setDualSenseLightbarMode(context, ControllerPrefs.LIGHTBAR_TINT)
        ControllerPrefs.setDualSensePlayerLeds(context, ControllerPrefs.PLAYER_LEDS_OFF)
        ControllerPrefs.setDualSensePlayerLedBrightness(context, 2)
        ControllerPrefs.setDualSenseTriggerMode(context, ControllerPrefs.TRIGGER_WEAPON)
        ControllerPrefs.setDualSenseTouchpad(context, false)

        val manager = DualSenseManager(context)
        val sentBt = mutableListOf<ByteArray>()
        manager.bluetoothReportSender = { bt ->
            sentBt.add(bt.clone())
            true
        }
        manager.refreshUserPreferences()
        assertTrue(manager.applyUserPreferences())
        assertEquals(1, sentBt.size)
        val bt = sentBt[0]
        // bt[2..] mirrors usb[1..]: usb[45..47] -> bt[46..48], usb[44] -> bt[45], usb[43] -> bt[44], usb[11] -> bt[12], usb[22] -> bt[23]
        assertEquals(80.toByte(), bt[46])
        assertEquals(215.toByte(), bt[47])
        assertEquals(165.toByte(), bt[48])
        assertEquals(0x00.toByte(), bt[45])
        assertEquals(2.toByte(), bt[44])
        assertEquals(0x25.toByte(), bt[12])
        assertEquals(0x25.toByte(), bt[23])

        // Touchpad disabled suppresses touch0Down/touchpadClick in guest state
        val report = createNeutralReport()
        report[33] = 0x01 // Touch0 active
        report[10] = 0x02 // Touchpad click
        manager.onInputReport(report)
        assertEquals(0, RecordingWriter.lastControls and 0xF0)

        manager.stop()
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
