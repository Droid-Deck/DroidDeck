package com.droiddeck.launcher

import android.app.Application
import com.droiddeck.launcher.session.CrashHandler
import com.droiddeck.launcher.session.GpuClockPin

/**
 * Process-wide setup: the crash handler, so a session's folder is finished even when we die, and
 * the GPU clock pin cleared in case a killed process left it set.
 */
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        GpuClockPin.clearLeftover(this)
    }
}
