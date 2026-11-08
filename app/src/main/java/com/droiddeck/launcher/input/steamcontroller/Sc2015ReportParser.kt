package com.droiddeck.launcher.input.steamcontroller

import kotlin.math.abs

object Sc2015ReportParser {
    const val REPORT_STATE = 0x04
    const val REPORT_STATUS = 0x05

    const val CHUNK_BUTTONS_1 = 0x0010
    const val CHUNK_TRIGGERS = 0x0020
    const val CHUNK_BUTTONS_3 = 0x0040
    const val CHUNK_LEFT_JOYSTICK = 0x0080
    const val CHUNK_LEFT_TRACKPAD = 0x0100
    const val CHUNK_RIGHT_TRACKPAD = 0x0200
    const val CHUNK_IMU_ACCEL = 0x0400
    const val CHUNK_IMU_GYRO = 0x0800
    const val CHUNK_IMU_QUAT = 0x1000

    const val BUTTON_RT_FULL = 0x000001
    const val BUTTON_LT_FULL = 0x000002
    const val BUTTON_RB = 0x000004
    const val BUTTON_LB = 0x000008
    const val BUTTON_Y = 0x000010
    const val BUTTON_B = 0x000020
    const val BUTTON_X = 0x000040
    const val BUTTON_A = 0x000080
    const val BUTTON_DPAD_UP = 0x000100
    const val BUTTON_DPAD_RIGHT = 0x000200
    const val BUTTON_DPAD_LEFT = 0x000400
    const val BUTTON_DPAD_DOWN = 0x000800
    const val BUTTON_SELECT = 0x001000
    const val BUTTON_STEAM = 0x002000
    const val BUTTON_START = 0x004000
    const val BUTTON_LGRIP = 0x008000
    const val BUTTON_RGRIP = 0x010000
    const val BUTTON_LPAD_CLICK = 0x020000
    const val BUTTON_RPAD_CLICK = 0x040000
    const val BUTTON_LPAD_TOUCH = 0x080000
    const val BUTTON_RPAD_TOUCH = 0x100000
    const val BUTTON_L3 = 0x400000
    const val BUTTON_LPAD_AND_JOYSTICK = 0x800000

    const val TRITON_STATE_BYTES = 45
    const val TRITON_FEATURE_BYTES = 62

    private const val TRITON_A = 0x00000001
    private const val TRITON_B = 0x00000002
    private const val TRITON_X = 0x00000004
    private const val TRITON_Y = 0x00000008
    private const val TRITON_VIEW = 0x00000040
    private const val TRITON_R4 = 0x00000080
    private const val TRITON_R = 0x00000200
    private const val TRITON_DOWN = 0x00000400
    private const val TRITON_RIGHT = 0x00000800
    private const val TRITON_LEFT = 0x00001000
    private const val TRITON_UP = 0x00002000
    private const val TRITON_MENU = 0x00004000
    private const val TRITON_L3 = 0x00008000
    private const val TRITON_STEAM = 0x00010000
    private const val TRITON_L4 = 0x00020000
    private const val TRITON_L = 0x00080000
    private const val TRITON_RPAD_TOUCH = 0x00200000
    private const val TRITON_RPAD_CLICK = 0x00400000
    private const val TRITON_RTRIGGER_CLICK = 0x00800000
    private const val TRITON_LSTICK_TOUCH = 0x01000000
    private const val TRITON_LPAD_TOUCH = 0x02000000
    private const val TRITON_LPAD_CLICK = 0x04000000
    private const val TRITON_LTRIGGER_CLICK = 0x08000000
    private const val TRITON_RGRIP_TOUCH = 0x10000000
    private const val TRITON_LGRIP_TOUCH = 0x20000000

    private const val STICK_TOUCH_THRESHOLD = 2000

    private const val CMD_GET_ATTRIBUTES_VALUES = 0x83
    private const val CMD_SET_SETTINGS_VALUES = 0x87
    private const val CMD_TRIGGER_HAPTIC_PULSE = 0x8F
    private const val CMD_GET_STRING_ATTRIBUTE = 0xAE
    private const val CMD_GET_CHIP_ID = 0xBA
    private const val SETTING_IMU_MODE = 0x30

    private const val TRITON_BLE_PRODUCT_ID = 0x1303
    private const val TRITON_HWID = 72
    private const val TRITON_FW_TIMESTAMP = 0x6AC686B3

    sealed interface ParsedReport {
        data class State(val optionMask: Int) : ParsedReport
        data class Status(val voltageMv: Int, val batteryPercent: Int) : ParsedReport
        data class Unknown(val reportType: Int) : ParsedReport
    }

