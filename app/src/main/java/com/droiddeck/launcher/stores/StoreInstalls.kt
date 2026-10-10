package com.droiddeck.launcher.stores

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.frontend.AddedGames
import com.droiddeck.launcher.frontend.LibraryCache
import com.droiddeck.launcher.frontend.Library
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
     * Marks [folder] as an install under way before anything is fetched: a sidecar in state
     * `installing` with the store, id and title. Whatever stops the install from here on - a
     * failure, a cancel, the process killed - leaves a folder the scan skips and the Stores page
     * offers to resume, never a half-written Custom game. A finished install being repaired or
     * updated keeps its finished sidecar, so the game stays in Steam meanwhile.
     */
    fun begin(folder: File, store: Store, id: String, title: String, cover: String?, hero: String?) {
        if (StoreGameSidecar.read(folder)?.isInstalled == true) return
        StoreGameSidecar(store, id, title, exe = "", cover = cover, hero = hero, state = StoreGameSidecar.STATE_INSTALLING).write(folder)
    }

    /**
     * Finishes an install: writes the launcher and the finished sidecar, then the art, then
     * registers the game. Runs on the caller's worker thread. The launcher and sidecar writes
     * throw, so a failure there fails the download with its message; the art and the Steam side
     * are best-effort and only logged.
     */
    fun complete(context: Context, folder: File, sidecar: StoreGameSidecar, onStep: ((Int, Int) -> Unit)? = null) {
        val app = context.applicationContext
        onStep?.invoke(0, STEPS)
        // The user's launch choices survive an update or a repair.
        val before = StoreGameSidecar.read(folder)
        val finished = StoreLaunch.writeLauncher(folder, sidecar.copy(state = StoreGameSidecar.STATE_INSTALLED, epic = before?.epic ?: sidecar.epic, cloud = before?.cloud ?: sidecar.cloud))
        finished.write(folder)
        if (StoreGameSidecar.read(folder)?.isInstalled != true) throw java.io.IOException("the install record could not be written in ${folder.name}")
        onStep?.invoke(1, STEPS)
        // The art before the listing, so the listing already carries it for the client's grid.
        try { StoreArt.fetchInto(folder, finished) } catch (e: Exception) { Log.w(TAG, "art for ${folder.name}: ${e.message}") }
        onStep?.invoke(2, STEPS)
        register(app)
        // Its Windows components from the store's list (GOG's dependencies, Epic's prerequisite,
        // Amazon's install steps), on a thread of their own.
        AddedGames.single(app, folder.path)?.let { com.droiddeck.launcher.frontend.AutoComponents.refreshLater(app, Library.addedGame(app, it)) }
        onStep?.invoke(STEPS, STEPS)
    }

    /** The finishing steps [complete] reports: the record and launcher, the art, Steam. */
    private const val STEPS = 3

    /** Rewrites the session's listing from a fresh scan and, with a client running, adds live. */
    fun register(context: Context) {
        val app = context.applicationContext
        try {
            val games = AddedGames.scan(app)
            AddedGames.writeListing(app, games)
            LibraryCache.save(app, Library.launchableGames(app, added = games))
            SteamLiveShortcuts.sync(app, games)
        } catch (e: Exception) {
            Log.w(TAG, "registration: ${e.message}")
        }
    }

    /**
     * Removes a store game: its folder with the sidecar, then the shortcut (the listing without
     * it makes the writer drop the entry; the live client is asked too). Blocking.
     */
    fun uninstall(context: Context, game: InstalledStoreGame) {
        val app = context.applicationContext
        val appId = AddedGames.scan(app).firstOrNull { it.folder.absolutePath == game.folder.absolutePath }?.appId
        deleteTree(game.folder)
        // The scratch cache an install on a card may have left in the app's cache.
        deleteTree(StoreInstallRoot.scratchDir(app, game.sidecar.store, game.sidecar.id))
        register(app)
        if (appId != null) SteamLiveShortcuts.remove(app, appId)
        StoresState.logLine("uninstalled \"${game.sidecar.title}\"")
    }

    /**
     * What Cancel does: everything the install wrote goes - the game folder with its sidecar and
     * the caches in [caches] - unless the folder holds a finished install being repaired or updated;
     * then only the caches go and the working game stays. Off the caller's thread; the library
     * follows when it is done, so the game reads Install again.
     */
    fun discard(context: Context, folder: File, caches: List<File>) {
        val app = context.applicationContext
        Thread({
            val keep = StoreGameSidecar.read(folder)?.isInstalled == true
            caches.forEach { deleteTree(it) }
            if (!keep) deleteTree(folder)
            StoresState.logLine(if (keep) "removed the partial update of ${folder.name}" else "removed ${folder.name}")
            StoresState.notifyLibraryChanged(app)
        }, "store-cancel-clean").start()
    }

    fun deleteTree(dir: File) {
        dir.listFiles()?.forEach { if (it.isDirectory) deleteTree(it) else it.delete() }
        dir.delete()
    }
}
