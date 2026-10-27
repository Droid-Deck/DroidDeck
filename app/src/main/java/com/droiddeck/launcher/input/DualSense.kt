package com.droiddeck.launcher.input

import android.hardware.usb.UsbDevice
import android.view.InputDevice
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sqrt

object DualSense {
    const val VENDOR_SONY = 0x054C
    const val PRODUCT_DUALSENSE = 0x0CE6
    const val PRODUCT_DUALSENSE_EDGE = 0x0DF2

    const val REPORT_ID_INPUT: Byte = 0x01
    const val REPORT_ID_OUTPUT: Byte = 0x02
    const val REPORT_ID_CALIBRATION: Byte = 0x05
    const val REPORT_ID_PAIRING: Byte = 0x09
    const val REPORT_ID_FIRMWARE: Byte = 0x20
    const val REPORT_ID_BT_INPUT: Byte = 0x31
    const val REPORT_ID_BT_OUTPUT: Byte = 0x31
    const val REPORT_SIZE_INPUT = 64
    const val REPORT_SIZE_OUTPUT = 48
    const val REPORT_SIZE_CALIBRATION = 41
    const val REPORT_SIZE_PAIRING = 20
    const val REPORT_SIZE_FIRMWARE = 64
    const val STATUS1_HEADPHONE_DETECT = 0x01
    const val RUMBLE_WATCHDOG_MS = 5000L

    const val PLAYER_LED_BRIGHTNESS_HIGH = 0x00
    const val PLAYER_LED_BRIGHTNESS_MEDIUM = 0x01
    const val PLAYER_LED_BRIGHTNESS_LOW = 0x02

    /**
     * Routes the pad's own audio the way Linux hid-playstation does on a jack change: headphones get the stereo
     * pair and the speaker is muted; without headphones the right channel goes to the speaker at volume 0x64
     * (the accepted range seems to be 0x3D..0x64) with the +6 dB preamp. No other output fields are marked valid.
     */
    fun buildAudioRoutingReport(headphonesPlugged: Boolean): ByteArray {
        val report = ByteArray(REPORT_SIZE_OUTPUT)
        report[0] = REPORT_ID_OUTPUT
        if (headphonesPlugged) {
            report[1] = 0x80.toByte()
            report[8] = 0
        } else {
            report[1] = (0x80 or 0x20).toByte()
            report[6] = 0x64
            report[8] = 0x30
            report[2] = 0x80.toByte()
            report[38] = 0x02
        }
        return report
    }

    fun preserveSpeakerRouting(report: ByteArray): ByteArray {
        if (report.size < REPORT_SIZE_OUTPUT || (report[1].toInt() and 0x80) == 0 || report[8].toInt() != 0) {
            return report
        }
        val out = report.copyOf(REPORT_SIZE_OUTPUT)
        out[1] = (out[1].toInt() or 0xA0).toByte()
        if (out[6].toInt() == 0) out[6] = 0x64
        out[8] = 0x30
        out[2] = (out[2].toInt() or 0x80).toByte()
        out[38] = (out[38].toInt() or 0x02).toByte()
        return out
    }
    const val REPORT_SIZE_BT_INPUT = 78
    const val REPORT_SIZE_BT_OUTPUT = 78
    const val REPORT_SIZE_BT_BASIC_INPUT = 10

    const val TOUCHPAD_WIDTH = 1920
    const val TOUCHPAD_HEIGHT = 1080

    /**
     * DSX v3.2.0.0 discovery: For the first 3.0 seconds after a Bluetooth connection,
     * sending LightbarSetup = 0x02 (ResetLights) or custom RGB (enable2 & 0x04) interrupts
     * the controller's built-in blue fade-in animation and locks out subsequent RGB changes.
     */
    const val BT_LIGHTBAR_GUARD_MS = 3000L

    fun isDualSense(vendorId: Int, productId: Int): Boolean =
        vendorId == VENDOR_SONY && (productId == PRODUCT_DUALSENSE || productId == PRODUCT_DUALSENSE_EDGE)

    fun isDualSense(device: InputDevice?): Boolean =
        device != null && !device.isVirtual && isDualSense(device.vendorId, device.productId)

    fun isDualSense(device: UsbDevice?): Boolean =
        device != null && isDualSense(device.vendorId, device.productId)

    fun isEdge(productId: Int): Boolean = productId == PRODUCT_DUALSENSE_EDGE
    fun isEdge(device: InputDevice?): Boolean =
        device != null && !device.isVirtual && device.vendorId == VENDOR_SONY && isEdge(device.productId)
    fun isEdge(device: UsbDevice?): Boolean =
        device != null && device.vendorId == VENDOR_SONY && isEdge(device.productId)

    data class State(
        val leftX: Float,
        val leftY: Float,
        val rightX: Float,
        val rightY: Float,
        val leftTrigger: Float,
        val rightTrigger: Float,
        val dpadUp: Boolean,
        val dpadRight: Boolean,
        val dpadDown: Boolean,
        val dpadLeft: Boolean,
        val square: Boolean,
        val cross: Boolean,
        val circle: Boolean,
        val triangle: Boolean,
        val l1: Boolean,
        val r1: Boolean,
        val l2Click: Boolean,
        val r2Click: Boolean,
        val create: Boolean,
        val options: Boolean,
        val l3: Boolean,
        val r3: Boolean,
        val ps: Boolean,
        val touchpadClick: Boolean,
        val mute: Boolean,
        val l4: Boolean,
        val r4: Boolean,
        val l5: Boolean,
        val r5: Boolean,
        val gyroX: Short,
        val gyroY: Short,
        val gyroZ: Short,
        val accelX: Short,
        val accelY: Short,
        val accelZ: Short,
        val touch0Down: Boolean,
        val touch0Id: Int,
        val touch0X: Int,
        val touch0Y: Int,
        val touch1Down: Boolean,
        val touch1Id: Int,
        val touch1X: Int,
        val touch1Y: Int,
        val isEdgeTuningActive: Boolean = false,
        val batteryPercent: Int = 100,
        val isCharging: Boolean = false,
        val headphonesPlugged: Boolean = false,
        val batteryStatusByte: Int = 0x2a
    )

