package com.droiddeck.launcher.input

import android.hardware.usb.UsbDevice
import android.view.InputDevice

object DualSense {
    const val VENDOR_SONY = 0x054C
    const val PRODUCT_DUALSENSE = 0x0CE6
    const val PRODUCT_DUALSENSE_EDGE = 0x0DF2

    const val REPORT_ID_INPUT: Byte = 0x01
    const val REPORT_ID_OUTPUT: Byte = 0x02
    const val REPORT_ID_BT_INPUT: Byte = 0x31
    const val REPORT_ID_BT_OUTPUT: Byte = 0x31
    const val REPORT_SIZE_INPUT = 64
    const val REPORT_SIZE_OUTPUT = 48
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
        val isCharging: Boolean = false
    )

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

    fun parseInputReport(data: ByteArray, offset: Int = 0): State? {
        if (offset < 0 || offset >= data.size) return null
        val reportId = data[offset]
        val off: Int
        if (reportId == REPORT_ID_INPUT) {
            if (offset > data.size - REPORT_SIZE_INPUT) {
                if (offset <= data.size - REPORT_SIZE_BT_BASIC_INPUT) {
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

        val gyroX = shortLE(offset + 16 + off)
        val gyroY = shortLE(offset + 18 + off)
        val gyroZ = shortLE(offset + 20 + off)
        val accelX = shortLE(offset + 22 + off)
        val accelY = shortLE(offset + 24 + off)
        val accelZ = shortLE(offset + 26 + off)

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

        val isEdgeTuningActive = (data[offset + 49 + off].toInt() and 0x0F) in 0x0D..0x0F

        val b53 = data[offset + 53 + off].toInt() and 0xFF
        val batteryRaw = b53 and 0x0F
        val powerState = (b53 ushr 4) and 0x0F
        val batteryPercent = if (b53 == 0) 100 else minOf(batteryRaw * 10 + 5, 100)
        val isCharging = powerState == 0x01 || powerState == 0x02

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
            isCharging = isCharging
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

    fun buildOutputReport(
        leftMotor: Int = 0,
        rightMotor: Int = 0,
        r: Int = 0,
        g: Int = 0,
        b: Int = 0,
        micLed: Int = 0,
        playerLeds: Int = 0,
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
        if (playerLeds != 0) enable2 = enable2 or 0x10
        if (leftMotor > 0 || rightMotor > 0) enable2 = enable2 or 0x40
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
