package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

class XrealProtocolTest {
    @Test fun fragmentedAndCoalescedFramesRetainBoundariesAndDeviceUnits() {
        val bytes = frame(XrealProtocol.REPORT, sensor()) + frame(0x272e, byteArrayOf(1, 2))
        val input = object : ByteArrayInputStream(bytes) {
            override fun read(b: ByteArray, off: Int, len: Int) = super.read(b, off, minOf(3, len))
        }
        val sample = XrealProtocol.sample(XrealProtocol.read(input))!!
        assertEquals(123456789L, sample.timeNs)
        assertEquals(0.5f, sample.gyro.y, 0f)
        assertEquals(9.81f, sample.accel.y, 0f)
        assertArrayEquals(byteArrayOf(1, 2), XrealProtocol.read(input).body)
    }

    @Test fun invalidLengthsAndTruncationFailWithoutUnboundedAllocation() {
        for (size in listOf(-1, Int.MAX_VALUE, XrealProtocol.MAX_BODY + 1)) {
            val bytes = ByteBuffer.allocate(6).putShort(0x2836).putInt(size).array()
            assertThrows(IOException::class.java) { XrealProtocol.read(bytes.inputStream()) }
        }
        assertThrows(IOException::class.java) { XrealProtocol.read(byteArrayOf(0x28).inputStream()) }
        assertThrows(IOException::class.java) { XrealProtocol.read(frame(0x2836, sensor()).dropLast(1).toByteArray().inputStream()) }
    }

    @Test fun onlyCompleteFiniteImuReportsAreAccepted() {
        assertNull(XrealProtocol.sample(XrealProtocol.Frame(0x2836, ByteArray(84))))
        assertNull(XrealProtocol.sample(XrealProtocol.Frame(0x272e, sensor())))
        for ((offset, value) in listOf(24 to 4, 28 to Float.NaN.toRawBits(), 40 to Float.POSITIVE_INFINITY.toRawBits())) {
            val bytes = sensor()
            ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(offset, value)
            assertNull(XrealProtocol.sample(XrealProtocol.Frame(0x2836, bytes)))
        }
    }

    @Test fun commandAndTransactionMustBothMatchBeforeParsingAcknowledgement() {
        val body = ByteBuffer.allocate(6).putInt(7).put(byteArrayOf(0x22, 0)).array()
        val reply = XrealProtocol.Frame(0x271c, body)
        assertNull(XrealProtocol.response(reply, 0x271c, 8))
        assertNull(XrealProtocol.response(reply, 0x275f, 7))
        assertTrue(XrealProtocol.acknowledged(XrealProtocol.response(reply, 0x271c, 7)!!))
        assertFalse(XrealProtocol.acknowledged(byteArrayOf(0x22, 1, 0)))
        assertEquals(5, XrealProtocol.numeric(byteArrayOf(0x22, 2, 0x10, 5)))
        assertNull(XrealProtocol.numeric(byteArrayOf(0x22, 2, 8, 5)))
    }

    @Test fun getAndSetCommandsUseBigEndianHeaderAndProtobufBody() {
        assertArrayEquals(byteArrayOf(0x27, 0x5e, 0, 0, 0, 6, 0x80.toByte(), 0, 0, 1, 0x18, 0),
            XrealProtocol.request(XrealProtocol.GET_DISPLAY, 1))
        assertArrayEquals(byteArrayOf(0x27, 0x1c, 0, 0, 0, 8, 0x80.toByte(), 0, 0, 2, 0x1a, 2, 8, 9),
            XrealProtocol.request(XrealProtocol.SET_BRIGHTNESS, 2, 9))
    }