    data class Calibration(
        val gyroBias: ShortArray,
        val gyroPlus: ShortArray,
        val gyroMinus: ShortArray,
        val gyroSpeedPlus: Short,
        val gyroSpeedMinus: Short,
        val accelPlus: ShortArray,
        val accelMinus: ShortArray
    ) {
        fun applyGyro(axis: Int, raw: Short): Short {
            val denom = gyroPlus[axis].toInt() - gyroMinus[axis].toInt()
            val speed2x = gyroSpeedPlus.toInt() + gyroSpeedMinus.toInt()
            if (denom <= 0 || speed2x <= 0) return raw
            val num = speed2x.toLong() * 16L
            return ((raw.toInt() - gyroBias[axis].toInt()).toLong() * num / denom)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
        }

        fun applyAccel(axis: Int, raw: Short): Short {
            val range2g = accelPlus[axis].toInt() - accelMinus[axis].toInt()
            if (range2g <= 0) return raw
            val bias = (accelPlus[axis].toInt() + accelMinus[axis].toInt()) / 2
            return ((raw.toInt() - bias).toLong() * 16384L / range2g)
                .coerceIn(Short.MIN_VALUE.toLong(), Short.MAX_VALUE.toLong())
                .toShort()
        }
    }

    fun parseCalibrationReport(data: ByteArray, offset: Int = 0): Calibration? {
        if (offset < 0 || offset > data.size - REPORT_SIZE_CALIBRATION) return null
        if (data[offset] != REPORT_ID_CALIBRATION) return null

        fun shortLE(idx: Int): Short =
            ((data[idx].toInt() and 0xFF) or (data[idx + 1].toInt() shl 8)).toShort()

        val gyroBias = shortArrayOf(
            shortLE(offset + 1),
            shortLE(offset + 3),
            shortLE(offset + 5)
        )
        val gyroPlus = shortArrayOf(
            shortLE(offset + 7),
            shortLE(offset + 11),
            shortLE(offset + 15)
        )
        val gyroMinus = shortArrayOf(
            shortLE(offset + 9),
            shortLE(offset + 13),
            shortLE(offset + 17)
        )
        val gyroSpeedPlus = shortLE(offset + 19)
        val gyroSpeedMinus = shortLE(offset + 21)
        val accelPlus = shortArrayOf(
            shortLE(offset + 23),
            shortLE(offset + 27),
            shortLE(offset + 31)
        )
        val accelMinus = shortArrayOf(
            shortLE(offset + 25),
            shortLE(offset + 29),
            shortLE(offset + 33)
        )

        if (gyroSpeedPlus.toInt() + gyroSpeedMinus.toInt() <= 0) return null
        for (i in 0..2) {
            if (gyroPlus[i].toInt() - gyroMinus[i].toInt() <= 0) return null
            if (accelPlus[i].toInt() - accelMinus[i].toInt() <= 0) return null
        }

        return Calibration(
            gyroBias = gyroBias,
            gyroPlus = gyroPlus,
            gyroMinus = gyroMinus,
            gyroSpeedPlus = gyroSpeedPlus,
            gyroSpeedMinus = gyroSpeedMinus,
            accelPlus = accelPlus,
            accelMinus = accelMinus
        )
    }

    data class FirmwareInfo(
        val buildDate: String,
        val buildTime: String,
        val fwType: Int,
        val swSeries: Int,
        val hardwareInfo: Int,
        val mainFwVersion: Int,
        val sblFwVersion: Int,
        val dspFwVersion: Int
    )

    data class PairingInfo(
        val deviceMac: String,
        val hostMac: String
    )

    fun parseFirmwareInfoReport(data: ByteArray, offset: Int = 0): FirmwareInfo? {
        if (offset < 0 || offset > data.size - REPORT_SIZE_FIRMWARE) return null
        if (data[offset] != REPORT_ID_FIRMWARE) return null

        fun u16LE(idx: Int): Int =
            (data[idx].toInt() and 0xFF) or ((data[idx + 1].toInt() and 0xFF) shl 8)

        fun i32LE(idx: Int): Int =
            (data[idx].toInt() and 0xFF) or
                    ((data[idx + 1].toInt() and 0xFF) shl 8) or
                    ((data[idx + 2].toInt() and 0xFF) shl 16) or
                    ((data[idx + 3].toInt() and 0xFF) shl 24)

        val dateBytes = data.copyOfRange(offset + 1, offset + 12)
        val timeBytes = data.copyOfRange(offset + 12, offset + 20)
        val buildDate = String(dateBytes, Charsets.US_ASCII).trimEnd('\u0000', ' ')
        val buildTime = String(timeBytes, Charsets.US_ASCII).trimEnd('\u0000', ' ')

        return FirmwareInfo(
            buildDate = buildDate,
            buildTime = buildTime,
            fwType = u16LE(offset + 20),
            swSeries = u16LE(offset + 22),
            hardwareInfo = i32LE(offset + 24),
            mainFwVersion = i32LE(offset + 28),
            sblFwVersion = i32LE(offset + 44),
            dspFwVersion = i32LE(offset + 48)
        )
    }

    fun parsePairingInfoReport(data: ByteArray, offset: Int = 0): PairingInfo? {
        if (offset < 0 || offset > data.size - REPORT_SIZE_PAIRING) return null
        if (data[offset] != REPORT_ID_PAIRING) return null

        fun macAt(start: Int): String =
            (5 downTo 0).joinToString(":") { i ->
                String.format("%02X", data[start + i].toInt() and 0xFF)
            }

        return PairingInfo(
            deviceMac = macAt(offset + 1),
            hostMac = macAt(offset + 10)
        )
    }

    class MotionNoiseCalibration(private val windowSize: Int = 64) {
        @Volatile var gyroBiasX: Float = 0f
            private set
        @Volatile var gyroBiasY: Float = 0f
            private set
        @Volatile var gyroBiasZ: Float = 0f
            private set
        @Volatile var isCalibrated: Boolean = false
            private set

        private var count = 0
        private var sumGx = 0f
        private var sumGy = 0f
        private var sumGz = 0f
        private var sumGxSq = 0f
        private var sumGySq = 0f
        private var sumGzSq = 0f
        private var sumAx = 0f
        private var sumAy = 0f
        private var sumAz = 0f
        private var sumAxSq = 0f
        private var sumAySq = 0f
        private var sumAzSq = 0f

