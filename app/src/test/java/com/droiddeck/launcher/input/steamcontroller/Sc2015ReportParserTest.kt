package com.droiddeck.launcher.input.steamcontroller

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder

class Sc2015ReportParserTest {
    @Test
    fun parsesFullValveModeStateAndConvertsToTritonReport() {
        val state = Sc2015State()
        val mask = Sc2015ReportParser.CHUNK_BUTTONS_1 or
            Sc2015ReportParser.CHUNK_TRIGGERS or
            Sc2015ReportParser.CHUNK_LEFT_JOYSTICK or
            Sc2015ReportParser.CHUNK_LEFT_TRACKPAD or
            Sc2015ReportParser.CHUNK_RIGHT_TRACKPAD or
            Sc2015ReportParser.CHUNK_IMU_ACCEL or
            Sc2015ReportParser.CHUNK_IMU_GYRO

        val buttons = Sc2015ReportParser.BUTTON_A or
            Sc2015ReportParser.BUTTON_RB or
            Sc2015ReportParser.BUTTON_LGRIP or
            Sc2015ReportParser.BUTTON_RGRIP or
            Sc2015ReportParser.BUTTON_LPAD_TOUCH or
            Sc2015ReportParser.BUTTON_RPAD_TOUCH or
            Sc2015ReportParser.BUTTON_RPAD_CLICK or
            Sc2015ReportParser.BUTTON_STEAM

        val buf = ByteBuffer.allocate(31).order(ByteOrder.LITTLE_ENDIAN)
        buf.put((Sc2015ReportParser.REPORT_STATE or (mask and 0xF0)).toByte())
        buf.put(((mask ushr 8) and 0xFF).toByte())
        buf.put((buttons and 0xFF).toByte())
        buf.put(((buttons ushr 8) and 0xFF).toByte())
        buf.put(((buttons ushr 16) and 0xFF).toByte())
        buf.put(128.toByte())
        buf.put(255.toByte())
        buf.putShort(12000)
        buf.putShort(-8000)
        buf.putShort(-4500)
        buf.putShort(6500)
        buf.putShort(15000)
        buf.putShort(-11000)
        buf.putShort(100)
        buf.putShort(-200)
        buf.putShort(16384)
        buf.putShort(-50)
        buf.putShort(75)
        buf.putShort(-125)

        val parsed = Sc2015ReportParser.parse(buf.array(), state)
        assertTrue(parsed is Sc2015ReportParser.ParsedReport.State)
        assertEquals(buttons, state.buttons)
        assertEquals(128, state.leftTrigger)
        assertEquals(255, state.rightTrigger)
        assertEquals(12000.toShort(), state.stickX)
        assertEquals((-8000).toShort(), state.stickY)
        assertEquals((-4500).toShort(), state.leftPadX)
        assertEquals(6500.toShort(), state.leftPadY)
        assertEquals(15000.toShort(), state.rightPadX)
        assertEquals((-11000).toShort(), state.rightPadY)
        assertEquals(100.toShort(), state.accelX)
        assertEquals((-200).toShort(), state.accelY)
        assertEquals(16384.toShort(), state.accelZ)
        assertEquals((-50).toShort(), state.gyroX)
        assertEquals(75.toShort(), state.gyroY)
        assertEquals((-125).toShort(), state.gyroZ)

        val triton = Sc2015ReportParser.toTritonReport(state, packetNum = 7, elapsedUs = 123456L)
        assertEquals(45, triton.size)
        val out = ByteBuffer.wrap(triton).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(7, out.get(0).toInt() and 0xFF)
        val tritonBits = out.getInt(1)
        assertTrue(tritonBits and 0x00000001 != 0) // A
        assertTrue(tritonBits and 0x00000200 != 0) // RB
        assertTrue(tritonBits and 0x00010000 != 0) // STEAM
        assertTrue(tritonBits and 0x00020000 != 0) // L4 (Left Grip)
        assertTrue(tritonBits and 0x00000080 != 0) // R4 (Right Grip)
        assertTrue(tritonBits and 0x02000000 != 0) // LPAD_TOUCH
        assertTrue(tritonBits and 0x00200000 != 0) // RPAD_TOUCH
        assertTrue(tritonBits and 0x00400000 != 0) // RPAD_CLICK
        assertTrue(tritonBits and 0x00800000 != 0) // RTRIGGER_CLICK

        assertEquals((128 * 32767) / 255, out.getShort(5).toInt())
        assertEquals(32767, out.getShort(7).toInt())
        assertEquals(12000, out.getShort(9).toInt())
        assertEquals(-8000, out.getShort(11).toInt())
        assertEquals(-4500, out.getShort(17).toInt())
        assertEquals(6500, out.getShort(19).toInt())
        assertEquals(0, out.getShort(21).toInt())
        assertEquals(15000, out.getShort(23).toInt())
        assertEquals(-11000, out.getShort(25).toInt())
        assertEquals(32767, out.getShort(27).toInt())
        assertEquals(123456, out.getInt(29))
        assertEquals(100, out.getShort(33).toInt())
        assertEquals(-200, out.getShort(35).toInt())
        assertEquals(16384, out.getShort(37).toInt())
        assertEquals(-50, out.getShort(39).toInt())
        assertEquals(75, out.getShort(41).toInt())
        assertEquals(-125, out.getShort(43).toInt())
    }

