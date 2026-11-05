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

class LoadingState(context: Context, private val steam: Boolean = true) {
    var visible by mutableStateOf(true)
    var step by mutableStateOf(context.getString(com.droiddeck.launcher.R.string.loadstate_starting))
        private set
    var topic by mutableStateOf(Topic.SESSION)
        private set
    var readable by mutableStateOf(false)
        private set
    var percent by mutableIntStateOf(-1)
    var elapsed by mutableStateOf("")
    var hint by mutableStateOf("")
    var ended by mutableStateOf(false)
    var endedDetail by mutableStateOf<String?>(null)

    private val hints = context.resources.getStringArray(
        if (steam) com.droiddeck.launcher.R.array.loading_hints else com.droiddeck.launcher.R.array.loading_hints_desktop,
    )
    private val res = context.resources
    private val startedAt = SystemClock.elapsedRealtime()

    fun tick() {
        val seconds = (SystemClock.elapsedRealtime() - startedAt) / 1000
        elapsed = res.getString(com.droiddeck.launcher.R.string.loadstate_elapsed, seconds / 60, seconds % 60)
        hint = hints[((seconds / 8) % hints.size).toInt()]
    }

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

    fun update(context: Context, log: File?) {
        val state = read(context, log) ?: return
        step = state.line
        topic = state.topic
        readable = state.readable
        percent = state.percent
    }

    private class Line(val line: String, val percent: Int, val topic: Topic, val readable: Boolean)

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
                clientDownload(line)?.let { (what, percent) ->
                    clientDownloadAt = at
                    clientDownload = what
                    clientPercent = percent
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

    enum class Topic { RUNTIME, SESSION, DESKTOP, STEAM, STEAM_STARTING, OTHER }

    companion object {
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

        internal fun clientDownload(line: String): Pair<String, Int>? {
            val count = INSTALL_COUNT.find(line) ?: return null
            val mb = INSTALL_MEGABYTES.find(line.substring(count.range.last + 1))
            val done = mb?.groupValues?.get(1)?.toLongOrNull()
            val total = mb?.groupValues?.get(2)?.toLongOrNull()
            if (done != null && total != null && total > 0) {
                return "$done / $total MB" to (done * 100 / total).toInt().coerceIn(0, 100)
            }
            val part = count.groupValues[2].toIntOrNull() ?: return null
            val all = count.groupValues[3].toIntOrNull() ?: return null
            return count.groupValues[1] to (part.toLong() * 100 / maxOf(1, all)).coerceIn(0L, 100L).toInt()
        }

        internal fun readableScriptStep(scriptStep: String): Boolean {
            val t = scriptStep.lowercase()
            return t.startsWith("download") || t.startsWith("checking the linux") || t.startsWith("unpacking") || t.startsWith("installing")
        }

        private const val TAIL_BYTES = 48L * 1024
        private val UPDATE_PROGRESS = Regex("""Downloading update \((\d+) of (\d+) KB\)""")
        private val INSTALL_COUNT = Regex("""downloading Steam: (\S+) \((\d+)/(\d+)\)""")
        private val INSTALL_MEGABYTES = Regex("""^, (\d+) of (\d+) MB""")
    }
}
