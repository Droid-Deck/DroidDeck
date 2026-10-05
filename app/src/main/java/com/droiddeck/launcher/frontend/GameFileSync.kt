package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** Runs while DroidDeck's process is alive, including downloads in a background Steam session. */
object GameFileSync {
    private val worker = Executors.newSingleThreadScheduledExecutor { task -> Thread(task, "game-file-sync").apply { isDaemon = true } }
    private fun prefs(context: Context) = context.getSharedPreferences("game_files", Context.MODE_PRIVATE)
    fun folder(context: Context): String? = prefs(context).getString("folder", null)

    fun start(context: Context) {
        val app = context.applicationContext
        worker.scheduleWithFixedDelay({
            runCatching { syncConfigured(app) }.onFailure { Log.w("GameFileSync", "Sync failed: ${it.message}") }
        }, 0, 30, TimeUnit.SECONDS)
    }

    @Synchronized internal fun syncConfigured(context: Context) {
        val path = folder(context) ?: return
        sync(context, File(path))
    }

    @Synchronized fun enable(context: Context, folder: File) {
        sync(context, folder)
        prefs(context).edit().putString("folder", folder.absolutePath).apply()
    }

    @Synchronized fun disable(context: Context) {
        prefs(context).edit().remove("folder").apply()
    }

    @Synchronized private fun sync(context: Context, folder: File) {
        check(LinuxRuntime.isInstalled(context)) { "Install the runtime first" }
        // A disconnected library must not be interpreted as a mass uninstall.
        check(File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/steamapps").isDirectory) { "Steam library is unavailable" }
        val storage = GameStorage.effective(context)
        val storageSetting = SessionPrefs.gameStorage(context)
        val previousStorage = prefs(context).getString("storagePath", null)
        if (storageSetting.isEmpty() && prefs(context).getString("storageSetting", null) == storageSetting && previousStorage != null) {
            check(File(previousStorage).isDirectory) { "Previous SD library is unavailable" }
        }
        storage?.let { check(File(it.path).listFiles() != null) { "Game storage is unavailable" } }
        SessionPrefs.addedGamesDirs(context).forEach { check(File(it).listFiles() != null) { "Added games folder is unavailable" } }
        GameFiles.sync(folder, Library.launchableGames(context, strictRead = true))
        prefs(context).edit().putString("storageSetting", storageSetting).putString("storagePath", storage?.path).apply()
    }
}
