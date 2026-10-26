package com.droiddeck.launcher.glasses

import java.nio.ByteBuffer
import java.nio.ByteOrder

/** Rokid Air/Max share one USB identity; Max 2 has a separate product ID. */
internal class RokidModel private constructor(override val name: String, private val max: Boolean) : GlassesModel {
    override val controllable = true
    override val brightnessMax = BRIGHTNESS_STEPS
    override val volumeMax = 0
    override val hasSunblock = false
    override val diagonalFovDeg = if (max) 50f else 43f
    override val sbs90 = max
    override val rates2d = if (max) listOf(60, 120) else listOf(60)
    override val resolutions = if (max) listOf(GlassesResolution(1920, 1080), GlassesResolution(1920, 1200))
        else listOf(GlassesResolution(1920, 1080))

    override fun mode(hz: Int, sbs: Boolean): Int = mode(hz, sbs, null)

    override fun mode(hz: Int, sbs: Boolean, resolution: GlassesResolution?): Int = when {
        !max -> if (sbs) MODE_SBS_1080_60 else MODE_2D_60
        sbs && resolution?.height == 1080 -> MODE_SBS_1080_60
        sbs -> if (hz >= 90) MODE_SBS_1200_90 else MODE_SBS_1200_60
        hz >= 120 -> MODE_2D_120
        else -> MODE_2D_60
    }

    override fun modeForStep(step: Int): Int? = null

    override fun equals(other: Any?) = other is RokidModel && other.name == name && other.max == max
    override fun hashCode() = name.hashCode()

    companion object {
        const val VENDOR_ID = 0x04d2
        const val PRODUCT_ID = 0x162f
        const val MAX2_PRODUCT_ID = 0x2002
        const val BRIGHTNESS_STEPS = 10

        // Vendor control requests, device recipient.
        const val REQUEST_SET_DISPLAY = 0x01
        const val REQUEST_GET_DISPLAY = 0x81
        const val REQUEST_SET_BRIGHTNESS = 0x02
        const val REQUEST_GET_BRIGHTNESS = 0x82
        const val INDEX_DISPLAY = 0x01
        const val INDEX_BRIGHTNESS = 0x02

        const val MODE_2D_60 = 0
        const val MODE_SBS_1080_60 = 1
        const val MODE_2D_120 = 3
        const val MODE_SBS_1200_90 = 4
        const val MODE_SBS_1200_60 = 5
        val MODES = setOf(MODE_2D_60, MODE_SBS_1080_60, MODE_2D_120, MODE_SBS_1200_90, MODE_SBS_1200_60)

        // 0x04d2 is a shared vendor ID, so the Rokid product string is required too.
        fun model(vid: Int, pid: Int, name: String?): GlassesModel? {
            if (vid != VENDOR_ID || (pid != PRODUCT_ID && pid != MAX2_PRODUCT_ID)) return null
            val product = name?.trim()?.takeIf { it.startsWith("Rokid", ignoreCase = true) } ?: return null
            return RokidModel(product, pid == MAX2_PRODUCT_ID || product.contains("Max", ignoreCase = true))
        }

        fun isDisplay(name: String) = name.contains("Rokid", ignoreCase = true)

        /** Device brightness is 0..100; the shared slider uses ten steps. */
        fun brightnessStep(raw: Int): Int = ((raw.coerceIn(0, 100) + 5) / 10).coerceIn(0, BRIGHTNESS_STEPS)
        fun brightnessRaw(step: Int): Int = step.coerceIn(0, BRIGHTNESS_STEPS) * 10
    }
}

internal object RokidProtocol {
    data class Sample(val timeNs: Long, val accel: Vec3, val gyro: Vec3, val brightness: Int?)

    private const val COMBINED = 17
    private const val SEPARATE = 4
    private const val SENSOR_ACCEL = 1
    private const val SENSOR_GYRO = 2

    /** Max sends one combined report; Air sends accel and gyro reports that share a timestamp. */
    class Parser {
        private var accel: Vec3? = null
        private var accelTime = -1L
        private var gyro: Vec3? = null
        private var gyroTime = -2L

        fun parse(packet: ByteArray, length: Int = packet.size): Sample? {
            if (length < 64) return null
            val b = ByteBuffer.wrap(packet, 0, length).order(ByteOrder.LITTLE_ENDIAN)
            return when (packet[0].toInt() and 0xff) {
                COMBINED -> Sample(b.getLong(1), vec(b, 9), vec(b, 21), packet[59].toInt() and 0xff)
                SEPARATE -> {
                    val time = b.getLong(9) * 1000 // Air reports microseconds; Max reports nanoseconds.
                    when (packet[1].toInt() and 0xff) {
                        SENSOR_ACCEL -> { accel = vec(b, 21); accelTime = time }
                        SENSOR_GYRO -> { gyro = vec(b, 21); gyroTime = time }
                        else -> return null
                    }
                    if (accelTime != gyroTime) return null
                    Sample(time, accel!!, gyro!!, null).also { accelTime = -1L; gyroTime = -2L }
                }
                else -> null
            }
        }

        private fun vec(b: ByteBuffer, offset: Int) = Vec3(b.getFloat(offset), b.getFloat(offset + 4), b.getFloat(offset + 8))
    }
}