    fun parse(payload: ByteArray, state: Sc2015State): ParsedReport? {
        if (payload.isEmpty()) return null
        return when (val reportType = payload[0].toInt() and 0x0F) {
            REPORT_STATE -> parseStateReport(payload, state)
            REPORT_STATUS -> parseStatusReport(payload, state)
            else -> {
                SteamControllerDebug.log("2015 BLE unknown reportType=0x%02x raw=[%s]".format(reportType, SteamControllerDebug.hex(payload)))
                ParsedReport.Unknown(reportType)
            }
        }
    }

    private fun parseStateReport(payload: ByteArray, state: Sc2015State): ParsedReport.State? {
        if (payload.size < 2) return null
        val optionMask = (payload[0].toInt() and 0xF0) or ((payload[1].toInt() and 0xFF) shl 8)
        var offset = 2

        if (optionMask and CHUNK_BUTTONS_1 != 0) {
            if (offset + 3 > payload.size) return null
            val b0 = payload[offset].toInt() and 0xFF
            val b1 = payload[offset + 1].toInt() and 0xFF
            val b2 = payload[offset + 2].toInt() and 0xFF
            state.buttons = (state.buttons and 0xFFFFFF.inv()) or b0 or (b1 shl 8) or (b2 shl 16)
            offset += 3
        }

        if (optionMask and CHUNK_TRIGGERS != 0) {
            if (offset + 2 > payload.size) return null
            state.leftTrigger = payload[offset].toInt() and 0xFF
            state.rightTrigger = payload[offset + 1].toInt() and 0xFF
            offset += 2
        }

        if (optionMask and CHUNK_BUTTONS_3 != 0) {
            if (offset + 3 > payload.size) return null
            offset += 3
        }

        if (optionMask and CHUNK_LEFT_JOYSTICK != 0) {
            if (offset + 4 > payload.size) return null
            state.stickX = readShortLE(payload, offset)
            state.stickY = readShortLE(payload, offset + 2)
            offset += 4
        }

        if (optionMask and CHUNK_LEFT_TRACKPAD != 0) {
            if (offset + 4 > payload.size) return null
            state.leftPadX = readShortLE(payload, offset)
            state.leftPadY = readShortLE(payload, offset + 2)
            offset += 4
        }

        if (optionMask and CHUNK_RIGHT_TRACKPAD != 0) {
            if (offset + 4 > payload.size) return null
            state.rightPadX = readShortLE(payload, offset)
            state.rightPadY = readShortLE(payload, offset + 2)
            offset += 4
        }

        if (optionMask and CHUNK_IMU_ACCEL != 0) {
            if (offset + 6 > payload.size) return null
            state.accelX = readShortLE(payload, offset)
            state.accelY = readShortLE(payload, offset + 2)
            state.accelZ = readShortLE(payload, offset + 4)
            offset += 6
        }

        if (optionMask and CHUNK_IMU_GYRO != 0) {
            if (offset + 6 > payload.size) return null
            state.gyroX = readShortLE(payload, offset)
            state.gyroY = readShortLE(payload, offset + 2)
            state.gyroZ = readShortLE(payload, offset + 4)
            offset += 6
        }

        if (optionMask and CHUNK_IMU_QUAT != 0) {
            if (offset + 8 > payload.size) return null
            state.quatW = readShortLE(payload, offset)
            state.quatX = readShortLE(payload, offset + 2)
            state.quatY = readShortLE(payload, offset + 4)
            state.quatZ = readShortLE(payload, offset + 6)
        }

        if (state.buttons and BUTTON_LPAD_TOUCH == 0 && optionMask and CHUNK_LEFT_TRACKPAD == 0) {
            state.leftPadX = 0
            state.leftPadY = 0
        }
        if (state.buttons and BUTTON_RPAD_TOUCH == 0 && optionMask and CHUNK_RIGHT_TRACKPAD == 0) {
            state.rightPadX = 0
            state.rightPadY = 0
        }

        return ParsedReport.State(optionMask)
    }

    private fun parseStatusReport(payload: ByteArray, state: Sc2015State): ParsedReport.Status? {
        if (payload.size < 13) return null
        val voltageMv = readUShortLE(payload, 11)
        val rawPercent = if (payload.size >= 14) payload[13].toInt() and 0xFF else 0
        val percent = if (rawPercent in 1..100) rawPercent else estimateBatteryPercent(voltageMv)
        if (voltageMv in 1500..4500) {
            state.batteryVoltageMv = voltageMv
            state.batteryPercent = percent
        }
        SteamControllerDebug.log("2015 BLE battery status: ${state.batteryVoltageMv} mV (${state.batteryPercent}%)")
        return ParsedReport.Status(state.batteryVoltageMv, state.batteryPercent)
    }

    fun estimateBatteryPercent(voltageMv: Int): Int =
        ((voltageMv - 2000) * 100 / 1000).coerceIn(0, 100)

