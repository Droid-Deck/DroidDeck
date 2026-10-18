package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import java.util.concurrent.Executors

object AddedGamesPublisher {
    private const val TAG = "AddedGamesPublisher"

    @VisibleForTesting
    @Volatile
    internal var now = false

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "added-games-publish") }

    fun later(context: Context, live: () -> Unit = {}) {
        val app = context.applicationContext
        val work = Runnable {
            try { live() } catch (e: Exception) { Log.w(TAG, "live: ${e.message}") }
            try { republish(app) } catch (e: Exception) { Log.w(TAG, "listing: ${e.message}") }
        }
        if (now) work.run() else worker.execute(work)
    }

    fun republish(context: Context) {
        val games = AddedGames.scan(context)
        AddedGames.writeListing(context, games)
        val arch = com.droiddeck.launcher.session.SessionPrefs.activeSteamClientArch(context)
        LibraryCache.save(context, Library.launchableGames(context, added = games, arch = arch), arch == "x86_64")
    }
}
