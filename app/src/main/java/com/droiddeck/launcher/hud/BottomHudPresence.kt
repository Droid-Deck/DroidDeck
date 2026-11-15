package com.droiddeck.launcher.hud

import android.os.SystemClock

internal object BottomHudPresence {
    private const val STALE_MS = 2_500L

    @Volatile private var seenAt = 0L

    fun beat() {
        seenAt = SystemClock.elapsedRealtime()
    }

    val showing: Boolean get() = seenAt != 0L && SystemClock.elapsedRealtime() - seenAt < STALE_MS
}
