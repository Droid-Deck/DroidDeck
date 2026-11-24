package com.droiddeck.launcher.session

/**
 * Starts a queued launch once nothing stands in its way and retries until it can or it expires. One runner belongs to one
 * activity: destroying it clears its queue and cancels its retry, so a restored activity is the only one that starts the launch.
 */
class QueuedLaunchRunner<T : Any>(
    private val queue: QueuedLaunch<T>,
    private val now: () -> Long,
    private val blocked: () -> Boolean,
    private val busy: () -> Boolean,
    private val schedule: (delayMs: Long, task: Runnable) -> Unit,
    private val unschedule: (Runnable) -> Unit,
    private val start: (T) -> Unit,
    private val retryMs: Long = 2_000L,
) {
    private var posted = false
    private var retry: Runnable? = null
    private var destroyed = false

    /** Call whenever the blocking setting may have changed. */
    fun poke() {
        if (destroyed || posted) return
        val launch = queue.current(now()) ?: return
        if (blocked()) return
        posted = true
        schedule(0L, Runnable {
            posted = false
            attempt(launch)
        })
    }

    private fun attempt(launch: T) {
        if (destroyed || queue.current(now()) !== launch) return
        if (busy()) {
            val again = Runnable { if (!destroyed && queue.launch === launch) poke() }
            retry = again
            schedule(retryMs, again)
            return
        }
        queue.clear()
        start(launch)
    }

    fun destroy() {
        destroyed = true
        queue.clear()
        retry?.let(unschedule)
        retry = null
    }
}
