package com.droiddeck.launcher.input.bt

import java.util.zip.CRC32
import kotlin.math.roundToInt

class BtPadState {
    @JvmField var buttons = 0
    @JvmField var lx = 0f
    @JvmField var ly = 0f
    @JvmField var rx = 0f
    @JvmField var ry = 0f
    @JvmField var lt = 0f
    @JvmField var rt = 0f
    @JvmField var lpx = 0f
    @JvmField var lpy = 0f
    @JvmField var rpx = 0f
    @JvmField var rpy = 0f

    fun isDown(bit: Int) = buttons and bit != 0

    fun set(other: BtPadState) {
        buttons = other.buttons; lx = other.lx; ly = other.ly; rx = other.rx; ry = other.ry; lt = other.lt; rt = other.rt
        lpx = other.lpx; lpy = other.lpy; rpx = other.rpx; rpy = other.rpy
    }

    fun merge(a: BtPadState, b: BtPadState) {
        buttons = a.buttons or b.buttons
        lx = stronger(a.lx, b.lx); ly = stronger(a.ly, b.ly)
        rx = stronger(a.rx, b.rx); ry = stronger(a.ry, b.ry)
        lt = maxOf(a.lt, b.lt, if (isDown(LT)) 1f else 0f)
        rt = maxOf(a.rt, b.rt, if (isDown(RT)) 1f else 0f)
        val left = if (b.isDown(LPAD_TOUCH)) b else a
        lpx = left.lpx; lpy = left.lpy
        val right = if (b.isDown(RPAD_TOUCH)) b else a
        rpx = right.rpx; rpy = right.rpy
    }

    fun sameAs(o: BtPadState) = buttons == o.buttons && lx == o.lx && ly == o.ly && rx == o.rx && ry == o.ry && lt == o.lt && rt == o.rt &&
        lpx == o.lpx && lpy == o.lpy && rpx == o.rpx && rpy == o.rpy

    fun hat(): Int {
        val x = (if (isDown(RIGHT)) 1 else 0) - (if (isDown(LEFT)) 1 else 0)
        val y = (if (isDown(DOWN)) 1 else 0) - (if (isDown(UP)) 1 else 0)
        return when {
            x == 0 && y == -1 -> 0
            x == 1 && y == -1 -> 1
            x == 1 && y == 0 -> 2
            x == 1 && y == 1 -> 3
            x == 0 && y == 1 -> 4
            x == -1 && y == 1 -> 5
            x == -1 && y == 0 -> 6
            x == -1 && y == -1 -> 7
            else -> -1
        }
    }

    companion object {
        const val A = 1 shl 0
        const val B = 1 shl 1
        const val X = 1 shl 2
        const val Y = 1 shl 3
        const val LB = 1 shl 4
        const val RB = 1 shl 5
        const val LT = 1 shl 6
        const val RT = 1 shl 7
        const val SELECT = 1 shl 8
        const val START = 1 shl 9
        const val GUIDE = 1 shl 10
        const val L3 = 1 shl 11
        const val R3 = 1 shl 12
        const val UP = 1 shl 13
        const val DOWN = 1 shl 14
        const val LEFT = 1 shl 15
        const val RIGHT = 1 shl 16
        const val L4 = 1 shl 17
        const val R4 = 1 shl 18
        const val L5 = 1 shl 19
        const val R5 = 1 shl 20
        const val TOUCHPAD = 1 shl 21
        const val SHARE = 1 shl 22
        const val LPAD_TOUCH = 1 shl 23
        const val RPAD_TOUCH = 1 shl 24
        const val LPAD_CLICK = 1 shl 25
        const val RPAD_CLICK = 1 shl 26

        private fun stronger(a: Float, b: Float) = if (Math.abs(b) > Math.abs(a)) b else a
    }
}

class BtFeedback(val strong: Int, val weak: Int, val lightbar: Int? = null)

enum class BtPadProfile(val id: String, val vendorId: Int, val productId: Int, val version: Int) {
    GENERIC("generic", 0x1209, 0x0001, 0x0100),
    XBOX("xbox", 0x045E, 0x0B13, 0x0509),
    DUALSHOCK4("dualshock4", 0x054C, 0x09CC, 0x0100),
    DUALSENSE("dualsense", 0x054C, 0x0CE6, 0x0100),
    STEAM("steam", 0x28DE, 0x1303, 0x0100);

