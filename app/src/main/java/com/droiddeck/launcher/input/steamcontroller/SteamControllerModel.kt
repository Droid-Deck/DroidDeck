package com.droiddeck.launcher.input.steamcontroller

import java.util.UUID

enum class SteamControllerModel {
    TRITON_2025,
    ORIGINAL_2015
}

object SteamControllerUuids {
    val SERVICE: UUID = UUID.fromString("100F6C32-1735-4313-B402-38567131E5F3")
    val INPUT_2015: UUID = UUID.fromString("100F6C33-1735-4313-B402-38567131E5F3")
    val REPORT: UUID = UUID.fromString("100F6C34-1735-4313-B402-38567131E5F3")
    val INPUT_0X45: UUID = UUID.fromString("100F6C7A-1735-4313-B402-38567131E5F3")
    val INPUT_0X47: UUID = UUID.fromString("100F6C7C-1735-4313-B402-38567131E5F3")
    val CCCD: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")

    // Disables right-trackpad mouse emulation (setting 0x08 = TRACKPAD_NONE 0x0007), which exits BLE lizard mode.
    val VALVE_MODE_2015: ByteArray = byteArrayOf(
        0xC0.toByte(),
        0x87.toByte(),
        0x03,
        0x08,
        0x07,
        0x00
    )

    fun isSteamControllerName(name: String?): Boolean {
        if (name.isNullOrBlank()) return false
        val trimmed = name.trim()
        return trimmed.startsWith("Steam Controller", ignoreCase = true) ||
            trimmed.startsWith("SteamController", ignoreCase = true) ||
            trimmed.startsWith("Steam Ctrl", ignoreCase = true)
    }
}
