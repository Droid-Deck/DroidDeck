package com.droiddeck.launcher.session

import android.content.Context
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File
import java.io.RandomAccessFile
import java.nio.charset.StandardCharsets

/**
 * The loading overlay's state: what to say, read out of the session log, and a clock so a user
 * can see that time is passing even when the log is quiet. The runtime script marks its
 * milestones with "== STEP"; the client's own bootstrap does not, but it does print its download
 * progress, and lifting that out is the difference between "starting the Steam client" for three
 * minutes and a percentage that moves.
 */
class LoadingState(context: Context, steam: Boolean = true) {
    var visible by mutableStateOf(true)
    var step by mutableStateOf("Starting the session…")
    var percent by mutableIntStateOf(-1)
    var elapsed by mutableStateOf("")
    var hint by mutableStateOf("")
    var ended by mutableStateOf(false)
    /** The technical side of an ended session (exit status, log path), under the advice in [step]. */
    var endedDetail by mutableStateOf<String?>(null)

    // The desktop and the emulators get their own: the Steam ones talk about Steam.
    private val hints = context.resources.getStringArray(
        if (steam) com.droiddeck.launcher.R.array.loading_hints else com.droiddeck.launcher.R.array.loading_hints_desktop,
    )
    private val startedAt = SystemClock.elapsedRealtime()

    /** Once a second: the clock and the hint. */
    fun tick() {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        elapsed = String.format(java.util.Locale.US, "%d:%02d elapsed · still working", seconds / 60, seconds % 60)
        hint = hints[((seconds / 8) % hints.size).toInt()]
    }

    fun showEnded(message: String, detail: String? = null) {
        visible = true
        ended = true
        step = message
        endedDetail = detail
    }

    /** Re-reads the end of the log and updates the line and the bar. */
    fun update(context: Context, log: File?) {
        val state = read(context, log) ?: return
        step = state.first
        percent = state.second
    }

    /** Only the tail is read: the client alone writes megabytes an hour. */
    private fun read(context: Context, log: File?): Pair<String, Int>? {
        if (log == null || !log.isFile) return null
        val text = try {
            RandomAccessFile(log, "r").use { file ->
                val length = file.length()
                val want = minOf(length, TAIL_BYTES)
                file.seek(length - want)
                val bytes = ByteArray(want.toInt())
                file.readFully(bytes)
                String(bytes, StandardCharsets.UTF_8)
            }
        } catch (e: Exception) {
            return null
        }
        var stepAt = -1
        var stepText: String? = null
        var downloadAt = -1
        var downloadPercent = -1
        var clientDownloadAt = -1
        var clientDownload: String? = null
        var clientPercent = -1
        var offset = 0
        for (line in text.split('\n')) {
            val at = offset
            offset += line.length + 1
            if (line.startsWith("== STEP ")) {
                stepAt = at
                stepText = line.substringAfter("== STEP ").substringAfter(' ')
                val m = INSTALL_COUNT.find(line)
                if (m != null) {
                    clientDownloadAt = at
                    clientDownload = m.groupValues[1]
                    clientPercent = m.groupValues[2].toInt() * 100 / maxOf(1, m.groupValues[3].toInt())
                }
            } else {
                val m = UPDATE_PROGRESS.find(line)
                if (m != null) {
                    downloadAt = at
                    val done = m.groupValues[1].toLong()
                    val total = maxOf(1L, m.groupValues[2].toLong())
                    downloadPercent = (done * 100 / total).toInt()
                }
            }
        }
        return when {
            downloadAt > stepAt && downloadPercent >= 0 ->
                Pair("Downloading the Steam client update · $downloadPercent%", downloadPercent)
            clientDownloadAt == stepAt && clientDownload != null ->
                Pair("Downloading the Steam client · $clientDownload", clientPercent)
            stepText != null -> Pair(stepText, -1)
            else -> null
        }
    }

    companion object {
        private const val TAIL_BYTES = 48L * 1024
        private val UPDATE_PROGRESS = Regex("""Downloading update \((\d+) of (\d+) KB\)""")
        private val INSTALL_COUNT = Regex("""downloading Steam: (\S+) \((\d+)/(\d+)\)""")
    }
}
