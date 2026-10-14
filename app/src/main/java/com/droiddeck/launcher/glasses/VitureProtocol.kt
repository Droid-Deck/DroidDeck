package com.droiddeck.launcher.glasses

import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

object VitureProtocol {
    const val VENDOR_ID = 0x35CA
    const val PACKET_SIZE = 64
    private const val HEADER = 0x12

    const val MAGIC_COMMAND = 0xFE
    const val MAGIC_REPORT = 0xFD
    const val MAGIC_IMU = 0xFC

    const val MSG_GET_BRIGHTNESS = 0x0005
    const val MSG_BRIGHTNESS = 0x0006
    const val MSG_GET_DISPLAY = 0x0007
    const val MSG_DISPLAY = 0x0008
    const val MSG_FILM = 0x000E
    const val MSG_IMU = 0x0015
    const val MSG_GET_IMU = 0x0017
    const val MSG_IMU_RATE = 0x0018
    const val MSG_GET_VOLUME = 0x0032
    const val MSG_VOLUME = 0x0033

    const val EVENT_BRIGHTNESS = 0x0301
    const val EVENT_DISPLAY = 0x0302
    const val EVENT_VOLUME = 0x0304
    const val EVENT_DISPLAY_STEP = 0x030D

    const val IMU_RATE_240 = 3

    enum class Family { ONE, PRO, LUMA, ULTRA, BEAST }

    class Model(val name: String, val family: Family) {
        val controllable: Boolean get() = family != Family.BEAST
        val tall: Boolean get() = family == Family.LUMA || family == Family.ULTRA
        val sbs90: Boolean get() = family == Family.ULTRA
        val brightnessMax: Int get() = 8
        val volumeMax: Int get() = 8
        val diagonalFovDeg: Float get() = when (family) {
            Family.ONE -> 43f
            Family.PRO -> 46f
            Family.LUMA, Family.ULTRA -> 50f
            Family.BEAST -> 58f
        }

        fun mode(hz: Int, sbs: Boolean): Int = when {
            tall && sbs -> if (hz >= 90 && sbs90) 0x45 else 0x42
            tall -> when { hz >= 120 -> 0x44; hz >= 90 -> 0x43; else -> 0x41 }
            sbs -> if (hz >= 90 && sbs90) 0x35 else 0x32
            else -> when { hz >= 120 -> 0x34; hz >= 90 -> 0x33; else -> 0x31 }
        }

        fun modeForStep(step: Int): Int? = if (tall) null else when (step) {
            1 -> 0x31
            2 -> 0x33
            3 -> 0x34
            4 -> 0x32
            else -> null
        }
    }

    fun model(productId: Int, productName: String?): Model? {
        val family = when (productId) {
            0x1011, 0x1013, 0x1015, 0x1017, 0x101b -> Family.ONE
            0x1019, 0x101d -> Family.PRO
            0x1121, 0x1131, 0x1141, 0x1151 -> Family.LUMA
            0x1101, 0x1104 -> Family.ULTRA
            0x1201, 0x1211 -> Family.BEAST
            else -> return null
        }
        val fallback = when (productId) {
            0x1015, 0x101b -> "VITURE One Lite"
            0x1011, 0x1013, 0x1017 -> "VITURE One"
            0x1019 -> "VITURE Pro"
            0x101d -> "VITURE Pro XR"
            0x1131 -> "VITURE Luma"
            0x1121, 0x1141, 0x1151 -> "VITURE Luma Pro"
            0x1101, 0x1104 -> "VITURE Luma Ultra"
            else -> "VITURE Beast"
        }
        val name = productName?.trim()?.removeSuffix(" Glasses")?.takeIf { it.startsWith("VITURE", ignoreCase = true) } ?: fallback
        return Model(name, family)
    }

    fun modeIsSbs(mode: Int): Boolean = mode == 0x32 || mode == 0x35 || mode == 0x42 || mode == 0x45

    fun modeHz(mode: Int): Int = when (mode) {
        0x34, 0x44 -> 120
        0x33, 0x35, 0x43, 0x45 -> 90
        else -> 60
    }

