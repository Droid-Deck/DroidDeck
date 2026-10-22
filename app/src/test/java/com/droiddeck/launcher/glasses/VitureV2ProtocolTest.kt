// Fixtures adapted from elasticjava/viture-v2, MIT, Copyright (c) 2026 Holger Bartnick.
// Source: ece8c30485f7d357fefa362ae74f3eabac9b3899; see docs/third-party/viture-v2/.
package com.droiddeck.launcher.glasses

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.sqrt

class VitureV2ProtocolTest {
    @Test fun requestsMatchUpstreamWireExamples() {
        assertArrayEquals(hex("10 00 01 03 02 00 03 00 01 02"),
            VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE))
        assertArrayEquals(hex("10 00 01 03 02 00 04 00 02 02"),
            VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_RAW))
        assertArrayEquals(hex("10 00 01 03 02 00 00 00 00 00"),
            VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_OFF))
        assertArrayEquals(hex("10 00 22 31 00 00 00 00"),
            VitureV2Protocol.packet(VitureV2Protocol.MSG_GET_BRIGHTNESS))
    }

    @Test fun requestBoundsAndStreamLimitsAreEnforced() {
        assertThrows(IllegalArgumentException::class.java) { VitureV2Protocol.packet(-1) }
        assertThrows(IllegalArgumentException::class.java) { VitureV2Protocol.packet(0x10000) }
        assertThrows(IllegalArgumentException::class.java) { VitureV2Protocol.packet(0, ByteArray(57)) }
        assertThrows(IllegalArgumentException::class.java) { VitureV2Protocol.imuControl(3) }
        assertThrows(IllegalArgumentException::class.java) {
            VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_POSE, VitureV2Protocol.Rate.HZ_500)
        }
        assertArrayEquals(hex("10 00 01 03 02 00 07 00 02 05"),
            VitureV2Protocol.imuControl(VitureV2Protocol.STREAM_RAW, VitureV2Protocol.Rate.HZ_1000))
        val maximum = VitureV2Protocol.packet(0xFFFF, ByteArray(56) { 0xFF.toByte() })
        assertEquals(64, maximum.size)
        assertEquals(0x37C8, (maximum[6].toInt() and 0xFF) or ((maximum[7].toInt() and 0xFF) shl 8))
        assertNotNull(VitureV2Protocol.parse(maximum))
    }

    @Test fun brightnessReplyMatchesUpstreamAndKeepsFailureStatus() {
        val frame = VitureV2Protocol.parse(hex("10 00 22 51 02 00 03 00 00 03"))!!
        val reply = VitureV2Protocol.reply(frame, VitureV2Protocol.MSG_GET_BRIGHTNESS)!!
        assertTrue(reply.success)
        assertEquals(0, reply.status)
        assertArrayEquals(byteArrayOf(3), reply.body)
        assertNull(VitureV2Protocol.reply(frame, VitureV2Protocol.MSG_GET_VOLUME))
        assertNull(VitureV2Protocol.reply(frame, -1))
        val failure = VitureV2Protocol.parse(hex("10 00 01 23 01 00 07 00 07"))!!
        val failedReply = VitureV2Protocol.reply(failure, VitureV2Protocol.MSG_IMU_CONTROL)!!
        assertFalse(failedReply.success)
        assertEquals(7, failedReply.status)
        assertTrue(failedReply.body.isEmpty())
        val noStatus = VitureV2Protocol.parse(hex("10 00 01 23 00 00 00 00"))!!
        assertNull(VitureV2Protocol.reply(noStatus, VitureV2Protocol.MSG_IMU_CONTROL))
    }

    @Test fun validatesReceiveBoundsBeforeReadingHeader() {
        val valid = hex("10 00 22 51 02 00 03 00 00 03")
        for (size in listOf(-1, 0, 7, 9, 11, 65, Int.MAX_VALUE)) {
            assertNull("size=$size", VitureV2Protocol.parse(valid, size))
        }
        assertNull(VitureV2Protocol.parse(ByteArray(0)))
        assertNull(VitureV2Protocol.parse(valid.copyOf(65)))
        assertNull(VitureV2Protocol.parse(valid.copyOf().also { it[0] = 0x11 }))
        assertNull(VitureV2Protocol.parse(valid.copyOf().also { it[1] = 1 }))
        assertNull(VitureV2Protocol.parse(valid.copyOf().also { it[6] = 0xFF.toByte() }))
        assertNull(VitureV2Protocol.parse(valid.copyOf().also { it[9] = 4 }))
    }

    @Test fun rejectsOversizedAndTruncatedDeclaredPayloads() {
        val valid = hex("10 00 22 51 02 00 03 00 00 03")
        assertNull(VitureV2Protocol.parse(valid.copyOf(9)))
        val tooLong = valid.copyOf(64).also { it[4] = 57 }
        assertNull(VitureV2Protocol.parse(tooLong))
        tooLong[4] = 0xFF.toByte()
        tooLong[5] = 0xFF.toByte()
        assertNull(VitureV2Protocol.parse(tooLong))
        val padded = valid.copyOf(64)
        val parsed = VitureV2Protocol.parse(padded)!!
        assertArrayEquals(byteArrayOf(0, 3), parsed.payload)
        padded[9] = 8
        assertArrayEquals(byteArrayOf(0, 3), parsed.payload)
    }

    @Test fun poseFixturePreservesDeviceCounterAndConvertsWxyzToNormalizedXyzw() {
        val frame = VitureV2Protocol.parse(hex(
            "10 00 08 73 18 00 9c 08 " +
                "26 01 00 00 59 d6 21 00 dd e3 2b 3e 52 5d 7c 3f dd 45 9a 3b e8 27 52 3a"
        ))!!
        val pose = VitureV2Protocol.pose(frame)!!
        assertEquals(0x0021D659L, pose.deviceTick)
        assertEquals(0x126L, pose.unknown)
        val expected = floatArrayOf(Float.fromBits(0x3F7C5D52), Float.fromBits(0x3B9A45DD),
            Float.fromBits(0x3A5227E8), Float.fromBits(0x3E2BE3DD))
        val norm = sqrt(expected.sumOf { it.toDouble() * it }).toFloat()
        assertArrayEquals(expected.map { it / norm }.toFloatArray(), pose.quaternion, 1e-7f)
        assertEquals(1.0, pose.quaternion.sumOf { it.toDouble() * it }, 1e-6)
    }

    @Test fun poseNormalizesSmallScaleErrorsWithoutChangingAxesOrCounter() {
        val payload = posePayload(0.5f, -0.5f, 0.5f, -0.5f)
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putInt(4, -1)
        val pose = VitureV2Protocol.pose(event(VitureV2Protocol.EVENT_POSE, payload))!!
        assertEquals(0xFFFFFFFFL, pose.deviceTick)
        assertArrayEquals(floatArrayOf(-0.5f, 0.5f, -0.5f, 0.5f), pose.quaternion, 0f)
        val scaled = VitureV2Protocol.pose(event(VitureV2Protocol.EVENT_POSE, posePayload(1.01f, 0f, 0f, 0f)))!!
        assertArrayEquals(floatArrayOf(0f, 0f, 0f, 1f), scaled.quaternion, 1e-7f)
    }

    @Test fun rejectsMalformedPoseAndInvalidQuaternions() {
        val valid = posePayload(1f, 0f, 0f, 0f)
        assertNull(VitureV2Protocol.pose(event(VitureV2Protocol.EVENT_RAW, valid)))
        for (size in listOf(0, 23, 25, 56)) {
            assertNull(VitureV2Protocol.pose(event(VitureV2Protocol.EVENT_POSE, valid.copyOf(size))))
        }
        for (bad in listOf(0f, 0.5f, 2f, Float.MAX_VALUE, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
            assertNull("w=$bad", VitureV2Protocol.pose(event(VitureV2Protocol.EVENT_POSE, posePayload(bad, 0f, 0f, 0f))))
        }
        for (component in 1..3) {
            val payload = valid.copyOf()
            ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putFloat(8 + component * 4, Float.NaN)
            assertNull(VitureV2Protocol.pose(event(VitureV2Protocol.EVENT_POSE, payload)))
        }
    }

    @Test fun rawReadsUnalignedFloatsAndPreservesUnknownBytes() {
        val payload = rawPayload()
        val frame = event(VitureV2Protocol.EVENT_RAW, payload)
        val sample = VitureV2Protocol.raw(frame)!!
        assertEquals(0xFFFFFFFEL, sample.deviceTick)
        assertArrayEquals(floatArrayOf(1.5f, -2f, 0.25f), sample.gyroRadPerSec, 0f)
        assertArrayEquals(floatArrayOf(0.0024f, -0.3221f, -0.9287f), sample.accelG, 0f)
        assertArrayEquals(payload, sample.payload)
        frame.payload.fill(0)
        assertArrayEquals(payload, sample.payload)
    }

    @Test fun rejectsMalformedRawAndNonfiniteMeasurementsButKeepsOpaqueTail() {
        val payload = rawPayload()
        assertNull(VitureV2Protocol.raw(event(VitureV2Protocol.EVENT_POSE, payload)))
        for (size in listOf(0, 24, 33, 34, 55)) {
            assertNull(VitureV2Protocol.raw(event(VitureV2Protocol.EVENT_RAW, payload.copyOf(size))))
        }
        for (offset in listOf(10, 14, 18, 22, 26, 30)) {
            for (bad in listOf(Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY)) {
                val invalid = payload.copyOf()
                ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putFloat(offset, bad)
                assertNull("offset=$offset, value=$bad", VitureV2Protocol.raw(event(VitureV2Protocol.EVENT_RAW, invalid)))
            }
        }
        ByteBuffer.wrap(payload).order(ByteOrder.LITTLE_ENDIAN).putFloat(46, Float.NaN)
        assertNotNull(VitureV2Protocol.raw(event(VitureV2Protocol.EVENT_RAW, payload)))
    }

    private fun event(msgId: Int, payload: ByteArray) =
        VitureV2Protocol.parse(VitureV2Protocol.packet(msgId, payload))!!

    private fun posePayload(w: Float, x: Float, y: Float, z: Float) =
        ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
            .putInt(0x126).putInt(123).putFloat(w).putFloat(x).putFloat(y).putFloat(z).array()

    private fun rawPayload() = ByteBuffer.allocate(56).order(ByteOrder.LITTLE_ENDIAN)
        .putInt(0x126).putInt(-2).putShort(187)
        .putFloat(1.5f).putFloat(-2f).putFloat(0.25f)
        .putFloat(0.0024f).putFloat(-0.3221f).putFloat(-0.9287f)
        .putFloat(117.30f).putFloat(60.75f).putFloat(254.85f)
        .put(byteArrayOf(1, 2, 3, 4, 5, 6, 7, 8, 9, 10)).array()

    private fun hex(value: String) = value.split(' ').map { it.toInt(16).toByte() }.toByteArray()
}
