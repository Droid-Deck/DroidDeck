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
        com.droiddeck.launcher.ui.Themes.loadGradient(this)
        // Helper processes must not initialize the main USB provider or guest graphics context.
        val process = if (android.os.Build.VERSION.SDK_INT >= 28) Application.getProcessName()
            else (getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager)
                .runningAppProcesses?.firstOrNull { it.pid == android.os.Process.myPid() }?.processName
        if (process == "$packageName:xboxlogin") {
            if (android.os.Build.VERSION.SDK_INT >= 28) android.webkit.WebView.setDataDirectorySuffix("xbox")
            return
        }
        if (process == "$packageName:vr_recovery" || process == "$packageName:rgb_diagnostic") return
        CrashHandler.install(this)
        com.droiddeck.launcher.session.QuestExperimental.initialize(this)
        com.droiddeck.launcher.session.SessionPrefs.settleGpuDriverMode(this)
        com.droiddeck.launcher.update.AppUpdates.init(this)
        com.droiddeck.launcher.core.DownloadSource.init(this)
        prefetchAddonPacks()
        GpuClockPin.clearLeftover(this)
        com.droiddeck.launcher.glasses.Glasses.init(this)
        com.droiddeck.launcher.glasses.GlassesMenuMirror.initialize(this)
        BwrapSpawner.start(this)
        com.droiddeck.launcher.frontend.GameFileSync.start(this)
        com.droiddeck.launcher.stores.StoresState.init(this)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.gog.GogBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.epic.EpicBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.amazon.AmazonBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.itch.ItchBackend)
        com.droiddeck.launcher.stores.StoresState.register(com.droiddeck.launcher.stores.xbox.XboxBackend)
    }

    /** Fetches the packs an existing setup already relies on, so the first use after an update does not wait. */
    private fun prefetchAddonPacks() {
        val app = this
        Thread({
            runCatching {
                if (com.droiddeck.launcher.runtime.LinuxRuntime.rootDir(app).isDirectory) {
                    com.droiddeck.launcher.core.AddonPack.PROOT.ensure(app)
                    com.droiddeck.launcher.core.AddonPack.DIRECTAUDIO.ensure(app)
                }
                if (com.droiddeck.launcher.stores.gog.GogComet.enabled(app)) com.droiddeck.launcher.core.AddonPack.COMET.ensure(app)
                if (com.droiddeck.launcher.stores.StoreAccounts.signedInAs(app, com.droiddeck.launcher.stores.Store.XBOX) != null)
                    com.droiddeck.launcher.core.AddonPack.XBOX.ensure(app)
                com.droiddeck.launcher.handtracking.HandTracking.load(app)
            }.onFailure { android.util.Log.w("App", "add-on pack prefetch failed", it) }
        }, "addon-packs").start()
    }
}
