package com.droiddeck.launcher.session

/** Telling a session's Wine processes apart from what /proc says of them. */
internal object WineProcesses {
    private const val WINESERVER = "wineserver"

    /**
     * Whether a process is a wineserver, from its /proc cmdline (NUL-separated argv) or its comm:
     * Proton's (files/bin-arm64/wineserver), a sync pack's, or one started as a bare name.
     */
    fun isWineserver(cmdline: ByteArray?, comm: String?): Boolean {
        val program = cmdline?.let { bytes ->
            val end = bytes.indexOf(0.toByte()).let { if (it < 0) bytes.size else it }
            String(bytes, 0, end, Charsets.UTF_8).substringAfterLast('/')
        }
        return program == WINESERVER || comm?.trim() == WINESERVER
    }
}
