package com.droiddeck.launcher.input

internal class AxisPacing(private val intervalMs: Long) {
    private var lastWriteAt = 0L
    private var written = false

    fun dueAt(now: Long, axesOnly: Boolean): Long =
        if (axesOnly && written && now - lastWriteAt < intervalMs) lastWriteAt + intervalMs else now

    fun wrote(at: Long) {
        lastWriteAt = at
        written = true
    }

    companion object {
        @JvmStatic fun keepsMoving(before: Int, after: Int): Boolean = before == after || (before != 0 && after != 0)
    }
}
