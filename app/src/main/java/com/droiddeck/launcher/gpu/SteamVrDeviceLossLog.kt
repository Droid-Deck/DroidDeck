package com.droiddeck.launcher.gpu

import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

/** Only new compositor output is evidence of a fault in the current session. */
internal class SteamVrDeviceLossLog(private val file: File) {
    private var identity: Any? = identity()
    private var position = file.length()
    private var prefix = runCatching {
        RandomAccessFile(file, "r").use { input -> ByteArray(minOf(64, input.length()).toInt()).also(input::readFully) }
    }.getOrDefault(byteArrayOf())
    private var partial = ""
    private var handled = false

    private fun identity(): Any? = runCatching {
        Files.readAttributes(file.toPath(), BasicFileAttributes::class.java).fileKey()
    }.getOrNull()

    fun poll(): Boolean {
        if (handled) return true
        if (!file.isFile) return false
        return runCatching {
            val current = identity()
            RandomAccessFile(file, "r").use { input ->
                val head = ByteArray(minOf(64, input.length()).toInt()).also(input::readFully)
                val replaced = prefix.isNotEmpty() && !head.take(prefix.size).toByteArray().contentEquals(prefix)
                if (current != identity || input.length() < position || replaced) {
                    position = 0
                    partial = ""
                }
                identity = current
                prefix = head
                // Fault loops can produce megabytes per second. Bound work and keep the newest output.
                if (input.length() - position > MAX_READ) {
                    position = input.length() - MAX_READ
                    partial = ""
                }
                input.seek(position)
                val bytes = ByteArray(minOf(MAX_READ, input.length() - position).toInt())
                input.readFully(bytes)
                position = input.filePointer
                val text = partial + bytes.toString(Charsets.UTF_8)
                val end = text.lastIndexOf('\n')
                partial = text.substring(end + 1).takeLast(2048)
                if (end >= 0 && isDeviceLoss(text.substring(0, end + 1))) handled = true
                handled
            }
        }.getOrDefault(false)
    }

    companion object {
        private const val MAX_READ = 128 * 1024L
        internal fun isDeviceLoss(text: String): Boolean =
            Regex("\\bVK_ERROR_DEVICE_LOST\\b|\\bvkerror\\s*=\\s*-4\\b", RegexOption.IGNORE_CASE).containsMatchIn(text)
    }
}

internal object SteamVrRecoveryPolicy {
    const val COOLDOWN_MS = 10 * 60 * 1000L
    const val STOP_MS = 15_000L
    const val LOGS_MS = 30_000L
    private const val POLL_MS = 250L

    enum class Shutdown { COMPLETE, LOGS_PENDING, STUCK }

    fun canRestartAutomatically(lastAttempt: Long, now: Long): Boolean =
        lastAttempt == 0L || (now >= lastAttempt && now - lastAttempt >= COOLDOWN_MS)

    fun awaitShutdown(session: () -> JSONObject?, now: () -> Long, sleep: (Long) -> Unit): Shutdown {
        fun await(ms: Long, done: (JSONObject) -> Boolean): Boolean {
            val deadline = now() + ms
            while (true) {
                if (session()?.let(done) == true) return true
                if (now() >= deadline) return false
                sleep(POLL_MS)
            }
        }
        if (!await(STOP_MS) { it.optString("phase") in setOf("IDLE", "FAILED") }) return Shutdown.STUCK
        return if (await(LOGS_MS) { !it.optBoolean("artifactsAvailable") || it.optBoolean("artifactsComplete") }) Shutdown.COMPLETE
            else Shutdown.LOGS_PENDING
    }
}
