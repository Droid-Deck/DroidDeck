package com.droiddeck.launcher

import android.app.Application
import com.droiddeck.launcher.runtime.BwrapSpawner
import com.droiddeck.launcher.session.CrashHandler
import com.droiddeck.launcher.session.GpuClockPin

class App : Application() {
    override fun attachBaseContext(base: android.content.Context) =
        super.attachBaseContext(com.droiddeck.launcher.core.AppLanguage.wrap(base))

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        com.droiddeck.launcher.core.AppLanguage.systemChanged(newConfig)
    }

    override fun onCreate() {
        super.onCreate()
        CrashHandler.install(this)
        com.droiddeck.launcher.session.QuestExperimental.initialize(this)
        com.droiddeck.launcher.session.SessionPrefs.settleGpuDriverMode(this)
        com.droiddeck.launcher.update.AppUpdates.init(this)
        GpuClockPin.clearLeftover(this)
        BwrapSpawner.start(this)
        com.droiddeck.launcher.frontend.GameFileSync.start(this)
        com.droiddeck.launcher.stores.StoresState.init(this)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.gog.GogBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.epic.EpicBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.amazon.AmazonBackend)
    }
}