        @Synchronized
        fun reset() {
            count = 0
            sumGx = 0f; sumGy = 0f; sumGz = 0f
            sumGxSq = 0f; sumGySq = 0f; sumGzSq = 0f
            sumAx = 0f; sumAy = 0f; sumAz = 0f
            sumAxSq = 0f; sumAySq = 0f; sumAzSq = 0f
        }

        @Synchronized
        fun feedAndApply(
            gyroX: Short,
            gyroY: Short,
            gyroZ: Short,
            accelX: Short,
            accelY: Short,
            accelZ: Short
        ): ShortArray {
            val gx = gyroX.toFloat()
            val gy = gyroY.toFloat()
            val gz = gyroZ.toFloat()
            val ax = accelX.toFloat()
            val ay = accelY.toFloat()
            val az = accelZ.toFloat()

            if (isAutoCalibrationCandidate(gx, gy, gz, ax, ay, az)) {
                sumGx += gx; sumGy += gy; sumGz += gz
                sumGxSq += gx * gx; sumGySq += gy * gy; sumGzSq += gz * gz
                sumAx += ax; sumAy += ay; sumAz += az
                sumAxSq += ax * ax; sumAySq += ay * ay; sumAzSq += az * az
                if (++count >= windowSize) {
                    val n = count.toFloat()
                    val meanGx = sumGx / n
                    val meanGy = sumGy / n
                    val meanGz = sumGz / n
                    val meanAx = sumAx / n
                    val meanAy = sumAy / n
                    val meanAz = sumAz / n
                    val varGx = max(0f, sumGxSq / n - meanGx * meanGx)
                    val varGy = max(0f, sumGySq / n - meanGy * meanGy)
                    val varGz = max(0f, sumGzSq / n - meanGz * meanGz)
                    val varAx = max(0f, sumAxSq / n - meanAx * meanAx)
                    val varAy = max(0f, sumAySq / n - meanAy * meanAy)
                    val varAz = max(0f, sumAzSq / n - meanAz * meanAz)
                    val gyroStdMax = sqrt(max(varGx, max(varGy, varGz)))
                    val accelStdMax = sqrt(max(varAx, max(varAy, varAz)))
                    if (gyroStdMax <= AUTO_GYRO_STD_DEV_LIMIT && accelStdMax <= AUTO_ACCEL_STD_DEV_LIMIT) {
                        if (!isCalibrated ||
                            abs(meanGx - gyroBiasX) > SIGNIFICANT_GYRO_DELTA ||
                            abs(meanGy - gyroBiasY) > SIGNIFICANT_GYRO_DELTA ||
                            abs(meanGz - gyroBiasZ) > SIGNIFICANT_GYRO_DELTA
                        ) {
                            gyroBiasX = meanGx
                            gyroBiasY = meanGy
                            gyroBiasZ = meanGz
                            isCalibrated = true
                        }
                    }
                    reset()
                }
            } else {
                reset()
            }

            if (!isCalibrated) return shortArrayOf(gyroX, gyroY, gyroZ)
            return shortArrayOf(
                (gx - gyroBiasX).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                (gy - gyroBiasY).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort(),
                (gz - gyroBiasZ).roundToInt().coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
            )
        }

        companion object {
            const val EXPECTED_ACCEL_MAGNITUDE = 8192f
            const val AUTO_GYRO_MAGNITUDE_LIMIT = 256f
            const val AUTO_ACCEL_MAGNITUDE_TOLERANCE = 1200f
            const val AUTO_GYRO_STD_DEV_LIMIT = 24f
            const val AUTO_ACCEL_STD_DEV_LIMIT = 160f
            const val SIGNIFICANT_GYRO_DELTA = 0.5f

            fun isAutoCalibrationCandidate(
                gx: Float,
                gy: Float,
                gz: Float,
                ax: Float,
                ay: Float,
                az: Float
            ): Boolean {
                val gyroMag = sqrt(gx * gx + gy * gy + gz * gz)
                if (gyroMag > AUTO_GYRO_MAGNITUDE_LIMIT) return false
                val accelMag = sqrt(ax * ax + ay * ay + az * az)
                return abs(accelMag - EXPECTED_ACCEL_MAGNITUDE) <= AUTO_ACCEL_MAGNITUDE_TOLERANCE
            }
        }
    }

    fun touchXToPad(rawX: Int): Short {
        val clampedX = rawX.coerceIn(0, TOUCHPAD_WIDTH - 1).toLong()
        return ((clampedX * 65535L / (TOUCHPAD_WIDTH - 1)) - 32768L).toShort()
    }

    fun touchYToPad(rawY: Int): Short {
        val clampedY = rawY.coerceIn(0, TOUCHPAD_HEIGHT - 1).toLong()
        return (32767L - (clampedY * 65535L / (TOUCHPAD_HEIGHT - 1))).toShort()
    }

    fun padToTouchX(padX: Short): Int =
        (((padX.toInt() + 32768) * (TOUCHPAD_WIDTH - 1)) / 65535).coerceIn(0, TOUCHPAD_WIDTH - 1)

    fun padToTouchY(padY: Short): Int =
        (((32767 - padY.toInt()) * (TOUCHPAD_HEIGHT - 1)) / 65535).coerceIn(0, TOUCHPAD_HEIGHT - 1)

    private fun normalizeStick(raw: Int): Float = when {
        raw == 128 -> 0f
        raw < 128 -> ((raw - 128) / 128f).coerceIn(-1f, 1f)
        else -> ((raw - 128) / 127f).coerceIn(-1f, 1f)
    }

