package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import org.json.JSONArray
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

object AddedGamesPublisher {
    private const val TAG = "AddedGamesPublisher"

    @VisibleForTesting
    @Volatile
    internal var now = false

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "added-games-publish") }
    private val retries = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "added-games-publish-retry") }

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
                val known = runCatching {
                    val listing = JSONArray(before)
                    List(listing.length()) { listing.getJSONObject(it).optString("exe") }.toSet()
                }.getOrDefault(emptySet())
                val games = AddedGames.scan(app)
                AddedGames.writeListing(app, games)
                val arch = com.droiddeck.launcher.session.SessionPrefs.activeSteamClientArch(app)
                LibraryCache.save(app, Library.launchableGames(app, added = games, arch = arch), arch == "x86_64")
                val fresh = games.filter { it.guestExe !in known }
                if (fresh.isEmpty()) return@execute
                Log.i(TAG, "session refresh: ${fresh.size} game(s) not in the listing the session started with")
                val attempts = java.util.concurrent.atomic.AtomicInteger()
                val retry = retries.scheduleWithFixedDelay({
                    try {
                        if (com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(app, fresh) { true } ||
                            !com.droiddeck.launcher.session.SessionState.running || attempts.incrementAndGet() >= 36) {
                            throw java.util.concurrent.CancellationException()
                        }
                    } catch (e: java.util.concurrent.CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "session shortcut retry: ${e.message}")
                    }
                }, 5, 5, TimeUnit.SECONDS)
                retries.schedule({ retry.cancel(false) }, 180, TimeUnit.SECONDS)
            } catch (e: Exception) {
                Log.w(TAG, "session refresh: ${e.message}")
            }
        }
    }

    fun republish(context: Context) {
        val games = AddedGames.scan(context)
        AddedGames.writeListing(context, games)
        val arch = com.droiddeck.launcher.session.SessionPrefs.activeSteamClientArch(context)
        LibraryCache.save(context, Library.launchableGames(context, added = games, arch = arch), arch == "x86_64")
    }
}
