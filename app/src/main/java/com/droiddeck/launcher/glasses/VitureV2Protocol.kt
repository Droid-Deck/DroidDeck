// Adapted from elasticjava/viture-v2, MIT, Copyright (c) 2026 Holger Bartnick.
// Source: ece8c30485f7d357fefa362ae74f3eabac9b3899; see docs/third-party/viture-v2/.
package com.droiddeck.launcher.glasses

import kotlin.math.sqrt

object VitureV2Protocol {
    const val PACKET_SIZE = 64
    const val HEADER_SIZE = 8
    const val RESPONSE_OFFSET = 0x2000
    private const val PREAMBLE = 0x0010
    private const val MAX_PAYLOAD_SIZE = PACKET_SIZE - HEADER_SIZE

    const val MSG_IMU_CONTROL = 0x0301
    const val MSG_GET_SERIAL = 0x3002
    const val MSG_GET_VERSION = 0x3003
    const val MSG_GET_BRIGHTNESS = 0x3122
    const val MSG_GET_DUTY_CYCLE = 0x3125
    const val MSG_GET_DISPLAY = 0x3141
    const val MSG_GET_VOLUME = 0x3201
    const val MSG_GET_WEAR = 0x3321
    const val EVENT_POSE = 0x7308
    const val EVENT_RAW = 0x7309

    const val STREAM_OFF = 0
    const val STREAM_POSE = 1
    const val STREAM_RAW = 2

    enum class Rate(val wireValue: Int, val hz: Int) {
        HZ_60(0, 60), HZ_90(1, 90), HZ_120(2, 120),
        HZ_240(3, 240), HZ_500(4, 500), HZ_1000(5, 1000),
    }

    class Frame internal constructor(val msgId: Int, val payload: ByteArray)

    class Reply internal constructor(val status: Int, val body: ByteArray) {
        val success: Boolean get() = status == 0
    }

    // Device ticks have no established time unit; quaternion is xyzw with no axis remapping.
    class Pose internal constructor(
        val deviceTick: Long,
        val quaternion: FloatArray,
        val unknown: Long,
    )

    class RawSample internal constructor(
        val deviceTick: Long,
        val gyroRadPerSec: FloatArray,
        val accelG: FloatArray,
        val payload: ByteArray,
    )

    fun packet(msgId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(msgId in 0..0xFFFF)
        require(payload.size <= MAX_PAYLOAD_SIZE)
        return ByteArray(HEADER_SIZE + payload.size).also { buffer ->
            putU16(buffer, 0, PREAMBLE)
            putU16(buffer, 2, msgId)
            putU16(buffer, 4, payload.size)
            putU16(buffer, 6, checksum(payload, 0, payload.size))
            payload.copyInto(buffer, HEADER_SIZE)
        }
    }

    fun imuControl(streams: Int, rate: Rate = Rate.HZ_120): ByteArray {
        // Upstream measured that stream mask 3 ACKs but produces no events on Pro 2.
        require(streams == STREAM_OFF || streams == STREAM_POSE || streams == STREAM_RAW)
        require(streams != STREAM_POSE || rate.hz <= 240)
        val wireRate = if (streams == STREAM_OFF) 0 else rate.wireValue
        return packet(MSG_IMU_CONTROL, byteArrayOf(streams.toByte(), wireRate.toByte()))
    }

    fun parse(buffer: ByteArray, size: Int = buffer.size): Frame? {
        if (size < HEADER_SIZE || size > PACKET_SIZE || size > buffer.size) return null
        if (u16(buffer, 0) != PREAMBLE) return null
        val payloadSize = u16(buffer, 4)
        if (payloadSize > MAX_PAYLOAD_SIZE || HEADER_SIZE + payloadSize > size) return null
        if (checksum(buffer, HEADER_SIZE, payloadSize) != u16(buffer, 6)) return null
        return Frame(u16(buffer, 2), buffer.copyOfRange(HEADER_SIZE, HEADER_SIZE + payloadSize))
    }

    fun reply(frame: Frame, requestMsgId: Int): Reply? {
        if (requestMsgId !in 0..0xFFFF || frame.msgId != ((requestMsgId + RESPONSE_OFFSET) and 0xFFFF)) return null
        if (frame.payload.isEmpty()) return null
        return Reply(u8(frame.payload[0]), frame.payload.copyOfRange(1, frame.payload.size))
    }

    fun pose(frame: Frame): Pose? {
        if (frame.msgId != EVENT_POSE || frame.payload.size != 24) return null
        val payload = frame.payload
        val w = float(payload, 8)
        val x = float(payload, 12)
        val y = float(payload, 16)
        val z = float(payload, 20)
        if (!w.isFinite() || !x.isFinite() || !y.isFinite() || !z.isFinite()) return null
        val norm = sqrt(w.toDouble() * w + x.toDouble() * x + y.toDouble() * y + z.toDouble() * z)
        if (norm !in 0.9..1.1) return null
        val n = norm.toFloat()
        return Pose(u32(payload, 4), floatArrayOf(x / n, y / n, z / n, w / n), u32(payload, 0))
    }

    fun raw(frame: Frame): RawSample? {
        if (frame.msgId != EVENT_RAW || frame.payload.size != 56) return null
        val payload = frame.payload
        val gyro = floatArrayOf(float(payload, 10), float(payload, 14), float(payload, 18))
        val accel = floatArrayOf(float(payload, 22), float(payload, 26), float(payload, 30))
        if (gyro.any { !it.isFinite() } || accel.any { !it.isFinite() }) return null
        return RawSample(u32(payload, 4), gyro, accel, payload.copyOf())
    }

    private fun checksum(buffer: ByteArray, offset: Int, size: Int): Int {
        var sum = 0
        for (i in offset until offset + size) sum += u8(buffer[i])
        return sum and 0xFFFF
    }

    private fun u8(value: Byte) = value.toInt() and 0xFF

    private fun u16(buffer: ByteArray, offset: Int) =
        u8(buffer[offset]) or (u8(buffer[offset + 1]) shl 8)

    private fun u32(buffer: ByteArray, offset: Int): Long =
        u16(buffer, offset).toLong() or (u16(buffer, offset + 2).toLong() shl 16)

    private fun float(buffer: ByteArray, offset: Int): Float = Float.fromBits(u32(buffer, offset).toInt())

    private fun putU16(buffer: ByteArray, offset: Int, value: Int) {
        buffer[offset] = value.toByte()
        buffer[offset + 1] = (value ushr 8).toByte()
    }
}
