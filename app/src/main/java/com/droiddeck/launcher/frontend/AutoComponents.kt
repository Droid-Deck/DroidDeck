package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import androidx.annotation.VisibleForTesting
import com.droiddeck.launcher.session.WinComponents
import kotlinx.coroutines.flow.MutableStateFlow
import java.util.concurrent.Executors

/**
 * Turns on, per game, the Windows components its source's list names ([WinCompSources]): when a
 * game is installed or added, and again before it is launched from DroidDeck. Only the findings
 * marked auto are turned on, only the ones that install here, and never one the user switched
 * off: the user's switches are kept apart ([WinComponents.Selection.user]) and win either way.
 */
object AutoComponents {
    private const val TAG = "AutoComponents"

    /** The key a game's picks are kept under: the appid its prefix is named after. */
    fun appKey(game: Library.SteamGame): String? =
        if (game.library == Library.ADDED) Integer.toUnsignedString(game.appId) else game.appId.takeIf { it > 0 }?.toString()

    /**
     * What turns on by itself for [findings], given [catalog] and what is [installed]: the catalog
     * name each auto finding installs as, with its reason. A component that needs a Windows
     * installer, or is not in the catalog, stays out (the editor lists it as a suggestion).
     */
    fun autoPicks(findings: List<WinCompSources.Finding>, catalog: Map<String, WinComponents.Component>): Map<String, WinComponents.AutoReason> {
        val out = LinkedHashMap<String, WinComponents.AutoReason>()
        for (f in findings) {
            if (!f.auto || f.component == null) continue
            val id = WinComponents.installable(f.component, catalog)
            val c = catalog[id] ?: continue
            if (WinComponents.support(c, catalog) != WinComponents.Support.READY) continue
            if (id !in out) out[id] = WinComponents.AutoReason(f.origin.name, listOf(f.original) + f.args)
        }
        return out
    }

    // ---- seams for tests ----
    @VisibleForTesting internal var catalogOf: (Context, Boolean) -> List<WinComponents.Component>? =
        { c, network -> if (network) WinComponents.fetch(c) else WinComponents.cached(c) }
    @VisibleForTesting internal var installedOf: (Context) -> Set<String> = { WinComponents.installedIds(it).toSet() }
    @VisibleForTesting internal var installer: (Context, WinComponents.Component, Map<String, WinComponents.Component>, (WinComponents.Progress) -> Unit) -> String? =
        { c, comp, all, progress -> WinComponents.install(c, comp, all, progress) }
    /** Tests run the queued downloads at once, on the caller's thread. */
    @VisibleForTesting @Volatile internal var runNow = false

    /** Bumped whenever a game's automatic components change, so an open page reads them again. */
    val changes = MutableStateFlow(0)

    /** The components downloading for an automatic pick right now, with their progress. */
    val downloading = MutableStateFlow<Map<String, WinComponents.Progress>>(emptyMap())

    private val queue = Executors.newSingleThreadExecutor { r -> Thread(r, "auto-components") }
    private val queued = java.util.Collections.synchronizedSet(HashSet<String>())

    /**
     * Works out [game]'s automatic components and keeps them. Each one already in the runtime is on
     * at once; each one not yet downloaded is downloaded here when [download], else queued
     * ([queueDownloads]) and turned on when it lands - never one the user switched off. Blocking
     * (network when downloading); never throws.
     */
    fun refresh(context: Context, game: Library.SteamGame, download: Boolean) {
        val app = context.applicationContext
        try {
            val key = appKey(game) ?: return
            // A game added in DroidDeck: whether its files name a Steam app, read now (the store
            // search by name is the art fetch's, which keeps its answer in the same record).
            val folder = game.gameFiles
            if (download && game.library == Library.ADDED && game.source == Library.ADDED && folder != null) {
                AddedGames.single(app, folder.path)?.let { SteamMatch.resolve(app, folder.path, it.exe, it.name) }
            }
            val findings = WinCompSources.forGame(app, game)
            // Without downloads, only the catalog kept: no wait on the network.
            val catalog = catalogOf(app, download)?.associateBy { it.name }
            // No catalog kept yet: the queued run fetches it.
            if (catalog == null) { if (!download) queueDownloads(app, game); return }
            val wanted = autoPicks(findings, catalog)
            val user = WinComponents.selection(app, key).user
            var installed = installedOf(app)
            val kept = LinkedHashMap<String, WinComponents.AutoReason>()
            var missing = false
            for ((id, reason) in wanted) {
                if (user[id] == false) { kept[id] = reason; continue }
                if (id !in installed && download) {
                    downloading.value = downloading.value + (id to WinComponents.Progress(id, "", WinComponents.Phase.DOWNLOAD, -1))
                    val problem = try {
                        installer(app, catalog.getValue(id), catalog) { p -> downloading.value = downloading.value + (id to p) }
                    } finally {
                        downloading.value = downloading.value - id
                    }
                    if (problem != null) { Log.w(TAG, "${game.name}: $id did not install: $problem"); continue }
                    installed = installedOf(app)
                }
                if (id in installed) kept[id] = reason else missing = true
            }
            val before = WinComponents.selection(app, key).auto
            if (before.keys != kept.keys) {
                WinComponents.setAuto(app, key, kept)
                Log.i(TAG, "${game.name}: on by itself ${kept.keys.joinToString().ifEmpty { "(none)" }}")
                changes.value = changes.value + 1
            }
            if (missing) queueDownloads(app, game)
        } catch (e: Exception) {
            Log.w(TAG, "${game.name}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /**
     * [game]'s automatic components that are not downloaded yet, downloaded in the background, one
     * game at a time and each game once in the queue; each turns on when it lands.
     */
    fun queueDownloads(context: Context, game: Library.SteamGame) {
        val app = context.applicationContext
        val key = appKey(game) ?: return
        if (!queued.add(key)) return
        val work = Runnable {
            try { refresh(app, game, download = true) } finally { queued.remove(key) }
        }
        if (runNow) work.run() else queue.execute(work)
    }

    /** [refresh] with downloads, in the background queue: after an install or an add. */
    fun refreshLater(context: Context, game: Library.SteamGame) = queueDownloads(context, game)

    /**
     * Every Steam game's automatic components, once at app start: Steam installs its games with no
     * word to DroidDeck, so what their lists name is downloaded here. Background only.
     */
    fun sweep(context: Context, games: List<Library.SteamGame>) {
        val app = context.applicationContext
        Thread({ games.filter { it.library != Library.ADDED }.forEach { refresh(app, it, download = false) } }, "auto-components-sweep").start()
    }
}