    fun parseInputReport(
        data: ByteArray,
        offset: Int = 0,
        calibration: Calibration? = null,
        isEdge: Boolean = false,
        motionCalibration: MotionNoiseCalibration? = null
    ): State? {
        if (offset < 0 || offset >= data.size) return null
        val reportId = data[offset]
        val off: Int
        if (reportId == REPORT_ID_INPUT) {
            if (offset > data.size - REPORT_SIZE_INPUT) {
                if (data.size - offset == REPORT_SIZE_BT_BASIC_INPUT) {
                    return parseBasicBtInputReport(data, offset)
                }
                return null
            }
            off = 0
        } else if (reportId == REPORT_ID_BT_INPUT) {
            if (offset > data.size - REPORT_SIZE_BT_INPUT) return null
            if ((data[offset + 1].toInt() and 0x01) == 0) return null
            val crcBytes = (data[offset + 74].toInt() and 0xFF) or
                    ((data[offset + 75].toInt() and 0xFF) shl 8) or
                    ((data[offset + 76].toInt() and 0xFF) shl 16) or
                    ((data[offset + 77].toInt() and 0xFF) shl 24)
            if (crcBytes != 0) {
                val crc = java.util.zip.CRC32()
                crc.update(0xA1)
                crc.update(data, offset, 74)
                if (crc.value.toInt() != crcBytes) return null
            }
            off = 1
        } else {
            return null
        }

        val lx = data[offset + 1 + off].toInt() and 0xFF
        val ly = data[offset + 2 + off].toInt() and 0xFF
        val rx = data[offset + 3 + off].toInt() and 0xFF
        val ry = data[offset + 4 + off].toInt() and 0xFF

        val leftX = normalizeStick(lx)
        val leftY = normalizeStick(ly)
        val rightX = normalizeStick(rx)
        val rightY = normalizeStick(ry)

        val rawLt = data[offset + 5 + off].toInt() and 0xFF
        val rawRt = data[offset + 6 + off].toInt() and 0xFF
        val leftTrigger = (rawLt / 255f).coerceIn(0f, 1f)
        val rightTrigger = (rawRt / 255f).coerceIn(0f, 1f)

        val b8 = data[offset + 8 + off].toInt() and 0xFF
        val hat = b8 and 0x0F
        val dpadUp = hat == 0 || hat == 1 || hat == 7
        val dpadRight = hat == 1 || hat == 2 || hat == 3
        val dpadDown = hat == 3 || hat == 4 || hat == 5
        val dpadLeft = hat == 5 || hat == 6 || hat == 7

        val square = (b8 and 0x10) != 0
        val cross = (b8 and 0x20) != 0
        val circle = (b8 and 0x40) != 0
        val triangle = (b8 and 0x80) != 0

        val b9 = data[offset + 9 + off].toInt() and 0xFF
        val l1 = (b9 and 0x01) != 0
        val r1 = (b9 and 0x02) != 0
        val l2Click = (b9 and 0x04) != 0
        val r2Click = (b9 and 0x08) != 0
        val create = (b9 and 0x10) != 0
        val options = (b9 and 0x20) != 0
        val l3 = (b9 and 0x40) != 0
        val r3 = (b9 and 0x80) != 0

        val b10 = data[offset + 10 + off].toInt() and 0xFF
        val ps = (b10 and 0x01) != 0
        val touchpadClick = (b10 and 0x02) != 0
        val mute = (b10 and 0x04) != 0
        val l5 = (b10 and 0x10) != 0
        val r5 = (b10 and 0x20) != 0
        val l4 = (b10 and 0x40) != 0
        val r4 = (b10 and 0x80) != 0

        fun shortLE(idx: Int): Short =
            ((data[idx].toInt() and 0xFF) or (data[idx + 1].toInt() shl 8)).toShort()

        val rawGyroX = shortLE(offset + 16 + off)
        val rawGyroY = shortLE(offset + 18 + off)
        val rawGyroZ = shortLE(offset + 20 + off)
        val rawAccelX = shortLE(offset + 22 + off)
        val rawAccelY = shortLE(offset + 24 + off)
        val rawAccelZ = shortLE(offset + 26 + off)

        val calGyroX = calibration?.applyGyro(0, rawGyroX) ?: rawGyroX
        val calGyroY = calibration?.applyGyro(1, rawGyroY) ?: rawGyroY
        val calGyroZ = calibration?.applyGyro(2, rawGyroZ) ?: rawGyroZ
        val accelX = calibration?.applyAccel(0, rawAccelX) ?: rawAccelX
        val accelY = calibration?.applyAccel(1, rawAccelY) ?: rawAccelY
        val accelZ = calibration?.applyAccel(2, rawAccelZ) ?: rawAccelZ

        val adjustedGyro = motionCalibration?.feedAndApply(
            calGyroX, calGyroY, calGyroZ, accelX, accelY, accelZ
        )
        val gyroX = adjustedGyro?.get(0) ?: calGyroX
        val gyroY = adjustedGyro?.get(1) ?: calGyroY
        val gyroZ = adjustedGyro?.get(2) ?: calGyroZ

        val t0b0 = data[offset + 33 + off].toInt() and 0xFF
        val t0b1 = data[offset + 34 + off].toInt() and 0xFF
        val t0b2 = data[offset + 35 + off].toInt() and 0xFF
        val t0b3 = data[offset + 36 + off].toInt() and 0xFF
        val touch0Down = (t0b0 and 0x80) == 0
        val touch0Id = t0b0 and 0x7F
        val touch0X = (t0b1 or ((t0b2 and 0x0F) shl 8)).coerceIn(0, TOUCHPAD_WIDTH - 1)
        val touch0Y = (((t0b2 and 0xF0) ushr 4) or (t0b3 shl 4)).coerceIn(0, TOUCHPAD_HEIGHT - 1)

        val t1b0 = data[offset + 37 + off].toInt() and 0xFF
        val t1b1 = data[offset + 38 + off].toInt() and 0xFF
        val t1b2 = data[offset + 39 + off].toInt() and 0xFF
        val t1b3 = data[offset + 40 + off].toInt() and 0xFF
        val touch1Down = (t1b0 and 0x80) == 0
        val touch1Id = t1b0 and 0x7F
        val touch1X = (t1b1 or ((t1b2 and 0x0F) shl 8)).coerceIn(0, TOUCHPAD_WIDTH - 1)
        val touch1Y = (((t1b2 and 0xF0) ushr 4) or (t1b3 shl 4)).coerceIn(0, TOUCHPAD_HEIGHT - 1)

        val isEdgeTuningActive = isEdge && ((data[offset + 49 + off].toInt() and 0x0F) in 0x0D..0x0F)

        val b53 = data[offset + 53 + off].toInt() and 0xFF
        val batteryRaw = b53 and 0x0F
        val powerState = (b53 ushr 4) and 0x0F
        val batteryPercent = when {
            b53 == 0 -> 100
            powerState == 0x02 -> 100
            powerState == 0x0A || powerState == 0x0B || powerState == 0x0F -> 0
            else -> minOf(batteryRaw * 10 + 5, 100)
        }
        val isCharging = powerState == 0x01 || powerState == 0x02
        val b54 = data[offset + 54 + off].toInt() and 0xFF
        val headphonesPlugged = (b54 and STATUS1_HEADPHONE_DETECT) != 0
        val batteryStatusByte = if (b53 == 0) 0x2a else b53

        return State(
            leftX = leftX, leftY = leftY,
            rightX = rightX, rightY = rightY,
            leftTrigger = leftTrigger, rightTrigger = rightTrigger,
            dpadUp = dpadUp, dpadRight = dpadRight, dpadDown = dpadDown, dpadLeft = dpadLeft,
            square = square, cross = cross, circle = circle, triangle = triangle,
            l1 = l1, r1 = r1, l2Click = l2Click, r2Click = r2Click,
            create = create, options = options, l3 = l3, r3 = r3,
            ps = ps, touchpadClick = touchpadClick, mute = mute,
            l4 = l4, r4 = r4, l5 = l5, r5 = r5,
            gyroX = gyroX, gyroY = gyroY, gyroZ = gyroZ,
            accelX = accelX, accelY = accelY, accelZ = accelZ,
            touch0Down = touch0Down, touch0Id = touch0Id, touch0X = touch0X, touch0Y = touch0Y,
            touch1Down = touch1Down, touch1Id = touch1Id, touch1X = touch1X, touch1Y = touch1Y,
            isEdgeTuningActive = isEdgeTuningActive,
            batteryPercent = batteryPercent,
            isCharging = isCharging,
            headphonesPlugged = headphonesPlugged,
            batteryStatusByte = batteryStatusByte
        )
    }