    private val CRC_TABLE = IntArray(256) { i ->
        var c = i shl 8
        repeat(8) { c = if (c and 0x8000 != 0) (c shl 1) xor 0x1021 else c shl 1 }
        c and 0xFFFF
    }

    fun crc16(data: ByteArray, offset: Int, length: Int): Int {
        var crc = 0
        for (i in offset until offset + length) {
            crc = (CRC_TABLE[((data[i].toInt() and 0xFF) xor (crc ushr 8)) and 0xFF] xor (crc shl 8)) and 0xFFFF
        }
        return crc
    }

    fun packet(msgId: Int, payload: ByteArray = ByteArray(0)): ByteArray {
        require(payload.size <= PACKET_SIZE - HEADER)
        val buf = ByteArray(PACKET_SIZE)
        buf[0] = 0xFF.toByte()
        buf[1] = MAGIC_COMMAND.toByte()
        val len = payload.size + 0x0C
        buf[4] = len.toByte()
        buf[5] = (len shr 8).toByte()
        buf[14] = msgId.toByte()
        buf[15] = (msgId shr 8).toByte()
        payload.copyInto(buf, HEADER)
        val crc = crc16(buf, 4, len + 2)
        buf[2] = crc.toByte()
        buf[3] = (crc shr 8).toByte()
        return buf
    }

    class Frame(val magic: Int, val msgId: Int, val payload: ByteArray, val crcOk: Boolean)

    fun parse(buf: ByteArray, size: Int): Frame? {
        if (size < HEADER || (buf[0].toInt() and 0xFF) != 0xFF) return null
        val magic = buf[1].toInt() and 0xFF
        if (magic != MAGIC_COMMAND && magic != MAGIC_REPORT && magic != MAGIC_IMU) return null
        val len = u16(buf, 4)
        if (len < 0x0C) return null
        val dataLen = (len - 0x0C).coerceAtMost(size - HEADER)
        val crcOk = len + 6 <= size && crc16(buf, 4, len + 2) == u16(buf, 2)
        return Frame(magic, u16(buf, 14), buf.copyOfRange(HEADER, HEADER + dataLen), crcOk)
    }

    private fun u16(buf: ByteArray, at: Int) = (buf[at].toInt() and 0xFF) or ((buf[at + 1].toInt() and 0xFF) shl 8)

    fun beFloat(b: ByteArray, at: Int): Float = java.lang.Float.intBitsToFloat(
        ((b[at].toInt() and 0xFF) shl 24) or ((b[at + 1].toInt() and 0xFF) shl 16) or
            ((b[at + 2].toInt() and 0xFF) shl 8) or (b[at + 3].toInt() and 0xFF),
    )

    fun imuQuaternion(payload: ByteArray): FloatArray? {
        if (payload.size >= 36) {
            val w = beFloat(payload, 20)
            val x = beFloat(payload, 24)
            val y = beFloat(payload, 28)
            val z = beFloat(payload, 32)
            val n = sqrt(w * w + x * x + y * y + z * z)
            if (n.isFinite() && n > 0.9f && n < 1.1f) return floatArrayOf(x / n, y / n, z / n, w / n)
        }
        if (payload.size < 12) return null
        val roll = beFloat(payload, 0)
        val pitch = beFloat(payload, 4)
        val yaw = beFloat(payload, 8)
        if (!roll.isFinite() || !pitch.isFinite() || !yaw.isFinite()) return null
        return eulerZxy(roll, pitch, yaw)
    }

    fun eulerZxy(rollDeg: Float, pitchDeg: Float, yawDeg: Float): FloatArray {
        val r = Math.toRadians(rollDeg.toDouble()) / 2
        val p = Math.toRadians(pitchDeg.toDouble()) / 2
        val y = Math.toRadians(yawDeg.toDouble()) / 2
        val cx = cos(r); val sx = sin(r)
        val cy = cos(p); val sy = sin(p)
        val cz = cos(y); val sz = sin(y)
        return floatArrayOf(
            (sx * cy * cz - cx * sy * sz).toFloat(),
            (cx * sy * cz + sx * cy * sz).toFloat(),
            (cx * cy * sz + sx * sy * cz).toFloat(),
            (cx * cy * cz - sx * sy * sz).toFloat(),
        )
    }
}