    fun encoder(): BtPadEncoder = when (this) {
        GENERIC -> GenericEncoder()
        XBOX -> XboxEncoder()
        DUALSHOCK4 -> DualShock4Encoder()
        DUALSENSE -> DualSenseEncoder()
        STEAM -> SteamControllerEncoder()
    }

    companion object {
        fun byId(id: String?) = entries.firstOrNull { it.id == id } ?: GENERIC
    }
}

abstract class BtPadEncoder {
    abstract val descriptor: ByteArray
    abstract val reports: List<Pair<Int, Int>>
    abstract fun inputId(): Int
    abstract fun input(s: BtPadState): ByteArray
    open val periodMs = 0L
    open fun feature(id: Int): ByteArray? = null
    open fun setFeature(id: Int, data: ByteArray) {}
    open fun output(id: Int, data: ByteArray): BtFeedback? = null

    protected fun axis8(v: Float) = ((v.coerceIn(-1f, 1f) + 1f) * 127.5f).roundToInt().coerceIn(0, 255)
    protected fun trigger8(v: Float) = (v.coerceIn(0f, 1f) * 255f).roundToInt()

    protected fun ByteArray.le16(at: Int, v: Int) { this[at] = v.toByte(); this[at + 1] = (v shr 8).toByte() }
    protected fun ByteArray.le32(at: Int, v: Long) { for (i in 0 until 4) this[at + i] = (v shr (8 * i)).toByte() }

    protected fun ByteArray.sealCrc(seed: Int, id: Int): ByteArray {
        val crc = CRC32()
        crc.update(seed)
        crc.update(id)
        crc.update(this, 0, size - 4)
        le32(size - 4, crc.value)
        return this
    }


    companion object {
        const val INPUT = 1
        const val OUTPUT = 2
        const val FEATURE = 3

        fun bytes(vararg v: Int) = ByteArray(v.size) { v[it].toByte() }
    }
}

private class GenericEncoder : BtPadEncoder() {
    override val reports = listOf(1 to INPUT)

    override val descriptor = bytes(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x18, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x18, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x65, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x03,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x05, 0x02, 0x09, 0xC5, 0x09, 0xC4, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0xC0,
    )

    override fun inputId() = 1

    override fun input(s: BtPadState): ByteArray {
        var b = 0
        GENERIC_ORDER.forEachIndexed { i, bit -> if (s.isDown(bit)) b = b or (1 shl i) }
        val hat = s.hat().let { if (it < 0) 8 else it }
        return bytes(
            b and 0xFF, (b shr 8) and 0xFF, (b shr 16) and 0xFF, hat,
            axis8(s.lx), axis8(s.ly), axis8(s.rx), axis8(s.ry), trigger8(s.lt), trigger8(s.rt),
        )
    }

    companion object {
        private val GENERIC_ORDER = intArrayOf(
            BtPadState.A, BtPadState.B, 0, BtPadState.X, BtPadState.Y, 0, BtPadState.LB, BtPadState.RB,
            BtPadState.LT, BtPadState.RT, BtPadState.SELECT, BtPadState.START, BtPadState.GUIDE, BtPadState.L3, BtPadState.R3, 0,
            BtPadState.L4, BtPadState.R4, BtPadState.L5, BtPadState.R5, BtPadState.TOUCHPAD, BtPadState.SHARE, 0, 0,
        )
    }
}

private class XboxEncoder : BtPadEncoder() {
    override val reports = listOf(1 to INPUT, 3 to OUTPUT)