    /**
     * Normalizes either a 64-byte USB 0x01 input report or a 78-byte Bluetooth 0x31
     * extended input report into the canonical 64-byte USB 0x01 layout expected by pad.bin
     * and fakeinput_steam.cpp.
     */
    fun convertBtInputToUsb64(data: ByteArray, offset: Int = 0): ByteArray? {
        if (offset < 0 || offset >= data.size) return null
        return when (data[offset]) {
            REPORT_ID_INPUT -> {
                if (offset > data.size - REPORT_SIZE_INPUT) return null
                data.copyOfRange(offset, offset + REPORT_SIZE_INPUT)
            }
            REPORT_ID_BT_INPUT -> {
                if (offset > data.size - REPORT_SIZE_BT_INPUT) return null
                if ((data[offset + 1].toInt() and 0x01) == 0) return null
                val usb = ByteArray(REPORT_SIZE_INPUT)
                usb[0] = REPORT_ID_INPUT
                System.arraycopy(data, offset + 2, usb, 1, REPORT_SIZE_INPUT - 1)
                usb
            }
            else -> null
        }
    }

    /**
     * Compares two 64-byte USB 0x01 input reports while ignoring hardware-ticking counters,
     * sensor timestamps, and rolling checksum bytes (7, 12..15, 28..32, 41, 49..52, 56..63).
     */
    fun isSamePadBinState(prev: ByteArray?, curr: ByteArray, isEdge: Boolean = false): Boolean {
        if (prev == null || prev.size < REPORT_SIZE_INPUT || curr.size < REPORT_SIZE_INPUT) return false
        if (prev[0] != curr[0]) return false
        for (i in 1..6) if (prev[i] != curr[i]) return false
        for (i in 8..11) if (prev[i] != curr[i]) return false
        for (i in 16..27) if (prev[i] != curr[i]) return false
        for (i in 33..40) if (prev[i] != curr[i]) return false
        for (i in 42..48) if (prev[i] != curr[i]) return false
        if (isEdge && (prev[49].toInt() and 0x0F) != (curr[49].toInt() and 0x0F)) return false
        for (i in 53..55) if (prev[i] != curr[i]) return false
        return true
    }

    /**
     * Parses a 10-byte basic Bluetooth 0x01 input report (sent over Bluetooth before Feature 0x05 is queried).
     */
    fun parseBasicBtInputReport(data: ByteArray, offset: Int = 0): State? {
        if (offset < 0 || offset > data.size - 10) return null
        if (data[offset] != REPORT_ID_INPUT) return null

        val lx = data[offset + 1].toInt() and 0xFF
        val ly = data[offset + 2].toInt() and 0xFF
        val rx = data[offset + 3].toInt() and 0xFF
        val ry = data[offset + 4].toInt() and 0xFF

        val b5 = data[offset + 5].toInt() and 0xFF
        val hat = b5 and 0x0F
        val dpadUp = hat == 0 || hat == 1 || hat == 7
        val dpadRight = hat == 1 || hat == 2 || hat == 3
        val dpadDown = hat == 3 || hat == 4 || hat == 5
        val dpadLeft = hat == 5 || hat == 6 || hat == 7

        val b6 = data[offset + 6].toInt() and 0xFF
        val b7 = data[offset + 7].toInt() and 0xFF
        val rawLt = data[offset + 8].toInt() and 0xFF
        val rawRt = data[offset + 9].toInt() and 0xFF

        return State(
            leftX = normalizeStick(lx), leftY = normalizeStick(ly),
            rightX = normalizeStick(rx), rightY = normalizeStick(ry),
            leftTrigger = (rawLt / 255f).coerceIn(0f, 1f),
            rightTrigger = (rawRt / 255f).coerceIn(0f, 1f),
            dpadUp = dpadUp, dpadRight = dpadRight, dpadDown = dpadDown, dpadLeft = dpadLeft,
            square = (b5 and 0x10) != 0,
            cross = (b5 and 0x20) != 0,
            circle = (b5 and 0x40) != 0,
            triangle = (b5 and 0x80) != 0,
            l1 = (b6 and 0x01) != 0,
            r1 = (b6 and 0x02) != 0,
            l2Click = (b6 and 0x04) != 0,
            r2Click = (b6 and 0x08) != 0,
            create = (b6 and 0x10) != 0,
            options = (b6 and 0x20) != 0,
            l3 = (b6 and 0x40) != 0,
            r3 = (b6 and 0x80) != 0,
            ps = (b7 and 0x01) != 0,
            touchpadClick = (b7 and 0x02) != 0,
            mute = (b7 and 0x04) != 0,
            l4 = false, r4 = false, l5 = false, r5 = false,
            gyroX = 0, gyroY = 0, gyroZ = 0,
            accelX = 0, accelY = 0, accelZ = 0,
            touch0Down = false, touch0Id = 0, touch0X = 0, touch0Y = 0,
            touch1Down = false, touch1Id = 0, touch1X = 0, touch1Y = 0
        )
    }