    fun toTritonReport(
        state: Sc2015State,
        packetNum: Int,
        elapsedUs: Long,
        out: ByteArray = ByteArray(TRITON_STATE_BYTES)
    ): ByteArray {
        out.fill(0)
        out[0] = (packetNum and 0xFF).toByte()

        val b = state.buttons
        var bits = TRITON_LGRIP_TOUCH or TRITON_RGRIP_TOUCH
        if (b and BUTTON_A != 0) bits = bits or TRITON_A
        if (b and BUTTON_B != 0) bits = bits or TRITON_B
        if (b and BUTTON_X != 0) bits = bits or TRITON_X
        if (b and BUTTON_Y != 0) bits = bits or TRITON_Y
        if (b and BUTTON_LB != 0) bits = bits or TRITON_L
        if (b and BUTTON_RB != 0) bits = bits or TRITON_R
        if (b and BUTTON_SELECT != 0) bits = bits or TRITON_MENU
        if (b and BUTTON_START != 0) bits = bits or TRITON_VIEW
        if (b and BUTTON_STEAM != 0) bits = bits or TRITON_STEAM
        if (b and BUTTON_L3 != 0) bits = bits or TRITON_L3
        if (b and BUTTON_LGRIP != 0) bits = bits or TRITON_L4
        if (b and BUTTON_RGRIP != 0) bits = bits or TRITON_R4
        if (b and BUTTON_DPAD_UP != 0) bits = bits or TRITON_UP
        if (b and BUTTON_DPAD_DOWN != 0) bits = bits or TRITON_DOWN
        if (b and BUTTON_DPAD_LEFT != 0) bits = bits or TRITON_LEFT
        if (b and BUTTON_DPAD_RIGHT != 0) bits = bits or TRITON_RIGHT
        if (b and BUTTON_LPAD_TOUCH != 0) bits = bits or TRITON_LPAD_TOUCH
        if (b and BUTTON_LPAD_CLICK != 0) bits = bits or TRITON_LPAD_CLICK
        if (b and BUTTON_RPAD_TOUCH != 0) bits = bits or TRITON_RPAD_TOUCH
        if (b and BUTTON_RPAD_CLICK != 0) bits = bits or TRITON_RPAD_CLICK

        val ltFull = b and BUTTON_LT_FULL != 0
        val rtFull = b and BUTTON_RT_FULL != 0
        if (ltFull || state.leftTrigger >= 250) bits = bits or TRITON_LTRIGGER_CLICK
        if (rtFull || state.rightTrigger >= 250) bits = bits or TRITON_RTRIGGER_CLICK

        if (abs(state.stickX.toInt()) > STICK_TOUCH_THRESHOLD || abs(state.stickY.toInt()) > STICK_TOUCH_THRESHOLD) {
            bits = bits or TRITON_LSTICK_TOUCH
        }

        writeIntLE(out, 1, bits)

        val ltAnalog = maxOf((state.leftTrigger.coerceIn(0, 255) * 32767) / 255, if (ltFull) 32767 else 0)
        val rtAnalog = maxOf((state.rightTrigger.coerceIn(0, 255) * 32767) / 255, if (rtFull) 32767 else 0)
        writeShortLE(out, 5, ltAnalog)
        writeShortLE(out, 7, rtAnalog)

        writeShortLE(out, 9, state.stickX.toInt().coerceIn(-32767, 32767))
        writeShortLE(out, 11, state.stickY.toInt().coerceIn(-32767, 32767))

        writeShortLE(out, 17, state.leftPadX.toInt())
        writeShortLE(out, 19, state.leftPadY.toInt())
        writeShortLE(out, 21, if (b and BUTTON_LPAD_CLICK != 0) 32767 else 0)

        writeShortLE(out, 23, state.rightPadX.toInt())
        writeShortLE(out, 25, state.rightPadY.toInt())
        writeShortLE(out, 27, if (b and BUTTON_RPAD_CLICK != 0) 32767 else 0)

        writeIntLE(out, 29, elapsedUs.toInt())

        writeShortLE(out, 33, state.accelX.toInt())
        writeShortLE(out, 35, state.accelY.toInt())
        writeShortLE(out, 37, state.accelZ.toInt())

        writeShortLE(out, 39, state.gyroX.toInt())
        writeShortLE(out, 41, state.gyroY.toInt())
        writeShortLE(out, 43, state.gyroZ.toInt())

        return out
    }

