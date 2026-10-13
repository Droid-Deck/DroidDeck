package com.droiddeck.launcher.session

import android.util.Log
import java.io.File
import java.io.RandomAccessFile

internal object SteamSessionDiagnostics {
    private const val TAG = "SteamSessionDiagnostics"
    private const val TAIL_BYTES = 512 * 1024
    private const val MAX_LOG_BYTES = 8L * 1024 * 1024

    fun hasSessionReplaced(logDirectory: File): Boolean {
        if (!logDirectory.isDirectory) return false
        val logs = logDirectory.listFiles { file -> file.isFile && file.length() <= MAX_LOG_BYTES }
        if (logs == null) {
            Log.w(TAG, "could not list ${logDirectory.path}")
            return false
        }
        return logs.any { file ->
                runCatching {
                    RandomAccessFile(file, "r").use { input ->
                        val start = maxOf(0L, input.length() - TAIL_BYTES)
                        input.seek(start)
                        val bytes = ByteArray((input.length() - start).toInt())
                        input.readFully(bytes)
                        containsSessionReplaced(String(bytes, Charsets.ISO_8859_1).lineSequence())
                    }
                }.onFailure { Log.w(TAG, "could not inspect ${file.name}", it) }.getOrDefault(false)
            }
    }

    internal fun containsSessionReplaced(lines: Sequence<String>): Boolean =
        lines.any { SESSION_REPLACED.containsMatchIn(it) }

    private val SESSION_REPLACED = Regex("""\bsession[\s_-]+replaced\b""", RegexOption.IGNORE_CASE)
}