    object TriggerEffects {
        const val MODE_OFF: Byte = 0x05
        const val MODE_SIMPLE_FEEDBACK: Byte = 0x01
        const val MODE_SIMPLE_WEAPON: Byte = 0x02
        const val MODE_SIMPLE_VIBRATION: Byte = 0x06
        const val MODE_LIMITED_FEEDBACK: Byte = 0x11
        const val MODE_LIMITED_WEAPON: Byte = 0x12
        const val MODE_FEEDBACK: Byte = 0x21
        const val MODE_BOW: Byte = 0x22
        const val MODE_GALLOPING: Byte = 0x23
        const val MODE_WEAPON: Byte = 0x25
        const val MODE_VIBRATION: Byte = 0x26
        const val MODE_MACHINE: Byte = 0x27

        fun off(): ByteArray = ByteArray(11).apply { this[0] = MODE_OFF }

        fun feedback(startPosition: Int, strength: Int): ByteArray {
            if (startPosition !in 0..9 || strength !in 0..8) return off()
            if (strength == 0) return off()
            val forceValue = (strength - 1) and 0x07
            var forceZones = 0
            var activeZones = 0
            for (i in startPosition..9) {
                forceZones = forceZones or (forceValue shl ((3 * i) and 31))
                activeZones = (activeZones or (1 shl (i and 31))) and 0xFFFF
            }
            return ByteArray(11).apply {
                this[0] = MODE_FEEDBACK
                this[1] = (activeZones and 0xFF).toByte()
                this[2] = ((activeZones shr 8) and 0xFF).toByte()
                this[3] = (forceZones and 0xFF).toByte()
                this[4] = ((forceZones ushr 8) and 0xFF).toByte()
                this[5] = ((forceZones ushr 16) and 0xFF).toByte()
                this[6] = ((forceZones ushr 24) and 0xFF).toByte()
            }
        }

        fun weapon(startPosition: Int, endPosition: Int, strength: Int): ByteArray {
            if (startPosition !in 2..7 || endPosition !in (startPosition + 1)..8 || strength !in 0..8) return off()
            if (strength == 0) return off()
            val zones = ((1 shl startPosition) or (1 shl endPosition)) and 0xFFFF
            return ByteArray(11).apply {
                this[0] = MODE_WEAPON
                this[1] = (zones and 0xFF).toByte()
                this[2] = ((zones shr 8) and 0xFF).toByte()
                this[3] = (strength - 1).toByte()
            }
        }

        fun vibration(startPosition: Int, amplitude: Int, frequency: Int): ByteArray {
            if (startPosition !in 0..9 || amplitude !in 0..8 || frequency !in 0..255) return off()
            if (amplitude == 0 || frequency == 0) return off()
            val ampValue = (amplitude - 1) and 0x07
            var ampZones = 0
            var activeZones = 0
            for (i in startPosition..9) {
                ampZones = ampZones or (ampValue shl ((3 * i) and 31))
                activeZones = (activeZones or (1 shl (i and 31))) and 0xFFFF
            }
            return ByteArray(11).apply {
                this[0] = MODE_VIBRATION
                this[1] = (activeZones and 0xFF).toByte()
                this[2] = ((activeZones shr 8) and 0xFF).toByte()
                this[3] = (ampZones and 0xFF).toByte()
                this[4] = ((ampZones ushr 8) and 0xFF).toByte()
                this[5] = ((ampZones ushr 16) and 0xFF).toByte()
                this[6] = ((ampZones ushr 24) and 0xFF).toByte()
                this[9] = (frequency and 0xFF).toByte()
            }
        }

        fun multiplePositionFeedback(strengths: ByteArray): ByteArray {
            if (strengths.size != 10 || strengths.all { (it.toInt() and 0xFF) == 0 }) return off()
            var forceZones = 0
            var activeZones = 0
            for (i in 0..9) {
                val s = strengths[i].toInt() and 0xFF
                if (s > 0) {
                    val forceValue = (s.coerceAtMost(8) - 1) and 0x07
                    forceZones = forceZones or (forceValue shl ((3 * i) and 31))
                    activeZones = (activeZones or (1 shl (i and 31))) and 0xFFFF
                }
            }
            return ByteArray(11).apply {
                this[0] = MODE_FEEDBACK
                this[1] = (activeZones and 0xFF).toByte()
                this[2] = ((activeZones shr 8) and 0xFF).toByte()
                this[3] = (forceZones and 0xFF).toByte()
                this[4] = ((forceZones ushr 8) and 0xFF).toByte()
                this[5] = ((forceZones ushr 16) and 0xFF).toByte()
                this[6] = ((forceZones ushr 24) and 0xFF).toByte()
            }
        }

        fun slopeFeedback(startPosition: Int, endPosition: Int, startStrength: Int, endStrength: Int): ByteArray {
            if (startPosition !in 0..8 || endPosition !in (startPosition + 1)..9 ||
                startStrength !in 1..8 || endStrength !in 1..8
            ) return off()
            val strengths = ByteArray(10)
            val slope = (endStrength - startStrength).toFloat() / (endPosition - startPosition).toFloat()
            for (i in startPosition..9) {
                strengths[i] = if (i <= endPosition) {
                    (startStrength + slope * (i - startPosition)).roundToInt().coerceIn(1, 8).toByte()
                } else {
                    endStrength.toByte()
                }
            }
            return multiplePositionFeedback(strengths)
        }

