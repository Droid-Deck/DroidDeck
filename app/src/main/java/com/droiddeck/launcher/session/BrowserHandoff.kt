package com.droiddeck.launcher.session

internal class BrowserHandoff(private val timeoutMs: Long) {
    private var startedAt = -1L
    private var sessionLeft = false

    fun begin(now: Long) {
        startedAt = now
        sessionLeft = false
    }

    fun sessionHidden() {
        if (startedAt >= 0) sessionLeft = true
    }

    fun sessionShown() {
        if (sessionLeft) clear()
    }

    fun clear() {
        startedAt = -1L
        sessionLeft = false
    }

    fun active(now: Long): Boolean = startedAt >= 0 && now - startedAt < timeoutMs
}
