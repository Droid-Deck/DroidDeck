package com.droiddeck.launcher.input

internal class AxisPacing(private val intervalMs: Long) {
    private var lastWriteAt = 0L
    private var written = false
    private var heldUntil = NOT_HELD

    fun dueAt(now: Long, axesOnly: Boolean): Long =
        if (axesOnly && written && now - lastWriteAt < intervalMs) lastWriteAt + intervalMs else now

    fun wrote(at: Long) {
        lastWriteAt = at
        written = true
    }

    fun hold(due: Long): Boolean {
        if (heldUntil != NOT_HELD) return false
        heldUntil = due
        return true
    }

    fun release(now: Long): Boolean {
        if (heldUntil == NOT_HELD || now < heldUntil) return false
        heldUntil = NOT_HELD
        return true
    }

    fun cancel(): Boolean {
        if (heldUntil == NOT_HELD) return false
        heldUntil = NOT_HELD
        return true
    }

    companion object {
        private const val NOT_HELD = Long.MIN_VALUE

        @JvmStatic fun keepsMoving(before: Int, after: Int): Boolean = before == after || (before != 0 && after != 0)
    }
}
