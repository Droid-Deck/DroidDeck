package com.droiddeck.launcher.core

import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStreamWriter

class CappedLogWriter @JvmOverloads constructor(
    private val file: File,
    append: Boolean,
    private val limitBytes: Long = SESSION_LOG_LIMIT_BYTES,
    private val tailLines: Int = TAIL_LINES,
) : Closeable {
    private val out = BufferedWriter(OutputStreamWriter(FileOutputStream(file, append), Charsets.UTF_8))
    private val tail = ArrayDeque<String>()
    private var dropped = 0L
    private var closed = false

    @Synchronized
    fun line(text: String): Boolean {
        if (closed) return false
        if (dropped == 0L && file.length() + text.toByteArray(Charsets.UTF_8).size + 1 <= limitBytes) {
            runCatching { out.write(text); out.newLine(); out.flush() }
            return true
        }
        if (dropped == 0L) {
            runCatching {
                out.write("== this log reached its ${limitBytes / (1024 * 1024)} MB limit; later lines are dropped and only the last $tailLines are kept at the end")
                out.newLine()
                out.flush()
            }
        }
        dropped++
        tail.addLast(text)
        if (tail.size > tailLines) tail.removeFirst()
        return false
    }

    @Synchronized
    override fun close() {
        if (closed) return
        closed = true
        runCatching {
            if (dropped > 0) {
                out.write("== ${dropped - tail.size} lines dropped after the limit; the last ${tail.size} follow")
                out.newLine()
                tail.forEach { out.write(it); out.newLine() }
            }
            out.flush()
        }
        runCatching { out.close() }
    }

    companion object {
        const val SESSION_LOG_LIMIT_BYTES = 64L * 1024 * 1024
        const val TAIL_LINES = 400
    }
}