    override val descriptor = bytes(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01,
        0x09, 0x01, 0xA1, 0x00, 0x09, 0x30, 0x09, 0x31, 0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x95, 0x02, 0x75, 0x10, 0x81, 0x02, 0xC0,
        0x09, 0x01, 0xA1, 0x00, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x27, 0xFF, 0xFF, 0x00, 0x00, 0x95, 0x02, 0x75, 0x10, 0x81, 0x02, 0xC0,
        0x05, 0x02, 0x09, 0xC5, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x95, 0x01, 0x75, 0x0A, 0x81, 0x02, 0x15, 0x00, 0x25, 0x00, 0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x02, 0x09, 0xC4, 0x15, 0x00, 0x26, 0xFF, 0x03, 0x95, 0x01, 0x75, 0x0A, 0x81, 0x02, 0x15, 0x00, 0x25, 0x00, 0x75, 0x06, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x01, 0x09, 0x39, 0x15, 0x01, 0x25, 0x08, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x66, 0x14, 0x00, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42,
        0x75, 0x04, 0x95, 0x01, 0x15, 0x00, 0x25, 0x00, 0x35, 0x00, 0x45, 0x00, 0x65, 0x00, 0x81, 0x03,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0F, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0F, 0x81, 0x02,
        0x15, 0x00, 0x25, 0x00, 0x75, 0x01, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x0C, 0x0A, 0xB2, 0x00, 0x15, 0x00, 0x25, 0x01, 0x95, 0x01, 0x75, 0x01, 0x81, 0x02,
        0x15, 0x00, 0x25, 0x00, 0x75, 0x07, 0x95, 0x01, 0x81, 0x03,
        0x05, 0x0F, 0x09, 0x21, 0x85, 0x03, 0xA1, 0x02,
        0x09, 0x97, 0x15, 0x00, 0x25, 0x01, 0x75, 0x04, 0x95, 0x01, 0x91, 0x02,
        0x15, 0x00, 0x25, 0x00, 0x75, 0x04, 0x95, 0x01, 0x91, 0x03,
        0x09, 0x70, 0x15, 0x00, 0x25, 0x64, 0x75, 0x08, 0x95, 0x04, 0x91, 0x02,
        0x09, 0x50, 0x66, 0x01, 0x10, 0x55, 0x0E, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02,
        0x09, 0xA7, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02,
        0x65, 0x00, 0x55, 0x00, 0x09, 0x7C, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x01, 0x91, 0x02,
        0xC0, 0xC0,
    )

    override fun inputId() = 1

    override fun input(s: BtPadState): ByteArray {
        val r = ByteArray(16)
        r.le16(0, axis16(s.lx)); r.le16(2, axis16(s.ly)); r.le16(4, axis16(s.rx)); r.le16(6, axis16(s.ry))
        r.le16(8, (s.lt.coerceIn(0f, 1f) * 1023f).roundToInt())
        r.le16(10, (s.rt.coerceIn(0f, 1f) * 1023f).roundToInt())
        r[12] = (s.hat() + 1).toByte()
        var b = 0
        XBOX_ORDER.forEachIndexed { i, bit -> if (bit != 0 && s.isDown(bit)) b = b or (1 shl i) }
        r.le16(13, b)
        r[15] = if (s.isDown(BtPadState.SHARE)) 1 else 0
        return r
    }

    override fun output(id: Int, data: ByteArray): BtFeedback? {
        if (id != 3) return null
        val d = data
        if (d.size < 5) return null
        val enable = d[0].toInt()
        val strong = if (enable and 0x02 != 0) d[3].toInt() and 0xFF else 0
        val weak = if (enable and 0x01 != 0) d[4].toInt() and 0xFF else 0
        return BtFeedback(strong * 255 / 100, weak * 255 / 100)
    }

    private fun axis16(v: Float) = ((v.coerceIn(-1f, 1f) + 1f) * 32767.5f).roundToInt().coerceIn(0, 65535)

    companion object {
        private val XBOX_ORDER = intArrayOf(
            BtPadState.A, BtPadState.B, 0, BtPadState.X, BtPadState.Y, 0, BtPadState.LB, BtPadState.RB,
            0, 0, BtPadState.SELECT, BtPadState.START, BtPadState.GUIDE, BtPadState.L3, BtPadState.R3,
        )
    }
}

private abstract class SonyEncoder : BtPadEncoder() {
    @Volatile protected var enhanced = false
    protected var counter = 0
    protected var timestamp = 0L

    override val periodMs = 8L

    protected fun buttons(s: BtPadState): IntArray {
        val hat = s.hat().let { if (it < 0) 8 else it }
        var b0 = hat
        if (s.isDown(BtPadState.X)) b0 = b0 or 0x10
        if (s.isDown(BtPadState.A)) b0 = b0 or 0x20
        if (s.isDown(BtPadState.B)) b0 = b0 or 0x40
        if (s.isDown(BtPadState.Y)) b0 = b0 or 0x80
        var b1 = 0
        if (s.isDown(BtPadState.LB)) b1 = b1 or 0x01
        if (s.isDown(BtPadState.RB)) b1 = b1 or 0x02
        if (s.lt > 0.05f) b1 = b1 or 0x04
        if (s.rt > 0.05f) b1 = b1 or 0x08
        if (s.isDown(BtPadState.SELECT) || s.isDown(BtPadState.SHARE)) b1 = b1 or 0x10
        if (s.isDown(BtPadState.START)) b1 = b1 or 0x20
        if (s.isDown(BtPadState.L3)) b1 = b1 or 0x40
        if (s.isDown(BtPadState.R3)) b1 = b1 or 0x80
        var b2 = 0
        if (s.isDown(BtPadState.GUIDE)) b2 = b2 or 0x01
        if (s.isDown(BtPadState.TOUCHPAD)) b2 = b2 or 0x02
        return intArrayOf(b0, b1, b2)
    }

