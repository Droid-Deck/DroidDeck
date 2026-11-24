package com.droiddeck.launcher.session

/** A launch the user asked for that waits for something else to finish first, and is forgotten if it waits too long. */
class QueuedLaunch<T : Any>(private val lifetimeMs: Long = 15 * 60_000L) {
    var launch: T? = null
        private set
    var queuedAt: Long = 0L
        private set

    fun queue(launch: T, now: Long) {
        this.launch = launch
        queuedAt = now
    }

    /** Takes back a saved launch with the time it was first queued; one without a valid time or past its lifetime is dropped. */
    fun restore(launch: T?, queuedAt: Long, now: Long) {
        if (launch != null && fresh(queuedAt, now)) {
            this.launch = launch
            this.queuedAt = queuedAt
        } else {
            clear()
        }
    }

    /** The launch if it is still wanted; an expired one is cleared. */
    fun current(now: Long): T? {
        val queued = launch ?: return null
        if (fresh(queuedAt, now)) return queued
        clear()
        return null
    }

    fun clear() {
        launch = null
    }

    private fun fresh(queuedAt: Long, now: Long) = now - queuedAt in 0..lifetimeMs
}
