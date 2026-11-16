package com.droiddeck.launcher.frontend

import android.content.Context
import android.os.SystemClock
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionState
import java.io.File
import java.util.concurrent.Executors

object AddedGamesPublisher {
    private const val TAG = "AddedGamesPublisher"
    private const val CLIENT_WAIT_MS = 600_000L
    private const val POLL_MS = 1_000L

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

    fun republish(context: Context): List<AddedGames.Game> {
        val games = AddedGames.scan(context)
        AddedGames.writeListing(context, games)
        val arch = com.droiddeck.launcher.session.SessionPrefs.activeSteamClientArch(context)
        LibraryCache.save(context, Library.launchableGames(context, added = games, arch = arch), arch == "x86_64")
        return games
    }

    fun sessionListing(context: Context): File {
        val app = context.applicationContext
        val started = AddedGames.listed(app)?.map { it.appId }?.toSet()
            ?: return AddedGames.writeListing(app, AddedGames.scan(app).also { log(it) })
        val session = SessionState.sessionId
        val work = Runnable {
            try {
                val began = SystemClock.elapsedRealtime()
                val games = republish(app)
                Log.i(TAG, "session refresh: ${games.size} game(s) in ${SystemClock.elapsedRealtime() - began} ms")
                log(games)
                val fresh = games.filter { (it.appId and 0xFFFFFFFFL) !in started }
                if (fresh.isNotEmpty() && clientReady(session)) com.droiddeck.launcher.stores.SteamLiveShortcuts.sync(app, fresh) { true }
            } catch (e: Exception) {
                Log.w(TAG, "session refresh: ${e.message}")
            }
        }
        if (now) work.run() else Thread(work, "added-games-refresh").apply { priority = Thread.MIN_PRIORITY }.start()
        return AddedGames.listingFile(app)
    }

    private fun log(games: List<AddedGames.Game>) {
        if (games.isNotEmpty()) Log.i(TAG, "added games: " + games.joinToString { "${it.name} (${it.exe.name})" })
    }

    private fun clientReady(session: String?): Boolean {
        val until = SystemClock.uptimeMillis() + CLIENT_WAIT_MS
        fun same() = SessionState.running && !SessionState.stopRequested && SessionState.sessionId == session
        while (!now && same() && SessionState.phase != SessionPhase.READY && SystemClock.uptimeMillis() < until) Thread.sleep(POLL_MS)
        return same() && SessionState.phase == SessionPhase.READY
    }
}