    protected fun simple(s: BtPadState): ByteArray {
        val b = buttons(s)
        counter = (counter + 1) and 0x3F
        return bytes(axis8(s.lx), axis8(s.ly), axis8(s.rx), axis8(s.ry), b[0], b[1], b[2] or (counter shl 2), trigger8(s.lt), trigger8(s.rt))
    }

    protected fun calibration(): ByteArray {
        val r = ByteArray(40)
        val v = intArrayOf(0, 0, 0, GYRO, -GYRO, GYRO, -GYRO, GYRO, -GYRO, GYRO_SPEED, GYRO_SPEED, ACCEL, -ACCEL, ACCEL, -ACCEL, ACCEL, -ACCEL)
        v.forEachIndexed { i, x -> r.le16(i * 2, x) }
        return r.sealCrc(FEATURE_SEED, 0x05)
    }

    override fun feature(id: Int): ByteArray? {
        val reply = reply(id) ?: return null
        enhanced = true
        return reply
    }

    protected abstract fun reply(id: Int): ByteArray?

    companion object {
        const val INPUT_SEED = 0xA1
        const val OUTPUT_SEED = 0xA2
        const val FEATURE_SEED = 0xA3
        const val GYRO = 8800
        const val GYRO_SPEED = 540
        const val ACCEL = 8192
    }
}

private class DualShock4Encoder : SonyEncoder() {
    override val reports = listOf(0x01 to INPUT, 0x11 to INPUT, 0x11 to OUTPUT, 0x02 to FEATURE, 0xA3 to FEATURE, 0x05 to FEATURE, 0x12 to FEATURE)

    override val descriptor = bytes(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x65, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0E, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0E, 0x81, 0x02,
        0x06, 0x00, 0xFF, 0x09, 0x20, 0x75, 0x06, 0x95, 0x01, 0x15, 0x00, 0x25, 0x7F, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0x06, 0x04, 0xFF,
        0x85, 0x02, 0x09, 0x24, 0x95, 0x24, 0xB1, 0x02,
        0x85, 0xA3, 0x09, 0x25, 0x95, 0x30, 0xB1, 0x02,
        0x85, 0x05, 0x09, 0x26, 0x95, 0x28, 0xB1, 0x02,
        0x85, 0x12, 0x09, 0x27, 0x95, 0x0F, 0xB1, 0x02,
        0x85, 0x11, 0x09, 0x22, 0x95, 0x4D, 0x81, 0x02,
        0x09, 0x23, 0x95, 0x4D, 0x91, 0x02,
        0xC0,
    )

    override fun inputId() = if (enhanced) 0x11 else 0x01

    override fun input(s: BtPadState): ByteArray {
        if (!enhanced) return simple(s)
        val r = ByteArray(77)
        r[0] = 0xC0.toByte()
        val b = buttons(s)
        counter = (counter + 1) and 0x3F
        timestamp += 1500
        val c = 2
        r[c] = axis8(s.lx).toByte(); r[c + 1] = axis8(s.ly).toByte(); r[c + 2] = axis8(s.rx).toByte(); r[c + 3] = axis8(s.ry).toByte()
        r[c + 4] = b[0].toByte(); r[c + 5] = b[1].toByte(); r[c + 6] = (b[2] or (counter shl 2)).toByte()
        r[c + 7] = trigger8(s.lt).toByte(); r[c + 8] = trigger8(s.rt).toByte()
        r.le16(c + 9, timestamp.toInt())
        r.le16(c + 20, ACCEL)
        r[c + 29] = 0x09
        r[c + 32] = 1
        r[c + 34] = 0x80.toByte()
        r[c + 38] = 0x80.toByte()
        return r.sealCrc(INPUT_SEED, 0x11)
    }