    fun buildFeatureReply(command: Int, batteryMv: Int = 3000): ByteArray {
        val reply = ByteArray(TRITON_FEATURE_BYTES)
        reply[0] = (command and 0xFF).toByte()
        when (command and 0xFF) {
            CMD_GET_ATTRIBUTES_VALUES -> {
                val ids = intArrayOf(0x01, 0x02, 0x04, 0x09, 0x0A, 0x0B)
                val values = intArrayOf(
                    TRITON_BLE_PRODUCT_ID,
                    0,
                    TRITON_FW_TIMESTAMP,
                    TRITON_HWID,
                    0,
                    batteryMv.coerceIn(1500, 4500)
                )
                reply[1] = (ids.size * 5).toByte()
                for (i in ids.indices) {
                    val base = 2 + i * 5
                    reply[base] = ids[i].toByte()
                    writeIntLE(reply, base + 1, values[i])
                }
            }
            CMD_GET_STRING_ATTRIBUTE -> {
                val serial = "DROIDDECKSC01".encodeToByteArray()
                reply[1] = 0x14
                reply[2] = 0x01
                serial.copyInto(reply, destinationOffset = 3, endIndex = minOf(serial.size, reply.size - 3))
            }
            CMD_GET_CHIP_ID -> {
                val chip = "DROIDDECKCHIP02".encodeToByteArray()
                reply[1] = 0x11
                chip.copyInto(reply, destinationOffset = 3, endIndex = minOf(chip.size, reply.size - 3))
            }
        }
        return reply
    }

    fun extractImuModeCommand(featureReport: ByteArray): ByteArray? {
        if (featureReport.size < 4) return null
        val cmdOffset = if (featureReport[0].toInt() and 0xFF == CMD_SET_SETTINGS_VALUES) 0 else 1
        if (cmdOffset + 2 >= featureReport.size) return null
        if (featureReport[cmdOffset].toInt() and 0xFF != CMD_SET_SETTINGS_VALUES) return null
        val length = featureReport[cmdOffset + 1].toInt() and 0xFF
        val end = minOf(featureReport.size, cmdOffset + 2 + length)
        var i = cmdOffset + 2
        while (i + 3 <= end) {
            val settingId = featureReport[i].toInt() and 0xFF
            if (settingId == SETTING_IMU_MODE) {
                return byteArrayOf(
                    0xC0.toByte(),
                    CMD_SET_SETTINGS_VALUES.toByte(),
                    0x03,
                    SETTING_IMU_MODE.toByte(),
                    featureReport[i + 1],
                    featureReport[i + 2]
                )
            }
            i += 3
        }
        return null
    }

    fun toHapticPulseCommand(outputReport: ByteArray): ByteArray? {
        if (outputReport.isEmpty()) return null
        return when (outputReport[0].toInt() and 0xFF) {
            0x80 -> {
                if (outputReport.size < 9) return null
                val left = readUShortLE(outputReport, 4)
                val right = readUShortLE(outputReport, 7)
                if (left == 0 && right == 0) return null
                val actuator = if (left >= right) 1 else 0
                val intensity = maxOf(left, right)
                val onUs = (150 + (intensity * 650 / 65535)).coerceIn(150, 800)
                val offUs = 2000
                val repeat = 4
                buildHapticPulse2015(actuator, onUs, offUs, repeat)
            }
            0x81 -> {
                if (outputReport.size < 8) return null
                val side = outputReport[1].toInt() and 0xFF
                val actuator = if (side == 0) 1 else 0
                val onUs = readUShortLE(outputReport, 2).coerceIn(50, 4000)
                val offUs = readUShortLE(outputReport, 4).coerceIn(50, 4000)
                val repeat = readUShortLE(outputReport, 6).coerceIn(1, 32)
                buildHapticPulse2015(actuator, onUs, offUs, repeat)
            }
            0x82, 0x83, 0x84, 0x85 -> buildHapticPulse2015(actuator = 0, onUs = 300, offUs = 1000, repeat = 1)
            else -> null
        }
    }

    private fun buildHapticPulse2015(actuator: Int, onUs: Int, offUs: Int, repeat: Int): ByteArray =
        byteArrayOf(
            0xC0.toByte(),
            CMD_TRIGGER_HAPTIC_PULSE.toByte(),
            0x07,
            (actuator and 0xFF).toByte(),
            (onUs and 0xFF).toByte(),
            ((onUs ushr 8) and 0xFF).toByte(),
            (offUs and 0xFF).toByte(),
            ((offUs ushr 8) and 0xFF).toByte(),
            (repeat and 0xFF).toByte(),
            ((repeat ushr 8) and 0xFF).toByte()
        )

    private fun readShortLE(data: ByteArray, offset: Int): Short =
        ((data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)).toShort()

    private fun readUShortLE(data: ByteArray, offset: Int): Int =
        (data[offset].toInt() and 0xFF) or ((data[offset + 1].toInt() and 0xFF) shl 8)

    private fun writeShortLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
    }

    private fun writeIntLE(data: ByteArray, offset: Int, value: Int) {
        data[offset] = (value and 0xFF).toByte()
        data[offset + 1] = ((value ushr 8) and 0xFF).toByte()
        data[offset + 2] = ((value ushr 16) and 0xFF).toByte()
        data[offset + 3] = ((value ushr 24) and 0xFF).toByte()
    }
}