        fun multiplePositionVibration(frequency: Int, amplitudes: ByteArray): ByteArray {
            if (amplitudes.size != 10 || frequency !in 1..255 || amplitudes.all { (it.toInt() and 0xFF) == 0 }) {
                return off()
            }
            var ampZones = 0
            var activeZones = 0
            for (i in 0..9) {
                val a = amplitudes[i].toInt() and 0xFF
                if (a > 0) {
                    val ampValue = (a.coerceAtMost(8) - 1) and 0x07
                    ampZones = ampZones or (ampValue shl ((3 * i) and 31))
                    activeZones = (activeZones or (1 shl (i and 31))) and 0xFFFF
                }
            }
            return ByteArray(11).apply {
                this[0] = MODE_VIBRATION
                this[1] = (activeZones and 0xFF).toByte()
                this[2] = ((activeZones shr 8) and 0xFF).toByte()
                this[3] = (ampZones and 0xFF).toByte()
                this[4] = ((ampZones ushr 8) and 0xFF).toByte()
                this[5] = ((ampZones ushr 16) and 0xFF).toByte()
                this[6] = ((ampZones ushr 24) and 0xFF).toByte()
                this[9] = (frequency and 0xFF).toByte()
            }
        }

        fun bow(startPosition: Int, endPosition: Int, strength: Int, snapForce: Int): ByteArray {
            if (startPosition !in 0..8 || endPosition !in (startPosition + 1)..8 ||
                strength !in 0..8 || snapForce !in 0..8
            ) return off()
            if (strength == 0 || snapForce == 0) return off()
            val zones = ((1 shl startPosition) or (1 shl endPosition)) and 0xFFFF
            val force = ((strength - 1) and 0x07) or (((snapForce - 1) and 0x07) shl 3)
            return ByteArray(11).apply {
                this[0] = MODE_BOW
                this[1] = (zones and 0xFF).toByte()
                this[2] = ((zones shr 8) and 0xFF).toByte()
                this[3] = (force and 0xFF).toByte()
                this[4] = ((force ushr 8) and 0xFF).toByte()
            }
        }

        fun galloping(
            startPosition: Int,
            endPosition: Int,
            firstFoot: Int,
            secondFoot: Int,
            frequency: Int
        ): ByteArray {
            if (startPosition !in 0..8 || endPosition !in (startPosition + 1)..9 ||
                firstFoot !in 0..6 || secondFoot !in (firstFoot + 1)..7 || frequency !in 0..255
            ) return off()
            if (frequency == 0) return off()
            val zones = ((1 shl startPosition) or (1 shl endPosition)) and 0xFFFF
            val ratio = (secondFoot and 0x07) or ((firstFoot and 0x07) shl 3)
            return ByteArray(11).apply {
                this[0] = MODE_GALLOPING
                this[1] = (zones and 0xFF).toByte()
                this[2] = ((zones shr 8) and 0xFF).toByte()
                this[3] = (ratio and 0xFF).toByte()
                this[4] = (frequency and 0xFF).toByte()
            }
        }

        fun machine(
            startPosition: Int,
            endPosition: Int,
            amplitudeA: Int,
            amplitudeB: Int,
            frequency: Int,
            period: Int
        ): ByteArray {
            if (startPosition !in 0..8 || endPosition !in (startPosition + 1)..9 ||
                amplitudeA !in 0..7 || amplitudeB !in 0..7 || frequency !in 0..255 || period !in 0..255
            ) return off()
            if (frequency == 0) return off()
            val zones = ((1 shl startPosition) or (1 shl endPosition)) and 0xFFFF
            val amp = (amplitudeA and 0x07) or ((amplitudeB and 0x07) shl 3)
            return ByteArray(11).apply {
                this[0] = MODE_MACHINE
                this[1] = (zones and 0xFF).toByte()
                this[2] = ((zones shr 8) and 0xFF).toByte()
                this[3] = (amp and 0xFF).toByte()
                this[4] = (frequency and 0xFF).toByte()
                this[5] = (period and 0xFF).toByte()
            }
        }

        fun simpleFeedback(start: Int, force: Int): ByteArray = ByteArray(11).apply {
            this[0] = MODE_SIMPLE_FEEDBACK
            this[1] = (start and 0xFF).toByte()
            this[2] = (force and 0xFF).toByte()
        }

        fun simpleWeapon(start: Int, end: Int, force: Int): ByteArray = ByteArray(11).apply {
            this[0] = MODE_SIMPLE_WEAPON
            this[1] = (start and 0xFF).toByte()
            this[2] = (end and 0xFF).toByte()
            this[3] = (force and 0xFF).toByte()
        }

        fun simpleVibration(position: Int, amplitude: Int, frequency: Int): ByteArray {
            if (amplitude <= 0 || frequency <= 0) return off()
            return ByteArray(11).apply {
                this[0] = MODE_SIMPLE_VIBRATION
                this[1] = (frequency and 0xFF).toByte()
                this[2] = (amplitude and 0xFF).toByte()
                this[3] = (position and 0xFF).toByte()
            }
        }

        fun limitedFeedback(start: Int, force: Int): ByteArray {
            if (force !in 0..10 || force == 0) return off()
            return ByteArray(11).apply {
                this[0] = MODE_LIMITED_FEEDBACK
                this[1] = (start and 0xFF).toByte()
                this[2] = (force and 0xFF).toByte()
            }
        }

        fun limitedWeapon(start: Int, end: Int, force: Int): ByteArray {
            if (start < 16 || end < start + 100 || force !in 0..10 || force == 0) return off()
            return ByteArray(11).apply {
                this[0] = MODE_LIMITED_WEAPON
                this[1] = (start and 0xFF).toByte()
                this[2] = (end and 0xFF).toByte()
                this[3] = (force and 0xFF).toByte()
            }
        }
    }

    fun isActiveTriggerMode(mode: Byte): Boolean {
        val m = mode.toInt() and 0xFF
        return m != 0x00 && m != 0x05
    }

    fun shouldClearRumble(report: ByteArray): Boolean {
        if (report.size < REPORT_SIZE_OUTPUT || report[0] != REPORT_ID_OUTPUT) return false
        val flag0 = report[1].toInt() and 0xFF
        val flag1 = report[2].toInt() and 0xFF
        val flag2 = report[39].toInt() and 0xFF
        val allZeroFlags = flag0 == 0 && flag1 == 0 && flag2 == 0
        if (allZeroFlags) return true
        return (flag0 and 0x03) == 1 ||
                (((flag0 and 0x03) != 0 || (flag2 and 0x04) != 0) && report[3].toInt() == 0 && report[4].toInt() == 0)
    }

