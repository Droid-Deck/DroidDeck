package com.droiddeck.launcher.glasses

internal class SteamVrSpatialGate(private val settleMs: Long = 2_000) {
    private var waiting = false
    private var running = false
    var deadline: Long? = null
        private set

    fun prepare(): Boolean {
        if (running || waiting) return false
        waiting = true
        deadline = null
        return true
    }

    fun setRunning(on: Boolean, now: Long): Boolean {
        if (running == on) return false
        running = on
        waiting = false
        deadline = if (on) now + settleMs else null
        return true
    }

    fun cancel() {
        waiting = false
        running = false
        deadline = null
    }

    fun spatialEnabled(requested: Boolean, now: Long) =
        requested && !waiting && (deadline?.let { now >= it } ?: true)
}