    @Test fun controlIgnoresUnsolicitedAndStaleRepliesAndRejectsFailure() {
        val output = ByteArrayOutputStream()
        val replies = frame(0x272e, ByteArray(8)) + response(0x275e, 99, byteArrayOf(0x22, 2, 0x10, 4)) +
            response(0x275e, 1, byteArrayOf(0x22, 2, 0x10, 2)) + response(0x271c, 2, byteArrayOf(0x22, 1, 1))
        val control = XrealControl(channels(replies.inputStream(), output))
        assertEquals(2, control.display())
        assertThrows(IOException::class.java) { control.set(0x271c, 5) }
        assertEquals(26, output.size())
    }

    @Test fun unrelatedResponsesCannotKeepControlWaitingForever() {
        val replies = ByteArrayOutputStream().apply { repeat(32) { write(frame(0x272e, ByteArray(8))) } }
        val control = XrealControl(channels(replies.toByteArray().inputStream(), ByteArrayOutputStream()))
        assertThrows(IOException::class.java) { control.display() }
    }

    @Test fun captured1sNativeDisplayResponsePreserves1200LineMode() {
        val reply = byteArrayOf(0x22, 2, 0x10, 0x1d)
        val control = XrealControl(channels(response(0x275e, 1, reply).inputStream(), ByteArrayOutputStream()))
        assertEquals(29, control.display())
        assertEquals(29, XrealModel.mode(60, false))
    }

    @Test fun stereoWaitsForEdidChangeWithoutSendingASecondModeCommandWhileBusy() {
        val output = ByteArrayOutputStream()
        val replies = response(0x2822, 1, byteArrayOf(0x22, 0)) +
            response(0x275e, 2, byteArrayOf(0x22, 2, 0x10, 29)) +
            response(0x275e, 3, byteArrayOf(0x22, 0)) +
            response(0x275e, 4, byteArrayOf(0x22, 2, 0x10, 5))
        assertEquals(5, XrealControl(channels(replies.inputStream(), output), 0).changeDisplay(5))
        val requests = output.toByteArray().inputStream()
        assertEquals(0x2822, XrealProtocol.read(requests).command)
        repeat(3) { assertEquals(0x275e, XrealProtocol.read(requests).command) }
        assertEquals(0, requests.available())
        // Actual 1S busy response remains an error, never a successful ack.
        assertFalse(XrealProtocol.acknowledged(byteArrayOf(0x22, 3, 8, 0x89.toByte(), 0x27)))
    }

    @Test fun stereoCanReturnToNative1200LinesAndAnUnfinishedTransitionIsBounded() {
        val replies = response(0x2822, 1, byteArrayOf(0x22, 0)) +
            response(0x275e, 2, byteArrayOf(0x22, 0)) +
            response(0x275e, 3, byteArrayOf(0x22, 2, 0x10, 29))
        assertEquals(29, XrealControl(channels(replies.inputStream(), ByteArrayOutputStream()), 0).changeDisplay(29))
        val stalled = ByteArrayOutputStream().apply {
            write(response(0x2822, 1, byteArrayOf(0x22, 0)))
            repeat(40) { write(response(0x275e, it + 2, byteArrayOf(0x22, 0))) }
        }
        val control = XrealControl(channels(stalled.toByteArray().inputStream(), ByteArrayOutputStream()), 0)
        assertThrows(IOException::class.java) { control.changeDisplay(5) }
    }

    companion object {
        fun sensor(time: Long = 123456789L): ByteArray = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN)
            .putLong(8, time).putInt(24, 11).putFloat(32, 0.5f).putFloat(44, 9.81f).array()
        fun frame(command: Int, body: ByteArray): ByteArray = ByteBuffer.allocate(6 + body.size)
            .putShort(command.toShort()).putInt(body.size).put(body).array()
        fun response(command: Int, transaction: Int, body: ByteArray) = frame(command,
            ByteBuffer.allocate(4 + body.size).putInt(transaction).put(body).array())
        internal fun channels(input: InputStream, output: ByteArrayOutputStream) = object : XrealChannels {
            override val controlInput = input
            override val controlOutput = output
            override val reports = ByteArrayInputStream(ByteArray(0))
            override fun close() = Unit
        }
    }
}
