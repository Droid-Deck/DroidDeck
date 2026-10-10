package com.droiddeck.launcher.frontend

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.session.WinComponents

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

    /**
     * Works out [game]'s automatic components and keeps them: each one not yet in the runtime is
     * downloaded and installed first when [download], else left for the next time. Blocking
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
            // Before a launch only what is kept: no wait on the network.
            val catalog = (if (download) WinComponents.fetch(app) else WinComponents.cached(app))?.associateBy { it.name } ?: return
            val wanted = autoPicks(findings, catalog)
            val user = WinComponents.selection(app, key).user
            var installed = WinComponents.installedIds(app).toSet()
            val kept = LinkedHashMap<String, WinComponents.AutoReason>()
            for ((id, reason) in wanted) {
                if (user[id] == false) { kept[id] = reason; continue }
                if (id !in installed && download) {
                    val problem = WinComponents.install(app, catalog.getValue(id), catalog) {}
                    if (problem != null) { Log.w(TAG, "${game.name}: $id did not install: $problem"); continue }
                    installed = WinComponents.installedIds(app).toSet()
                }
                if (id in installed) kept[id] = reason
            }
            val before = WinComponents.selection(app, key).auto
            if (before.keys != kept.keys) {
                WinComponents.setAuto(app, key, kept)
                Log.i(TAG, "${game.name}: on by itself ${kept.keys.joinToString().ifEmpty { "(none)" }}")
            }
        } catch (e: Exception) {
            Log.w(TAG, "${game.name}: ${e.javaClass.simpleName}: ${e.message}")
        }
    }

    /** [refresh] with downloads, on a thread of its own: after an install or an add. */
    fun refreshLater(context: Context, game: Library.SteamGame) {
        val app = context.applicationContext
        Thread({ refresh(app, game, download = true) }, "auto-components").start()
    }
}
