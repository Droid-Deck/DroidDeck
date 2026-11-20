package com.droiddeck.launcher.session

// Tracks a pause that skipped the session's departure cleanup for the keep-top focus bounce.
internal class LightPause {
    private var pending = false
    private var bouncesAtPause = 0

    fun paused(bounces: Int) {
        pending = true
        bouncesAtPause = bounces
    }

    // The bounce resumes the session within tens of milliseconds; still paused at the timeout means
    // something else holds the pause.
    fun timedOut(): Boolean = take()

    fun stopped(): Boolean = take()

    fun resumed(bounces: Int): Resume {
        if (!pending) return Resume.NORMAL
        pending = false
        return if (bounces != bouncesAtPause) Resume.LIGHT else Resume.CLEANUP_FIRST
    }

    private fun take(): Boolean {
        val was = pending
        pending = false
        return was
    }

    enum class Resume { NORMAL, LIGHT, CLEANUP_FIRST }
}
