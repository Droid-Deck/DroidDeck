package com.droiddeck.launcher.session

import android.content.ComponentCallbacks2

internal object TopUnderPressure {
    const val WINDOW_MS = 5_000L

    fun isPressure(level: Int) =
        level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL || level == ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW

    fun shouldReclaim(nowMs: Long, pressureAtMs: Long, sessionRunning: Boolean, finishing: Boolean) =
        sessionRunning && !finishing && pressureAtMs > 0 && nowMs - pressureAtMs in 0..WINDOW_MS
}
