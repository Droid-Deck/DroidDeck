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
class LoadingState(context: Context, private val steam: Boolean = true) {
    var visible by mutableStateOf(true)
    var step by mutableStateOf(context.getString(com.droiddeck.launcher.R.string.loadstate_starting))
        private set
    /** What [step] is about, so the screen picks its checklist stage without reading the (translated) words. */
    var topic by mutableStateOf(Topic.SESSION)
        private set
    /** [step] is a download or install line worth showing as it is, not one of the script's own notes. */
    var readable by mutableStateOf(false)
        private set
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
    private val res = context.resources
    private val startedAt = SystemClock.elapsedRealtime()

    /** Once a second: the clock and the hint. */
    fun tick() {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        elapsed = res.getString(com.droiddeck.launcher.R.string.loadstate_elapsed, seconds / 60, seconds % 60)
        hint = hints[((seconds / 8) % hints.size).toInt()]
    }

    /** A line the app itself writes (an install's progress, the session starting). */
    fun say(line: String, topic: Topic, readable: Boolean) {
        step = line
        this.topic = topic
        this.readable = readable
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
        step = state.line
        topic = state.topic
        readable = state.readable
        percent = state.percent
    }

    private class Line(val line: String, val percent: Int, val topic: Topic, val readable: Boolean)

    /** Only the tail is read: the client alone writes megabytes an hour. */
    private fun read(context: Context, log: File?): Line? {
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
            downloadAt > stepAt && downloadPercent >= 0 -> Line(
                context.getString(com.droiddeck.launcher.R.string.loadstate_steam_update, downloadPercent), downloadPercent,
                Topic.STEAM, readable = true,
            )
            clientDownloadAt == stepAt && clientDownload != null -> Line(
                context.getString(com.droiddeck.launcher.R.string.loadstate_steam_client, clientDownload), clientPercent,
                Topic.STEAM, readable = true,
            )
            stepText != null -> Line(stepText, -1, topicOf(stepText, steam), readableScriptStep(stepText))
            else -> null
        }
    }

    /** The checklist stages a loading line can move the screen to. */
    enum class Topic { RUNTIME, SESSION, DESKTOP, STEAM, STEAM_STARTING, OTHER }

    companion object {
        /**
         * A session script "== STEP" line's topic in a Steam ([steam]) or desktop session; those
         * lines are English whatever the app's language.
         */
        internal fun topicOf(scriptStep: String, steam: Boolean): Topic {
            val t = scriptStep.lowercase()
            return when {
                "linux runtime" in t -> Topic.RUNTIME
                "starting the session" in t -> Topic.SESSION
                !steam && "desktop" in t -> Topic.DESKTOP
                steam && "starting the steam client" in t -> Topic.STEAM_STARTING
                steam && ("steam" in t || "client" in t || "proton" in t || "library" in t || "compatibility" in t) -> Topic.STEAM
                else -> Topic.OTHER
            }
        }

        /** The script's install lines are worth reading as they are; its other notes are not. */
        internal fun readableScriptStep(scriptStep: String): Boolean {
            val t = scriptStep.lowercase()
            return t.startsWith("download") || t.startsWith("checking the linux") || t.startsWith("unpacking") || t.startsWith("installing")
        }

        private const val TAIL_BYTES = 48L * 1024
        private val UPDATE_PROGRESS = Regex("""Downloading update \((\d+) of (\d+) KB\)""")
        private val INSTALL_COUNT = Regex("""downloading Steam: (\S+) \((\d+)/(\d+)\)""")
    }
}
