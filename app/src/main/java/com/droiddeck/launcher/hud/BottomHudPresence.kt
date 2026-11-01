package com.droiddeck.launcher.hud

import android.os.SystemClock

/**
 * The bottom-screen HUD's heartbeat. The GPU load, clock and memory readers only work while a HUD is on screen
 * (GpuStatsComponent, GpuMemComponent); the bottom HUD and strips beat while they show those numbers, so the
 * readers keep going with the top-screen HUD off. A missed beat (page closed, app gone) stops them by itself.
 */
internal object BottomHudPresence {
    private const val STALE_MS = 2_500L

    @Volatile private var seenAt = 0L

    fun beat() {
        seenAt = SystemClock.elapsedRealtime()
    }

    val showing: Boolean get() = seenAt != 0L && SystemClock.elapsedRealtime() - seenAt < STALE_MS
}