    override fun reply(id: Int): ByteArray? = when (id) {
        0x02 -> ByteArray(36).also { r ->
            val v = intArrayOf(0, 0, 0, GYRO, GYRO, GYRO, -GYRO, -GYRO, -GYRO, GYRO_SPEED, GYRO_SPEED, ACCEL, -ACCEL, ACCEL, -ACCEL, ACCEL, -ACCEL)
            v.forEachIndexed { i, x -> r.le16(i * 2, x) }
        }
        0x05 -> calibration()
        0xA3 -> ByteArray(48).also { r ->
            "Sep 21 2018".toByteArray().copyInto(r, 0)
            "04:50:51".toByteArray().copyInto(r, 16)
            r.le16(34, 0x0100)
            r.le16(40, 0xA00A)
        }
        0x12 -> ByteArray(15).also { r -> FAKE_MAC.copyInto(r, 0); r[6] = 0x08; r[7] = 0x25 }
        else -> null
    }

    override fun output(id: Int, data: ByteArray): BtFeedback? {
        if (id != 0x11) return null
        enhanced = true
        val d = data
        if (d.size < 10) return null
        val flags = d[2].toInt()
        val light = if (flags and 0x02 != 0) rgb(d[7], d[8], d[9]) else null
        return BtFeedback(d[6].toInt() and 0xFF, d[5].toInt() and 0xFF, light)
    }
}

private class DualSenseEncoder : SonyEncoder() {
    override val reports = listOf(0x01 to INPUT, 0x31 to INPUT, 0x31 to OUTPUT, 0x05 to FEATURE, 0x09 to FEATURE, 0x20 to FEATURE)

    override val descriptor = bytes(
        0x05, 0x01, 0x09, 0x05, 0xA1, 0x01, 0x85, 0x01,
        0x09, 0x30, 0x09, 0x31, 0x09, 0x32, 0x09, 0x35, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x04, 0x81, 0x02,
        0x09, 0x39, 0x15, 0x00, 0x25, 0x07, 0x35, 0x00, 0x46, 0x3B, 0x01, 0x65, 0x14, 0x75, 0x04, 0x95, 0x01, 0x81, 0x42, 0x65, 0x00,
        0x05, 0x09, 0x19, 0x01, 0x29, 0x0E, 0x15, 0x00, 0x25, 0x01, 0x75, 0x01, 0x95, 0x0E, 0x81, 0x02,
        0x06, 0x00, 0xFF, 0x09, 0x20, 0x75, 0x06, 0x95, 0x01, 0x15, 0x00, 0x25, 0x7F, 0x81, 0x02,
        0x05, 0x01, 0x09, 0x33, 0x09, 0x34, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08, 0x95, 0x02, 0x81, 0x02,
        0x06, 0x00, 0xFF,
        0x85, 0x31, 0x09, 0x31, 0x95, 0x4D, 0x81, 0x02,
        0x09, 0x32, 0x95, 0x4D, 0x91, 0x02,
        0x85, 0x05, 0x09, 0x33, 0x95, 0x28, 0xB1, 0x02,
        0x85, 0x09, 0x09, 0x34, 0x95, 0x13, 0xB1, 0x02,
        0x85, 0x20, 0x09, 0x35, 0x95, 0x3F, 0xB1, 0x02,
        0xC0,
    )

    private var sequence = 0

    override fun inputId() = if (enhanced) 0x31 else 0x01

    override fun input(s: BtPadState): ByteArray {
        if (!enhanced) return simple(s)
        val r = ByteArray(77)
        sequence = (sequence + 1) and 0xFF
        timestamp += 24000
        r[0] = 0x10
        val c = 1
        r[c] = axis8(s.lx).toByte(); r[c + 1] = axis8(s.ly).toByte(); r[c + 2] = axis8(s.rx).toByte(); r[c + 3] = axis8(s.ry).toByte()
        r[c + 4] = trigger8(s.lt).toByte(); r[c + 5] = trigger8(s.rt).toByte()
        r[c + 6] = sequence.toByte()
        val b = buttons(s)
        r[c + 7] = b[0].toByte(); r[c + 8] = b[1].toByte(); r[c + 9] = b[2].toByte()
        r.le16(c + 23, ACCEL)
        r.le32(c + 27, timestamp)
        r[c + 32] = 0x80.toByte()
        r[c + 36] = 0x80.toByte()
        r[c + 52] = 0x09
        return r.sealCrc(INPUT_SEED, 0x31)
    }

