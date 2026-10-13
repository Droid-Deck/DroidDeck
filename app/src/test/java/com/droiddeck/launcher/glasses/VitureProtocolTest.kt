package com.droiddeck.launcher.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import kotlin.math.cos
import kotlin.math.sin

class VitureProtocolTest {
    private fun u8(b: Byte) = b.toInt() and 0xFF
    private fun u16(b: ByteArray, at: Int) = u8(b[at]) or (u8(b[at + 1]) shl 8)

    @Test fun crcIsXmodem() {
        val check = "123456789".toByteArray()
        assertEquals(0x31C3, VitureProtocol.crc16(check, 0, check.size))
    }

    @Test fun packetLayout() {
        val p = VitureProtocol.packet(VitureProtocol.MSG_BRIGHTNESS, byteArrayOf(5, 0))
        assertEquals(VitureProtocol.PACKET_SIZE, p.size)
        assertEquals(0xFF, u8(p[0]))
        assertEquals(VitureProtocol.MAGIC_COMMAND, u8(p[1]))
        assertEquals(2 + 0x0C, u16(p, 4))
        assertEquals(VitureProtocol.MSG_BRIGHTNESS, u16(p, 14))
        assertEquals(5, u8(p[18]))
        assertEquals(VitureProtocol.crc16(p, 4, u16(p, 4) + 2), u16(p, 2))
        for (i in 6 until 14) assertEquals(0, u8(p[i]))
    }

    @Test fun parseRoundTripsAndChecksCrc() {
        val p = VitureProtocol.packet(VitureProtocol.MSG_DISPLAY, byteArrayOf(0x34))
        val f = VitureProtocol.parse(p, p.size)
        assertNotNull(f)
        assertEquals(VitureProtocol.MAGIC_COMMAND, f!!.magic)
        assertEquals(VitureProtocol.MSG_DISPLAY, f.msgId)
        assertArrayEquals(byteArrayOf(0x34), f.payload)
        assertTrue(f.crcOk)
        p[18] = 0x31
        assertFalse(VitureProtocol.parse(p, p.size)!!.crcOk)
    }

    @Test fun parseReadsRepliesAndRejectsJunk() {
        val reply = VitureProtocol.packet(VitureProtocol.MSG_GET_VOLUME, byteArrayOf(0, 5))
        reply[1] = VitureProtocol.MAGIC_REPORT.toByte()
        val f = VitureProtocol.parse(reply, reply.size)!!
        assertEquals(VitureProtocol.MAGIC_REPORT, f.magic)
        assertArrayEquals(byteArrayOf(0, 5), f.payload)
        assertNull(VitureProtocol.parse(ByteArray(64), 64))
        assertNull(VitureProtocol.parse(reply, 8))
        val wrongMagic = reply.copyOf().also { it[1] = 0x10 }
        assertNull(VitureProtocol.parse(wrongMagic, wrongMagic.size))
    }

    @Test fun modelsAndModes() {
        val pro = VitureProtocol.model(0x101d, "VITURE Pro XR Glasses")!!
        assertEquals("VITURE Pro XR", pro.name)
        assertEquals(VitureProtocol.Family.PRO, pro.family)
        assertTrue(pro.controllable)
        assertFalse(pro.sbs90)
        assertEquals(0x34, pro.mode(120, false))
        assertEquals(0x33, pro.mode(90, false))
        assertEquals(0x31, pro.mode(60, false))
        assertEquals(0x32, pro.mode(90, true))
        assertEquals(0x32, pro.mode(60, true))

        val luma = VitureProtocol.model(0x1131, null)!!
        assertEquals("VITURE Luma", luma.name)
        assertTrue(luma.sbs90)
        assertEquals(0x44, luma.mode(120, false))
        assertEquals(0x45, luma.mode(90, true))
        assertEquals(0x42, luma.mode(60, true))

        assertFalse(VitureProtocol.model(0x1201, null)!!.controllable)
        assertNull(VitureProtocol.model(0x9999, "VITURE"))
        assertEquals("VITURE One", VitureProtocol.model(0x1011, "Something else")!!.name)
    }

    @Test fun modeHelpers() {
        listOf(0x32, 0x35, 0x42, 0x45).forEach { assertTrue(VitureProtocol.modeIsSbs(it)) }
        listOf(0x31, 0x33, 0x34, 0x41, 0x44).forEach { assertFalse(VitureProtocol.modeIsSbs(it)) }
        assertEquals(120, VitureProtocol.modeHz(0x34))
        assertEquals(90, VitureProtocol.modeHz(0x35))
        assertEquals(60, VitureProtocol.modeHz(0x32))
    }

    @Test fun imuPrefersTheQuaternion() {
        val half = Math.toRadians(30.0) / 2
        val payload = ByteBuffer.allocate(40)
            .putFloat(1f).putFloat(2f).putFloat(3f)
            .putFloat(0f).putFloat(0f)
            .putFloat(cos(half).toFloat()).putFloat(0f).putFloat(0f).putFloat(sin(half).toFloat())
            .array()
        val q = VitureProtocol.imuQuaternion(payload)!!
        assertArrayEquals(floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat()), q, 1e-5f)
    }

    @Test fun imuFallsBackToEuler() {
        val payload = ByteBuffer.allocate(12).putFloat(0f).putFloat(0f).putFloat(30f).array()
        val q = VitureProtocol.imuQuaternion(payload)!!
        val half = Math.toRadians(30.0) / 2
        assertArrayEquals(floatArrayOf(0f, 0f, sin(half).toFloat(), cos(half).toFloat()), q, 1e-5f)
        assertNull(VitureProtocol.imuQuaternion(ByteArray(8)))
        val nan = ByteBuffer.allocate(12).putFloat(Float.NaN).putFloat(0f).putFloat(0f).array()
        assertNull(VitureProtocol.imuQuaternion(nan))
    }
}
