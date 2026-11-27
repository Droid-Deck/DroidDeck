package com.droiddeck.launcher

import android.os.Bundle
import android.os.SystemClock

/**
 * State a restarting launcher passes to its replacement inside this process. Intent extras are not used
 * because FrontEndActivity is exported and any app could fill them.
 */
internal object LauncherHandoff {
    private const val LIFETIME_MS = 10_000L
    private var state: Bundle? = null
    private var at = 0L

    @Synchronized
    fun put(carried: Bundle) {
        state = carried
        at = SystemClock.elapsedRealtime()
    }

    @Synchronized
    fun take(): Bundle? {
        val carried = state
        state = null
        return carried?.takeIf { SystemClock.elapsedRealtime() - at <= LIFETIME_MS }
    }
}
