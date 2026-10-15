package com.droiddeck.launcher.glasses

import java.io.EOFException
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Independent implementation of the One-family wire format; see xreal-investigation.md. */
internal object XrealProtocol {
    const val HOST = "169.254.2.1"
    const val REPORT_PORT = 52998
    const val CONTROL_PORT = 52999
    const val REPORT = 0x2836
    const val GET_DISPLAY = 0x275e
    const val SET_DISPLAY = 0x275f
    const val SET_INPUT = 0x2822
    const val SET_BRIGHTNESS = 0x271c
    const val SET_DIMMER = 0x2727
    const val MAX_BODY = 64 * 1024

    data class Frame(val command: Int, val body: ByteArray)
    data class Sample(val timeNs: Long, val gyro: Vec3, val accel: Vec3)

    fun read(input: InputStream): Frame {
        val deadline = System.nanoTime() + 3_000_000_000L
        val header = ByteArray(6)
        input.readFully(header, deadline)
        val size = ByteBuffer.wrap(header, 2, 4).int
        if (size !in 0..MAX_BODY) throw IOException("Invalid XREAL frame length: $size")
        return Frame(((header[0].toInt() and 255) shl 8) or (header[1].toInt() and 255),
            ByteArray(size).also { input.readFully(it, deadline) })
    }

    private fun InputStream.readFully(bytes: ByteArray, deadline: Long) {
        var at = 0
        while (at < bytes.size) {
            if (System.nanoTime() > deadline) throw IOException("XREAL frame timed out")
            val count = read(bytes, at, bytes.size - at)
            if (count < 0) throw EOFException("XREAL stream closed mid-frame")
            if (count == 0) throw IOException("XREAL stream made no progress")
            at += count
        }
    }

    fun sample(frame: Frame): Sample? {
        if (frame.command != REPORT || frame.body.size != 128) return null
        val b = ByteBuffer.wrap(frame.body).order(ByteOrder.LITTLE_ENDIAN)
        if (b.getInt(24) != 11) return null // Magnetometer reports are a different type.
        val time = b.getLong(8)
        val gyro = Vec3(b.getFloat(28), b.getFloat(32), b.getFloat(36))
        val accel = Vec3(b.getFloat(40), b.getFloat(44), b.getFloat(48))
        if (time <= 0 || !gyro.valid(100f) || !accel.valid(200f)) return null
        return Sample(time, gyro, accel)
    }

    private fun Vec3.valid(limit: Float) = listOf(x, y, z).all { it.isFinite() && kotlin.math.abs(it) <= limit }

    fun request(command: Int, transaction: Int, value: Int? = null): ByteArray {
        require(transaction > 0)
        require(value == null || value in 0..127)
        val payload = if (value == null) byteArrayOf(0x18, 0) else byteArrayOf(0x1a, 2, 8, value.toByte())
        return ByteBuffer.allocate(10 + payload.size).putShort(command.toShort())
            .putInt(4 + payload.size).putInt(transaction or Int.MIN_VALUE).put(payload).array()
    }

    /** No acknowledgement is inferred from a write; match both command and transaction. */
    fun response(frame: Frame, command: Int, transaction: Int): ByteArray? {
        if (frame.command != command || frame.body.size < 4 ||
            ByteBuffer.wrap(frame.body).int != transaction) return null
        return frame.body.copyOfRange(4, frame.body.size)
    }

    fun acknowledged(payload: ByteArray) = payload.contentEquals(byteArrayOf(0x22, 0))
    fun numeric(payload: ByteArray): Int? = if (payload.size == 4 &&
        payload[0] == 0x22.toByte() && payload[1] == 2.toByte() && payload[2] == 0x10.toByte() &&
        payload[3].toInt() in 0..127) payload[3].toInt() else null
}