    @Test
    fun liftingTrackpadTouchClearsStaleTrackpadCoordinates() {
        val state = Sc2015State().apply {
            buttons = Sc2015ReportParser.BUTTON_LPAD_TOUCH or Sc2015ReportParser.BUTTON_RPAD_TOUCH
            leftPadX = 4000
            leftPadY = -3000
            rightPadX = 8000
            rightPadY = 9000
        }
        val releasePayload = byteArrayOf(
            (Sc2015ReportParser.REPORT_STATE or Sc2015ReportParser.CHUNK_BUTTONS_1).toByte(),
            (Sc2015ReportParser.CHUNK_RIGHT_TRACKPAD ushr 8).toByte(),
            0x00, 0x00, 0x00,
            0x10, 0x27, 0x60, 0x6D
        )
        val parsed = Sc2015ReportParser.parse(releasePayload, state)
        assertNotNull(parsed)
        assertEquals(0.toShort(), state.leftPadX)
        assertEquals(0.toShort(), state.leftPadY)
        assertEquals(0.toShort(), state.rightPadX)
        assertEquals(0.toShort(), state.rightPadY)
    }

    @Test
    fun parsesBatteryStatusPacket() {
        val state = Sc2015State()
        val payload = byteArrayOf(
            0x05, 0x03, 0x8B.toByte(), 0x57, 0x01, 0x00, 0x00, 0x00,
            0x00, 0x00, 0x00, 0xD0.toByte(), 0x0B, 0x64, 0x00, 0x00, 0x00, 0x00
        )
        val parsed = Sc2015ReportParser.parse(payload, state) as? Sc2015ReportParser.ParsedReport.Status
        assertNotNull(parsed)
        assertEquals(3024, parsed!!.voltageMv)
        assertEquals(100, parsed.batteryPercent)
    }

    @Test
    fun extractsImuModeCommands() {
        val featureReport = byteArrayOf(
            0x00, 0x87.toByte(), 0x06,
            0x08, 0x07, 0x00,
            0x30, 0x18, 0x00
        )
        val imuCmd = Sc2015ReportParser.extractImuModeCommand(featureReport)
        assertArrayEquals(
            byteArrayOf(0xC0.toByte(), 0x87.toByte(), 0x03, 0x30, 0x18, 0x00),
            imuCmd
        )
    }

    @Test
    fun latchedTrackpadTouchExpiresUntilPadReportsAgain() {
        val state = Sc2015State()
        val touch = byteArrayOf(
            (Sc2015ReportParser.REPORT_STATE or Sc2015ReportParser.CHUNK_BUTTONS_1).toByte(),
            (Sc2015ReportParser.CHUNK_RIGHT_TRACKPAD ushr 8).toByte(),
            0x00, 0x00, 0x10,
            0x10, 0x27, 0x60, 0x6D
        )
        Sc2015ReportParser.parse(touch, state, nowMs = 1000)
        assertEquals(28000.toShort(), state.rightPadY)
        assertTrue(!Sc2015ReportParser.expireIdlePads(state, 1100))
        assertTrue(Sc2015ReportParser.expireIdlePads(state, 1300))
        assertEquals(0, state.buttons and Sc2015ReportParser.BUTTON_RPAD_TOUCH)
        assertEquals(0.toShort(), state.rightPadY)

        val buttonsOnly = byteArrayOf((Sc2015ReportParser.REPORT_STATE or Sc2015ReportParser.CHUNK_BUTTONS_1).toByte(), 0x00, 0x00, 0x00, 0x10)
        Sc2015ReportParser.parse(buttonsOnly, state, nowMs = 1400)
        assertEquals(0, state.buttons and Sc2015ReportParser.BUTTON_RPAD_TOUCH)

        Sc2015ReportParser.parse(touch, state, nowMs = 1500)
        assertTrue(state.buttons and Sc2015ReportParser.BUTTON_RPAD_TOUCH != 0)
        assertEquals(28000.toShort(), state.rightPadY)
    }

    @Test
    fun buildsFeatureReplyWithShippedFirmwareTimestamp() {
        val reply = Sc2015ReportParser.buildFeatureReply(0x83, batteryMv = 2950)
        val buf = ByteBuffer.wrap(reply).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(0x83, buf.get(0).toInt() and 0xFF)
        assertEquals(0x04, buf.get(12).toInt() and 0xFF)
        assertEquals(Sc2015ReportParser.TRITON_FW_TIMESTAMP, buf.getInt(13))
    }

    @Test
    fun rejectsTruncatedStateReportWithoutMutatingState() {
        val state = Sc2015State()
        state.buttons = Sc2015ReportParser.BUTTON_A
        val mask = Sc2015ReportParser.CHUNK_BUTTONS_1 or Sc2015ReportParser.CHUNK_TRIGGERS
        val truncated = byteArrayOf(
            (Sc2015ReportParser.REPORT_STATE or (mask and 0xF0)).toByte(),
            ((mask ushr 8) and 0xFF).toByte(),
            Sc2015ReportParser.BUTTON_B.toByte(), 0x00, 0x00
        )
        val result = Sc2015ReportParser.parse(truncated, state)
        org.junit.Assert.assertNull(result)
        assertEquals(Sc2015ReportParser.BUTTON_A, state.buttons)
    }
}
