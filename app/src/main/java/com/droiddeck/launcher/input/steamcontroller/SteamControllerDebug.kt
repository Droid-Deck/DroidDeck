package com.droiddeck.launcher.input.steamcontroller

import android.util.Log

object SteamControllerDebug {
    const val DEFAULT_TAG = "SteamControllerBle"

    @Volatile
    var enabled: Boolean = false

    fun hex(bytes: ByteArray, limit: Int = bytes.size): String {
        if (bytes.isEmpty()) return ""
        val count = minOf(bytes.size, limit)
        val sb = StringBuilder(count * 3)
        for (i in 0 until count) {
            if (i > 0) sb.append(' ')
            val b = bytes[i].toInt() and 0xFF
            sb.append(HEX_CHARS[b ushr 4])
            sb.append(HEX_CHARS[b and 0x0F])
        }
        if (bytes.size > count) sb.append(" ...")
        return sb.toString()
    }

    fun log(message: String, tag: String = DEFAULT_TAG) {
        if (enabled) {
            runCatching { Log.d(tag, message) }
        }
    }

    inline fun log(tag: String = DEFAULT_TAG, message: () -> String) {
        if (enabled) {
            log(message(), tag)
        }
    }

    private val HEX_CHARS = charArrayOf(
        '0', '1', '2', '3', '4', '5', '6', '7',
        '8', '9', 'a', 'b', 'c', 'd', 'e', 'f'
    )
}