    fun shouldClearVisibleLeds(report: ByteArray): Boolean {
        if (report.size < REPORT_SIZE_OUTPUT || report[0] != REPORT_ID_OUTPUT) return false
        val flag0 = report[1].toInt() and 0xFF
        val flag1 = report[2].toInt() and 0xFF
        val flag2 = report[39].toInt() and 0xFF
        if (flag0 == 0 && flag1 == 0 && flag2 == 0) return true
        return (flag1 and 0x08) != 0
    }

    fun buildRumbleClearReport(): ByteArray {
        val report = ByteArray(REPORT_SIZE_OUTPUT)
        report[0] = REPORT_ID_OUTPUT
        report[1] = 0x03
        report[2] = 0x40
        report[39] = 0x04
        return report
    }

    fun buildOutputReport(
        leftMotor: Int = 0,
        rightMotor: Int = 0,
        r: Int = 0,
        g: Int = 0,
        b: Int = 0,
        micLed: Int = 0,
        playerLeds: Int = 0,
        playerLedBrightness: Int = PLAYER_LED_BRIGHTNESS_HIGH,
        rightTriggerEffect: ByteArray? = null,
        leftTriggerEffect: ByteArray? = null,
        isEdge: Boolean = false,
        disableEdgeFnSwitching: Boolean = false,
        updateLightbar: Boolean = true,
        lightbarSetup: Int = 0x02
    ): ByteArray {
        val report = ByteArray(REPORT_SIZE_OUTPUT)
        report[0] = REPORT_ID_OUTPUT
        var enable1 = 0
        if (leftMotor > 0 || rightMotor > 0) enable1 = enable1 or 0x01 or 0x02
        if (rightTriggerEffect != null) enable1 = enable1 or 0x04
        if (leftTriggerEffect != null) enable1 = enable1 or 0x08
        report[1] = enable1.toByte()

        var enable2 = 0
        if (micLed != 0) enable2 = enable2 or 0x01
        if (updateLightbar || r != 0 || g != 0 || b != 0) enable2 = enable2 or 0x04
        if (playerLeds != 0 || playerLedBrightness != PLAYER_LED_BRIGHTNESS_HIGH) enable2 = enable2 or 0x10
        if (leftMotor > 0 || rightMotor > 0 || rightTriggerEffect != null || leftTriggerEffect != null) {
            enable2 = enable2 or 0x40
        }
        report[2] = enable2.toByte()

        report[3] = (rightMotor and 0xFF).toByte()
        report[4] = (leftMotor and 0xFF).toByte()
        report[9] = (micLed and 0xFF).toByte()

        rightTriggerEffect?.let {
            System.arraycopy(it, 0, report, 11, minOf(it.size, 11))
        }
        leftTriggerEffect?.let {
            System.arraycopy(it, 0, report, 22, minOf(it.size, 11))
        }

        if (isEdge) {
            report[39] = 0xC7.toByte()
        } else {
            report[39] = 0x07.toByte()
        }

        if (isEdge || disableEdgeFnSwitching) {
            report[41] = 0x80.toByte()
        }

        report[42] = (lightbarSetup and 0xFF).toByte()
        report[43] = (playerLedBrightness and 0x03).toByte()
        report[44] = (playerLeds and 0xFF).toByte()
        report[45] = (r and 0xFF).toByte()
        report[46] = (g and 0xFF).toByte()
        report[47] = (b and 0xFF).toByte()
        return report
    }

    fun buildOutputReport(
        baseReport: ByteArray,
        isEdge: Boolean = false,
        disableEdgeFnSwitching: Boolean = false
    ): ByteArray {
        val report = baseReport.copyOf(REPORT_SIZE_OUTPUT)
        report[0] = REPORT_ID_OUTPUT
        if (isEdge) {
            report[39] = 0xC7.toByte()
        }
        if (isEdge || disableEdgeFnSwitching) {
            report[41] = 0x80.toByte()
        }
        return report
    }

    fun buildMicMuteReport(muted: Boolean): ByteArray {
        val report = ByteArray(REPORT_SIZE_OUTPUT)
        report[0] = REPORT_ID_OUTPUT
        report[2] = 0x03
        report[9] = if (muted) 1 else 0
        report[10] = if (muted) 0x10 else 0
        return report
    }

    fun buildBluetoothOutputReport(
        usbReport: ByteArray,
        seq: Int = 0,
        isEdge: Boolean = false,
        disableEdgeFnSwitching: Boolean = false,
        btInitializing: Boolean = false
    ): ByteArray {
        val bt = ByteArray(REPORT_SIZE_BT_OUTPUT)
        bt[0] = REPORT_ID_BT_OUTPUT
        bt[1] = (0x02 or ((seq and 0x0F) shl 4)).toByte()
        val copyLen = minOf(usbReport.size - 1, 47)
        if (copyLen > 0) {
            System.arraycopy(usbReport, 1, bt, 2, copyLen)
        }
        if (isEdge) {
            bt[40] = 0xC7.toByte()
            bt[49] = 0x03.toByte()
        }
        if (isEdge || disableEdgeFnSwitching) {
            bt[42] = 0x80.toByte()
        }

        // DSX 3.0-second Bluetooth startup guard: prevent LightbarSetup=0x02 / RGB from locking up
        // the controller's blue fade-in animation during the first 3.0s of Bluetooth connection.
        if (btInitializing) {
            bt[3] = (bt[3].toInt() and 0x04.inv()).toByte()
            bt[43] = 0x01.toByte()
            bt[46] = 0
            bt[47] = 0
            bt[48] = 0
        }

        val crc = java.util.zip.CRC32()
        crc.update(0xA2)
        crc.update(bt, 0, 74)
        val crcVal = crc.value
        bt[74] = (crcVal and 0xFF).toByte()
        bt[75] = ((crcVal shr 8) and 0xFF).toByte()
        bt[76] = ((crcVal shr 16) and 0xFF).toByte()
        bt[77] = ((crcVal shr 24) and 0xFF).toByte()
        return bt
    }
}