    override fun reply(id: Int): ByteArray? = when (id) {
        0x05 -> calibration()
        0x09 -> ByteArray(19).also { r -> FAKE_MAC.copyInto(r, 0) }.sealCrc(FEATURE_SEED, 0x09)
        0x20 -> ByteArray(63).also { r ->
            "Jun 19 2023".toByteArray().copyInto(r, 0)
            "14:47:34".toByteArray().copyInto(r, 11)
            r.le16(19, 0x0003)
            r.le32(23, 0x00000615L)
            r.le32(27, 0x0110002AL)
            r.le16(43, 0x0630)
        }.sealCrc(FEATURE_SEED, 0x20)
        else -> null
    }

    override fun output(id: Int, data: ByteArray): BtFeedback? {
        if (id != 0x31) return null
        enhanced = true
        val d = data
        if (d.size < 49) return null
        val flags0 = d[2].toInt()
        val flags1 = d[3].toInt()
        val light = if (flags1 and 0x04 != 0) rgb(d[46], d[47], d[48]) else null
        return if (flags0 and 0x03 != 0) BtFeedback(d[5].toInt() and 0xFF, d[4].toInt() and 0xFF, light)
        else if (light != null) BtFeedback(-1, -1, light) else null
    }
}

private class SteamControllerEncoder : BtPadEncoder() {
    override val descriptor = bytes(
        0x06, 0x00, 0xFF, 0x09, 0x01, 0xA1, 0x01, 0x15, 0x00, 0x26, 0xFF, 0x00, 0x75, 0x08,
        0x85, 0x45, 0x09, 0x02, 0x95, 0x2D, 0x81, 0x02, 0x85, 0x47, 0x09, 0x03, 0x95, 0x2D, 0x81, 0x02,
        0x85, 0x80, 0x09, 0x04, 0x95, 0x09, 0x91, 0x02, 0x85, 0x81, 0x09, 0x05, 0x95, 0x07, 0x91, 0x02,
        0x85, 0x82, 0x09, 0x06, 0x95, 0x03, 0x91, 0x02, 0x85, 0x83, 0x09, 0x07, 0x95, 0x09, 0x91, 0x02,
        0x85, 0x84, 0x09, 0x08, 0x95, 0x08, 0x91, 0x02, 0x85, 0x85, 0x09, 0x09, 0x95, 0x03, 0x91, 0x02,
        0x85, 0x01, 0x09, 0x0A, 0x95, 0x3F, 0xB1, 0x02, 0xC0,
    )
    override val reports = listOf(0x45 to INPUT, 0x47 to INPUT, 0x80 to OUTPUT, 0x81 to OUTPUT, 0x82 to OUTPUT,
        0x83 to OUTPUT, 0x84 to OUTPUT, 0x85 to OUTPUT, 0x01 to FEATURE)
    override val periodMs = 8L

    @Volatile private var pending = 0
    private var packet = 0
    private val start = System.nanoTime()

    override fun inputId() = 0x45

    override fun input(s: BtPadState): ByteArray {
        val r = ByteArray(45)
        packet++
        r[0] = packet.toByte()
        var b = LGRIP_TOUCH or RGRIP_TOUCH
        BUTTONS.forEach { (mine, theirs) -> if (s.isDown(mine)) b = b or theirs }
        if (s.lt >= 0.98f) b = b or LTRIGGER_CLICK
        if (s.rt >= 0.98f) b = b or RTRIGGER_CLICK
        if (Math.abs(s.lx) > 0.06f || Math.abs(s.ly) > 0.06f) b = b or LSTICK_TOUCH
        if (Math.abs(s.rx) > 0.06f || Math.abs(s.ry) > 0.06f) b = b or RSTICK_TOUCH
        r.le32(1, b.toLong())
        r.le16(5, (s.lt.coerceIn(0f, 1f) * 32767f).roundToInt())
        r.le16(7, (s.rt.coerceIn(0f, 1f) * 32767f).roundToInt())
        r.le16(9, stick(s.lx)); r.le16(11, stick(-s.ly)); r.le16(13, stick(s.rx)); r.le16(15, stick(-s.ry))
        if (s.isDown(BtPadState.LPAD_TOUCH)) {
            r.le16(17, stick(s.lpx)); r.le16(19, stick(-s.lpy))
            r.le16(21, if (s.isDown(BtPadState.LPAD_CLICK)) 32767 else 4000)
        }
        if (s.isDown(BtPadState.RPAD_TOUCH)) {
            r.le16(23, stick(s.rpx)); r.le16(25, stick(-s.rpy))
            r.le16(27, if (s.isDown(BtPadState.RPAD_CLICK)) 32767 else 4000)
        }
        r.le32(29, (System.nanoTime() - start) / 1000)
        r.le16(37, 16384)
        return r
    }

