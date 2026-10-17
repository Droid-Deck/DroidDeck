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

    fun refreshDuringSession(context: Context) {
        val app = context.applicationContext
        worker.execute {
            try {
                val before = AddedGames.listingFile(app).takeIf { it.isFile }?.readText().orEmpty()
                val games = AddedGames.scan(app)
                AddedGames.writeListing(app, games)
                LibraryCache.save(app, Library.launchableGames(app, added = games))
                val fresh = games.filter { !before.contains(org.json.JSONObject.quote(it.guestExe)) }
                if (fresh.isEmpty()) return@execute
                Log.i(TAG, "session refresh: ${fresh.size} game(s) not in the listing the session started with")
                for (attempt in 0 until 36) {
                    if (com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(app, fresh) { true }) break
                    if (!com.droiddeck.launcher.session.SessionState.running) break
                    Thread.sleep(5_000)
                }
            } catch (e: Exception) {
                Log.w(TAG, "session refresh: ${e.message}")
            }
        }
    }

    fun republish(context: Context) {
        val games = AddedGames.scan(context)
        AddedGames.writeListing(context, games)
        LibraryCache.save(context, Library.launchableGames(context, added = games))
    }
}
