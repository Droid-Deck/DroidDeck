package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.LibraryCache
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

/**
 * What happens around a store install so the rest of the app sees the game: the sidecar is
 * written, the launcher .bat beside it when the game needs one, and Steam is told.
 *
 * Telling Steam is the same path an added folder takes: the game is one more folder the
 * [AddedGames] scan finds, the scan's listing (`session/added-games.json`) is what the runtime's
 * shortcuts writer reads at the client's next start, and here the listing is rewritten at once so
 * a Steam client that restarts inside the running session already has it. When the client is up,
 * [SteamLiveShortcuts] adds the shortcut over the client's DevTools port as well, so the game shows
 * without a restart; that part is best-effort.
 */
object StoreInstalls {
    private const val TAG = "StoreInstalls"

    /**
     * Finishes an install: writes the sidecar (and the launcher), then registers the game. Runs on
     * the caller's worker thread.
     */
    fun complete(context: Context, folder: File, sidecar: StoreGameSidecar) {
        val app = context.applicationContext
        val withLauncher = StoreLaunch.writeLauncher(folder, sidecar)
        withLauncher.copy(addToSteam = sidecar.addToSteam && SessionPrefs.gameStoresAddToSteam(app)).write(folder)
        register(app)
    }

    /** Rewrites the session's listing from a fresh scan and, with a client running, adds live. */
    fun register(context: Context) {
        val app = context.applicationContext
        try {
            val games = AddedGames.scan(app)
            AddedGames.writeListing(app, games)
            LibraryCache.save(app, Library.launchableGames(app))
            SteamLiveShortcuts.sync(app, games)
        } catch (e: Exception) {
            Log.w(TAG, "registration: ${e.message}")
        }
    }

    /** Flips whether the game is a Steam shortcut; the listing follows. Off the main thread. */
    fun setAddToSteam(context: Context, game: InstalledStoreGame, on: Boolean, onDone: () -> Unit) {
        val app = context.applicationContext
        Thread({
            game.sidecar.copy(addToSteam = on).write(game.folder)
            register(app)
            StoresState.logLine("${if (on) "added" else "removed"} Steam shortcut for \"${game.sidecar.title}\"")
            StoresState.post(onDone)
        }, "stores-steam-toggle").start()
    }

    /**
     * Removes a store game: its folder with the sidecar, then the shortcut (the listing without
     * it makes the writer drop the entry; the live client is asked too). Blocking.
     */
    fun uninstall(context: Context, game: InstalledStoreGame) {
        val app = context.applicationContext
        val appId = AddedGames.scan(app).firstOrNull { it.folder.absolutePath == game.folder.absolutePath }?.appId
        deleteTree(game.folder)
        register(app)
        if (appId != null) SteamLiveShortcuts.remove(app, appId)
        StoresState.logLine("uninstalled \"${game.sidecar.title}\"")
    }

    fun deleteTree(dir: File) {
        dir.listFiles()?.forEach { if (it.isDirectory) deleteTree(it) else it.delete() }
        dir.delete()
    }
}