    override fun setFeature(id: Int, data: ByteArray) {
        val d = data
        if (id == 1 && d.isNotEmpty()) pending = d[0].toInt() and 0xFF
    }

    override fun feature(id: Int): ByteArray? {
        if (id != 1) return null
        val r = ByteArray(63)
        r[0] = pending.toByte()
        when (pending) {
            0x83 -> {
                val ids = intArrayOf(0x01, 0x02, 0x04, 0x09, 0x0A, 0x0B)
                val values = longArrayOf(0x1303, 0, FW_TIMESTAMP, 72, 0, 4000)
                r[1] = (ids.size * 5).toByte()
                ids.forEachIndexed { i, a -> r[2 + i * 5] = a.toByte(); r.le32(3 + i * 5, values[i]) }
            }
            0xAE -> { r[1] = 0x14; r[2] = 0x01; SERIAL.toByteArray().copyInto(r, 3) }
            0xBA -> { r[1] = 0x11; "DROIDDECKCHIP02".toByteArray().copyInto(r, 3) }
        }
        return r
    }

    override fun output(id: Int, data: ByteArray): BtFeedback? {
        val d = data
        return when (id) {
            0x80 -> if (d.size >= 8) {
                val left = (d[3].toInt() and 0xFF) or ((d[4].toInt() and 0xFF) shl 8)
                val right = (d[6].toInt() and 0xFF) or ((d[7].toInt() and 0xFF) shl 8)
                BtFeedback(left shr 8, right shr 8)
            } else null
            in 0x81..0x85 -> BtFeedback(0, 120)
            else -> null
        }
    }

    private fun stick(v: Float) = (v.coerceIn(-1f, 1f) * 32767f).roundToInt()

    companion object {
        private const val LGRIP_TOUCH = 0x20000000
        private const val RGRIP_TOUCH = 0x10000000
        private const val LTRIGGER_CLICK = 0x08000000
        private const val RTRIGGER_CLICK = 0x00800000
        private const val LSTICK_TOUCH = 0x01000000
        private const val RSTICK_TOUCH = 0x00100000
        private const val FW_TIMESTAMP = 0x6AC686B3L
        private const val SERIAL = "DROIDDECKSC01"
        private val BUTTONS = listOf(
            BtPadState.A to 0x00000001, BtPadState.B to 0x00000002, BtPadState.X to 0x00000004, BtPadState.Y to 0x00000008,
            BtPadState.SHARE to 0x00000010, BtPadState.R3 to 0x00000020, BtPadState.SELECT to 0x00000040,
            BtPadState.R4 to 0x00000080, BtPadState.R5 to 0x00000100, BtPadState.RB to 0x00000200,
            BtPadState.DOWN to 0x00000400, BtPadState.RIGHT to 0x00000800, BtPadState.LEFT to 0x00001000,
            BtPadState.UP to 0x00002000, BtPadState.START to 0x00004000, BtPadState.L3 to 0x00008000,
            BtPadState.GUIDE to 0x00010000, BtPadState.L4 to 0x00020000, BtPadState.L5 to 0x00040000,
            BtPadState.LB to 0x00080000,
            BtPadState.RPAD_TOUCH to 0x00200000, BtPadState.RPAD_CLICK to 0x00400000,
            BtPadState.LPAD_TOUCH to 0x02000000, BtPadState.LPAD_CLICK to 0x04000000,
        )
    }
}

private val FAKE_MAC = BtPadEncoder.bytes(0x5D, 0x44, 0x33, 0x22, 0x11, 0xD0)

private fun rgb(r: Byte, g: Byte, b: Byte) =
    (0xFF shl 24) or ((r.toInt() and 0xFF) shl 16) or ((g.toInt() and 0xFF) shl 8) or (b.toInt() and 0xFF)
