package com.droiddeck.launcher

import android.app.Application
import com.droiddeck.launcher.runtime.BwrapSpawner
import com.droiddeck.launcher.session.CrashHandler
import com.droiddeck.launcher.session.GpuClockPin

/**
 * Process-wide setup: the crash handler, so a session's folder is finished even when we die, and
 * the GPU clock pin cleared in case a killed process left it set, and the socket Flatpak's
 * sandboxes are started through (BwrapSpawner).
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        // Before anything else touches the session prefs, so the first session already runs in Deck mode.
        com.droiddeck.launcher.session.SessionPrefs.settleDeckModeDefault(this)
        com.droiddeck.launcher.session.SessionPrefs.settleGpuDriverMode(this)
        com.droiddeck.launcher.update.AppUpdates.init(this)
        GpuClockPin.clearLeftover(this)
        BwrapSpawner.start(this)
    }
}
