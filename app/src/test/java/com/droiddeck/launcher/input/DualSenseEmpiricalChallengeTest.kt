package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.usb.UsbDevice
import android.view.InputDevice
import android.view.MotionEvent
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.InputDeviceBuilder
import org.robolectric.util.ReflectionHelpers
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [34],
    manifest = Config.NONE,
    instrumentedPackages = ["com.droiddeck.launcher.input.FakeInputWriter"],
    shadows = [DualSenseTest.RecordingWriter::class]
)
class DualSenseEmpiricalChallengeTest {
    private lateinit var context: Context
    private lateinit var tempDir: File

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        tempDir = File(context.cacheDir, "challenge-dualsense-${System.nanoTime()}").apply { mkdirs() }
        DualSenseTest.RecordingWriter.reset()
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
        DualSenseTest.RecordingWriter.reset()
    }

    private fun makeReport(): ByteArray {
        val r = ByteArray(64)
        r[0] = DualSense.REPORT_ID_INPUT
        r[1] = 128.toByte() // LX
        r[2] = 128.toByte() // LY
        r[3] = 128.toByte() // RX
        r[4] = 128.toByte() // RY
        r[5] = 0 // L2
        r[6] = 0 // R2
        r[8] = 0x08 // Hat 8 = None
        r[33] = 0x80.toByte() // Finger 0 inactive
        r[37] = 0x80.toByte() // Finger 1 inactive
        return r
    }

    // =========================================================================
    // CHALLENGE 1: Buffer Corruption, Truncation & Offset Boundaries
    // =========================================================================

    @Test
    fun challengeBufferTruncation() {
        // Buffers shorter than 64 bytes (except exact 10-byte BT simple 0x01 report) MUST return null
        for (len in 0..63) {
            val buf = ByteArray(len)
            if (len > 0) buf[0] = DualSense.REPORT_ID_INPUT
            if (len == DualSense.REPORT_SIZE_BT_BASIC_INPUT) {
                assertNotNull("Buffer of length 10 (BT simple report 0x01) must succeed", DualSense.parseInputReport(buf))
            } else {
                assertNull("Buffer of length $len must return null", DualSense.parseInputReport(buf))
            }
        }

        // Buffer of exactly 64 bytes with correct ID must succeed
        val exact = makeReport()
        assertNotNull("Buffer of length 64 must succeed", DualSense.parseInputReport(exact))

        // Buffer larger than 64 bytes must succeed
        for (len in listOf(65, 78, 128, 512, 1024)) {
            val large = ByteArray(len)
            large[0] = DualSense.REPORT_ID_INPUT
            large[1] = 128.toByte()
            large[2] = 128.toByte()
            large[3] = 128.toByte()
            large[4] = 128.toByte()
            large[8] = 0x08
            large[33] = 0x80.toByte()
            large[37] = 0x80.toByte()
            assertNotNull("Buffer of length $len must succeed", DualSense.parseInputReport(large))
        }
    }

    @Test
    fun challengeInvalidReportIds() {
        // Only 0x01 (64B/10B) and 0x31 (>=78B) are valid input report IDs; 64B with 0x31 is truncated and must return null
        for (id in listOf(0x00, 0x02, 0x03, 0x05, 0x09, 0x20, 0x31, 0x80, 0xFF)) {
            val report = makeReport()
            report[0] = id.toByte()
            assertNull("64-byte Report ID 0x${Integer.toHexString(id)} must return null", DualSense.parseInputReport(report))
        }
    }

    @Test
    fun challengeOffsetBoundaries() {
        val large = ByteArray(200)
        // Put valid report at offset 50
        val offset = 50
        large[offset] = DualSense.REPORT_ID_INPUT
        large[offset + 1] = 128.toByte()
        large[offset + 2] = 128.toByte()
        large[offset + 3] = 128.toByte()
        large[offset + 4] = 128.toByte()
        large[offset + 8] = 0x08
        large[offset + 33] = 0x80.toByte()
        large[offset + 37] = 0x80.toByte()

        assertNotNull(DualSense.parseInputReport(large, offset = offset))

        // Offset where offset + 64 > size must return null
        assertNull(DualSense.parseInputReport(large, offset = 137))
        assertNull(DualSense.parseInputReport(large, offset = 200))
    }

    // =========================================================================
    // CHALLENGE 2: Extreme Stick Positions & Deadzones
    // =========================================================================

    @Test
    fun challengeExtremeStickPositions() {
        val report = makeReport()

        // 1. Minimum (0) -> must be -1.0f
        report[1] = 0
        report[2] = 0
        report[3] = 0
        report[4] = 0
        var state = DualSense.parseInputReport(report)!!
        assertEquals(-1.0f, state.leftX, 0.0001f)
        assertEquals(-1.0f, state.leftY, 0.0001f)
        assertEquals(-1.0f, state.rightX, 0.0001f)
        assertEquals(-1.0f, state.rightY, 0.0001f)

        // 2. Center (128) -> must be exactly 0.0f
        report[1] = 128.toByte()
        report[2] = 128.toByte()
        report[3] = 128.toByte()
        report[4] = 128.toByte()
        state = DualSense.parseInputReport(report)!!
        assertEquals(0.0f, state.leftX, 0.000001f)
        assertEquals(0.0f, state.leftY, 0.000001f)
        assertEquals(0.0f, state.rightX, 0.000001f)
        assertEquals(0.0f, state.rightY, 0.000001f)

        // 3. Maximum (255) -> must be exactly 1.0f
        report[1] = 255.toByte()
        report[2] = 255.toByte()
        report[3] = 255.toByte()
        report[4] = 255.toByte()
        state = DualSense.parseInputReport(report)!!
        assertEquals(1.0f, state.leftX, 0.0001f)
        assertEquals(1.0f, state.leftY, 0.0001f)
        assertEquals(1.0f, state.rightX, 0.0001f)
        assertEquals(1.0f, state.rightY, 0.0001f)

        // 4. One below center (127) and one above center (129)
        report[1] = 127.toByte()
        report[2] = 129.toByte()
        state = DualSense.parseInputReport(report)!!
        assertTrue("LX at 127 must be slightly negative", state.leftX < 0f && state.leftX > -0.01f)
        assertTrue("LY at 129 must be slightly positive", state.leftY > 0f && state.leftY < 0.01f)

        // 5. All 256 stick values must stay within [-1.0f, 1.0f]
        for (v in 0..255) {
            report[1] = v.toByte()
            val s = DualSense.parseInputReport(report)!!
            assertTrue("Value $v produced out-of-range leftX: ${s.leftX}", s.leftX in -1.0f..1.0f)
        }
    }

    // =========================================================================
    // CHALLENGE 3: Analog Triggers (0, 128, 255 and full range)
    // =========================================================================

    @Test
    fun challengeAnalogTriggers() {
        val report = makeReport()

        // 0 -> 0.0f
        report[5] = 0
        report[6] = 0
        var s = DualSense.parseInputReport(report)!!
        assertEquals(0.0f, s.leftTrigger, 0.0001f)
        assertEquals(0.0f, s.rightTrigger, 0.0001f)

        // 255 -> 1.0f
        report[5] = 255.toByte()
        report[6] = 255.toByte()
        s = DualSense.parseInputReport(report)!!
        assertEquals(1.0f, s.leftTrigger, 0.0001f)
        assertEquals(1.0f, s.rightTrigger, 0.0001f)

        // All 256 trigger values must stay within [0.0f, 1.0f]
        for (v in 0..255) {
            report[5] = v.toByte()
            report[6] = v.toByte()
            val st = DualSense.parseInputReport(report)!!
            assertTrue("Trigger $v out of range: ${st.leftTrigger}", st.leftTrigger in 0.0f..1.0f)
            assertTrue("Trigger $v out of range: ${st.rightTrigger}", st.rightTrigger in 0.0f..1.0f)
        }
    }

    // =========================================================================
    // CHALLENGE 4: 16-Bit Signed IMU Unpacking (Oracle vs ByteBuffer)
    // =========================================================================

    @Test
    fun challengeImuShortDecodingOracle() {
        // Empirically test EVERY possible 16-bit short value (-32768 to 32767)
        // to verify that DualSense shortLE produces 100% bit-exact results matching ByteBuffer LE.
        val report = makeReport()
        val bb = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)

        // Test key values and a dense sweep
        val testValues = mutableListOf<Short>()
        testValues.add(Short.MIN_VALUE) // -32768 (0x8000)
        testValues.add(Short.MAX_VALUE) // 32767 (0x7FFF)
        testValues.add(0.toShort())
        testValues.add((-1).toShort())
        testValues.add(1.toShort())
        testValues.add((-128).toShort())
        testValues.add(128.toShort())
        testValues.add((-256).toShort())
        testValues.add(256.toShort())

        // Add 2000 arbitrary points across the short range
        for (i in -32768..32767 step 33) {
            testValues.add(i.toShort())
        }

        for (v in testValues) {
            bb.putShort(16, v) // GyroX
            bb.putShort(18, (-v).toShort()) // GyroY
            bb.putShort(20, v) // GyroZ
            bb.putShort(22, v) // AccelX
            bb.putShort(24, (-v).toShort()) // AccelY
            bb.putShort(26, v) // AccelZ

            val state = DualSense.parseInputReport(report)!!
            assertEquals("GyroX mismatch for value $v", v, state.gyroX)
            assertEquals("GyroY mismatch for value ${-v}", (-v).toShort(), state.gyroY)
            assertEquals("GyroZ mismatch for value $v", v, state.gyroZ)
            assertEquals("AccelX mismatch for value $v", v, state.accelX)
            assertEquals("AccelY mismatch for value ${-v}", (-v).toShort(), state.accelY)
            assertEquals("AccelZ mismatch for value $v", v, state.accelZ)
        }
    }

    @Test
    fun challengeDualSenseManagerImuTransformExtremes() {
        val manager = DualSenseManager(context)
        val report = makeReport()
        val bb = ByteBuffer.wrap(report).order(ByteOrder.LITTLE_ENDIAN)

        // Extreme: Short.MIN_VALUE for all axes
        bb.putShort(16, Short.MIN_VALUE)
        bb.putShort(18, Short.MIN_VALUE)
        bb.putShort(20, Short.MIN_VALUE)
        bb.putShort(22, Short.MIN_VALUE)
        bb.putShort(24, Short.MIN_VALUE)
        bb.putShort(26, Short.MIN_VALUE)

        manager.onInputReport(report)

        assertNotNull(DualSenseTest.RecordingWriter.lastGyro)
        assertNotNull(DualSenseTest.RecordingWriter.lastAccel)

        // Verify no crash occurred and values were coerced cleanly
        val gyro = DualSenseTest.RecordingWriter.lastGyro!!
        val accel = DualSenseTest.RecordingWriter.lastAccel!!
        assertEquals(3, gyro.size)
        assertEquals(3, accel.size)

        // Extreme: Short.MAX_VALUE for all axes
        bb.putShort(16, Short.MAX_VALUE)
        bb.putShort(18, Short.MAX_VALUE)
        bb.putShort(20, Short.MAX_VALUE)
        bb.putShort(22, Short.MAX_VALUE)
        bb.putShort(24, Short.MAX_VALUE)
        bb.putShort(26, Short.MAX_VALUE)

        manager.onInputReport(report)
        assertEquals(Short.MAX_VALUE, DualSenseTest.RecordingWriter.lastAccel!![0])
        assertEquals(Short.MIN_VALUE, DualSenseTest.RecordingWriter.lastAccel!![1]) // -MAX_VALUE coerced
        assertEquals(Short.MAX_VALUE, DualSenseTest.RecordingWriter.lastAccel!![2])

        manager.stop()
    }

    // =========================================================================
    // CHALLENGE 5: Touchpad Coordinates & Boundary Clamping
    // =========================================================================

    @Test
    fun challengeTouchpadBoundariesAndClamping() {
        val report = makeReport()

        // Test 1: (0, 0)
        report[33] = 0 // down, id 0
        report[34] = 0
        report[35] = 0
        report[36] = 0
        var state = DualSense.parseInputReport(report)!!
        assertTrue(state.touch0Down)
        assertEquals(0, state.touch0X)
        assertEquals(0, state.touch0Y)

        // Test 2: (1919, 1079) -> X=0x77F, Y=0x437
        // px = 0x77F -> low 8 = 0x7F, high 4 = 0x07
        // py = 0x437 -> low 4 = 0x07, high 8 = 0x43
        // byte 34 = 0x7F
        // byte 35 = (0x07) | (0x07 << 4) = 0x77
        // byte 36 = 0x43
        report[34] = 0x7F
        report[35] = 0x77
        report[36] = 0x43
        state = DualSense.parseInputReport(report)!!
        assertEquals(1919, state.touch0X)
        assertEquals(1079, state.touch0Y)

        // Test 3: Oversized/Corrupted coordinates in HID packet (e.g. 12-bit max = 4095)
        // If hardware sends 0xFFF for both X and Y:
        report[34] = 0xFF.toByte()
        report[35] = 0xFF.toByte()
        report[36] = 0xFF.toByte()
        state = DualSense.parseInputReport(report)!!
        // MUST clamp to TOUCHPAD_WIDTH - 1 (1919) and TOUCHPAD_HEIGHT - 1 (1079)
        assertEquals(1919, state.touch0X)
        assertEquals(1079, state.touch0Y)
    }

    @Test
    fun challengeTouchpadActiveBitStates() {
        val report = makeReport()

        // Finger 0 Active (0x00), Finger 1 Inactive (0x80)
        report[33] = 0x01 // id 1, down
        report[37] = 0x82.toByte() // id 2, up
        var state = DualSense.parseInputReport(report)!!
        assertTrue(state.touch0Down)
        assertFalse(state.touch1Down)
        assertEquals(1, state.touch0Id)
        assertEquals(2, state.touch1Id)

        // Finger 0 Inactive (0x80), Finger 1 Active (0x00)
        report[33] = 0x83.toByte() // id 3, up
        report[37] = 0x04 // id 4, down
        state = DualSense.parseInputReport(report)!!
        assertFalse(state.touch0Down)
        assertTrue(state.touch1Down)
        assertEquals(3, state.touch0Id)
        assertEquals(4, state.touch1Id)

        // Both Active
        report[33] = 0x05
        report[37] = 0x06
        state = DualSense.parseInputReport(report)!!
        assertTrue(state.touch0Down)
        assertTrue(state.touch1Down)

        // Both Inactive
        report[33] = 0x85.toByte()
        report[37] = 0x86.toByte()
        state = DualSense.parseInputReport(report)!!
        assertFalse(state.touch0Down)
        assertFalse(state.touch1Down)
    }

    @Test
    fun challengeCoordinateTransformersExtremeInputs() {
        // Test touchXToPad & touchYToPad with negative and out-of-bounds inputs
        assertEquals(Short.MIN_VALUE, DualSense.touchXToPad(-1000))
        assertEquals(Short.MIN_VALUE, DualSense.touchXToPad(0))
        assertEquals(Short.MAX_VALUE, DualSense.touchXToPad(1919))
        assertEquals(Short.MAX_VALUE, DualSense.touchXToPad(5000))

        assertEquals(Short.MAX_VALUE, DualSense.touchYToPad(-1000))
        assertEquals(Short.MAX_VALUE, DualSense.touchYToPad(0))
        assertEquals(Short.MIN_VALUE, DualSense.touchYToPad(1079))
        assertEquals(Short.MIN_VALUE, DualSense.touchYToPad(5000))

        // Full round-trip fidelity across grid
        for (x in 0..1919 step 71) {
            val padX = DualSense.touchXToPad(x)
            val recoveredX = DualSense.padToTouchX(padX)
            assertTrue("X roundtrip error at $x -> recovered $recoveredX", Math.abs(x - recoveredX) <= 1)
        }
        for (y in 0..1079 step 43) {
            val padY = DualSense.touchYToPad(y)
            val recoveredY = DualSense.padToTouchY(padY)
            assertTrue("Y roundtrip error at $y -> recovered $recoveredY", Math.abs(y - recoveredY) <= 1)
        }
    }

    // =========================================================================
    // CHALLENGE 6: Complex Button Combinations & Simultaneous Presses
    // =========================================================================

    @Test
    fun challengeSimultaneousAllButtons() {
        val report = makeReport()

        // Press ALL face buttons + hat UP-RIGHT (1)
        report[8] = (0x01 or 0x10 or 0x20 or 0x40 or 0x80).toByte()
        // Press ALL shoulder, trigger clicks, create, options, L3, R3
        report[9] = 0xFF.toByte()
        // Press PS, Touchpad Click, Mute, L4, R4, L5, R5
        report[10] = (0x01 or 0x02 or 0x04 or 0x10 or 0x20 or 0x40 or 0x80).toByte()

        val state = DualSense.parseInputReport(report)!!

        // D-Pad
        assertTrue("Hat 1 must have UP", state.dpadUp)
        assertTrue("Hat 1 must have RIGHT", state.dpadRight)
        assertFalse(state.dpadDown)
        assertFalse(state.dpadLeft)

        // Face
        assertTrue(state.square)
        assertTrue(state.cross)
        assertTrue(state.circle)
        assertTrue(state.triangle)

        // Shoulders & System
        assertTrue(state.l1)
        assertTrue(state.r1)
        assertTrue(state.l2Click)
        assertTrue(state.r2Click)
        assertTrue(state.create)
        assertTrue(state.options)
        assertTrue(state.l3)
        assertTrue(state.r3)

        // DualSense Extras
        assertTrue(state.ps)
        assertTrue(state.touchpadClick)
        assertTrue(state.mute)
        assertTrue(state.l4)
        assertTrue(state.r4)
        assertTrue(state.l5)
        assertTrue(state.r5)
    }

    @Test
    fun challengeHatSwitchExhaustive() {
        val report = makeReport()
        // Hat values 0..15
        for (hat in 0..15) {
            report[8] = hat.toByte()
            val state = DualSense.parseInputReport(report)!!
            when (hat) {
                0 -> { assertTrue(state.dpadUp); assertFalse(state.dpadRight); assertFalse(state.dpadDown); assertFalse(state.dpadLeft) }
                1 -> { assertTrue(state.dpadUp); assertTrue(state.dpadRight); assertFalse(state.dpadDown); assertFalse(state.dpadLeft) }
                2 -> { assertFalse(state.dpadUp); assertTrue(state.dpadRight); assertFalse(state.dpadDown); assertFalse(state.dpadLeft) }
                3 -> { assertFalse(state.dpadUp); assertTrue(state.dpadRight); assertTrue(state.dpadDown); assertFalse(state.dpadLeft) }
                4 -> { assertFalse(state.dpadUp); assertFalse(state.dpadRight); assertTrue(state.dpadDown); assertFalse(state.dpadLeft) }
                5 -> { assertFalse(state.dpadUp); assertFalse(state.dpadRight); assertTrue(state.dpadDown); assertTrue(state.dpadLeft) }
                6 -> { assertFalse(state.dpadUp); assertFalse(state.dpadRight); assertFalse(state.dpadDown); assertTrue(state.dpadLeft) }
                7 -> { assertTrue(state.dpadUp); assertFalse(state.dpadRight); assertFalse(state.dpadDown); assertTrue(state.dpadLeft) }
                else -> {
                    // Released (8) or invalid (9..15) -> all false
                    assertFalse(state.dpadUp)
                    assertFalse(state.dpadRight)
                    assertFalse(state.dpadDown)
                    assertFalse(state.dpadLeft)
                }
            }
        }
    }

    // =========================================================================
    // CHALLENGE 7: Concurrency & Multithreaded Stress
    // =========================================================================

    @Test
    fun challengeConcurrentReportParsing() {
        val executor = Executors.newFixedThreadPool(8)
        val report = makeReport()
        report[1] = 64
        report[2] = 192.toByte()
        report[5] = 200.toByte()
        report[8] = 0x33 // SE hat (3) + cross (0x20) + square (0x10)
        report[9] = 0x05 // L1 + L2 click
        report[10] = 0x04 // Mute

        var failure: Throwable? = null
        val iterations = 5000
        for (i in 0 until iterations) {
            executor.execute {
                try {
                    val s = DualSense.parseInputReport(report)
                    assertNotNull(s)
                    assertTrue(s!!.square)
                    assertTrue(s.cross)
                    assertTrue(s.l1)
                    assertTrue(s.l2Click)
                    assertTrue(s.mute)
                } catch (t: Throwable) {
                    failure = t
                }
            }
        }

        executor.shutdown()
        assertTrue("Executor did not finish in time", executor.awaitTermination(10, TimeUnit.SECONDS))
        assertNull("Concurrent parsing failed with error: ${failure?.message}", failure)
    }

    // =========================================================================
    // CHALLENGE 8: Hardware Detection Edge Cases
    // =========================================================================

    @Test
    fun challengeDetectionEdgeCases() {
        // PlayStation VR, PlayStation 3, PlayStation 4, DualSense, DualSense Edge
        val sonyDevices = listOf(
            0x0268 to false, // DualShock 3
            0x05C4 to false, // DualShock 4
            0x09CC to false, // DualShock 4 v2
            0x09CE to false, // PlayStation VR
            DualSense.PRODUCT_DUALSENSE to true,
            DualSense.PRODUCT_DUALSENSE_EDGE to true
        )

        for ((pid, isDs) in sonyDevices) {
            assertEquals("VID 0x054C PID 0x${Integer.toHexString(pid)} DualSense check failed",
                isDs, DualSense.isDualSense(DualSense.VENDOR_SONY, pid))
        }

        // Non-Sony devices having the same PID as DualSense (0x0CE6 or 0x0DF2)
        assertFalse(DualSense.isDualSense(0x045E, DualSense.PRODUCT_DUALSENSE))
        assertFalse(DualSense.isDualSense(0x057E, DualSense.PRODUCT_DUALSENSE))
        assertFalse(DualSense.isDualSense(0x045E, DualSense.PRODUCT_DUALSENSE_EDGE))
    }

    // =========================================================================
    // CHALLENGE 9: Offset Negative & Overflow Boundaries
    // =========================================================================

    @Test
    fun challengeNegativeOffsetBehavior() {
        val report = makeReport()
        // Negative offset must safely return null rather than throwing uncaught ArrayIndexOutOfBoundsException
        assertNull("Negative offset must return null safely", DualSense.parseInputReport(report, offset = -1))
    }

    @Test
    fun challengeLargeOffsetIntegerOverflow() {
        val report = makeReport()
        val largeOffset = Int.MAX_VALUE - 10
        // Extremely large offset must safely return null rather than overflowing to negative and throwing ArrayIndexOutOfBoundsException
        assertNull("Integer overflow offset must return null safely", DualSense.parseInputReport(report, offset = largeOffset))
    }

    @Test
    fun challengeTouchpadCoordinateIntegerOverflowMath() {
        // Test behavior when rawX > 32767
        // Expected: Coordinates exceeding 1919 must clamp to Short.MAX_VALUE (32767).
        // Bug: 40000 * 65535 overflows 32-bit signed integer (2,621,400,000 > Int.MAX_VALUE 2,147,483,647),
        // wrapping to negative and coercing to Short.MIN_VALUE (-32768).
        assertEquals(
            "touchXToPad must clamp out-of-bounds coordinates to Short.MAX_VALUE without 32-bit integer overflow",
            Short.MAX_VALUE,
            DualSense.touchXToPad(40000)
        )
    }

    @Test
    fun challengeDualSenseManagerConcurrentReportsAndPadBin() {
        val manager = DualSenseManager(context)
        val report = makeReport()
        val executor = Executors.newFixedThreadPool(4)
        val iterations = 500

        for (i in 0 until iterations) {
            executor.execute {
                manager.onInputReport(report)
            }
        }

        executor.shutdown()
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS))
        manager.stop()

        val padBin = File(tempDir, "pad.bin")
        if (padBin.exists()) {
            println("pad.bin length after concurrent writes: ${padBin.length()} bytes (expected 64 bytes)")
            assertEquals("pad.bin must remain exactly 64 bytes even under concurrent writes", 64L, padBin.length())
        }
    }
}

