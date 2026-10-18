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
        // Helper processes must not initialize the main USB provider or guest graphics context.
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (process == "$packageName:vr_recovery" || process == "$packageName:rgb_diagnostic") return
        CrashHandler.install(this)
        com.droiddeck.launcher.session.QuestExperimental.initialize(this)
        com.droiddeck.launcher.session.SessionPrefs.settleGpuDriverMode(this)
        com.droiddeck.launcher.update.AppUpdates.init(this)
        com.droiddeck.launcher.core.DownloadSource.init(this)
        GpuClockPin.clearLeftover(this)
        com.droiddeck.launcher.glasses.Glasses.init(this)
        com.droiddeck.launcher.glasses.GlassesMenuMirror.initialize(this)
        BwrapSpawner.start(this)
        com.droiddeck.launcher.frontend.GameFileSync.start(this)
        com.droiddeck.launcher.stores.StoresState.init(this)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.gog.GogBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.epic.EpicBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.amazon.AmazonBackend)
    }
}
