package com.droiddeck.launcher.session

internal object WineProcesses {
    private const val WINESERVER = "wineserver"

    fun isWineserver(cmdline: ByteArray?, comm: String?): Boolean {
        val program = cmdline?.let { bytes ->
            val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
            String(bytes, 0, end, Charsets.UTF_8).substringAfterLast('/')
        }
        return program == WINESERVER || comm?.trim() == WINESERVER
    }
}
