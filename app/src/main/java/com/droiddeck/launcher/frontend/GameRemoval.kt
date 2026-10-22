package com.droiddeck.launcher.frontend

import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoresState
import java.io.File

object GameRemoval {
    enum class Kind { STORE, SHORTCUT, LAUNCHER }

    fun kind(game: Library.SteamGame): Kind = when {
        game.library == Library.EPIC_LAUNCHER -> Kind.LAUNCHER
        game.library == Library.ADDED && game.source == Library.ADDED -> Kind.SHORTCUT
        else -> Kind.STORE
    }

    class SteamFiles(val dir: File, val manifest: File, val partial: File?)

    fun steamFiles(game: Library.SteamGame): SteamFiles? {
        val dir = game.gameFiles ?: return null
        val common = dir.parentFile ?: return null
        if (common.name != "common") return null
        val steamapps = common.parentFile ?: return null
        if (steamapps.name != "steamapps") return null
        val manifest = File(steamapps, "appmanifest_${game.appId}.acf")
        if (!manifest.isFile) return null
        if (!declares(manifest, game.appId)) return null
        return SteamFiles(dir, manifest, File(steamapps, "downloading/${game.appId}"))
    }

    fun delete(target: SteamFiles): Boolean {
        StoreInstalls.deleteTree(target.dir)
        if (target.dir.exists()) return false
        target.partial?.let { StoreInstalls.deleteTree(it) }
        target.manifest.delete()
        return !target.manifest.exists()
    }

    fun storeGame(game: Library.SteamGame): InstalledStoreGame? {
        val folder = game.gameFiles ?: return null
        val store = Store.byId(game.source)
        val id = game.storeId
        if (store != null && id != null) StoresState.installedGame(store, id)?.let { return it }
        val sidecar = StoreGameSidecar.read(folder)?.takeIf { it.isInstalled } ?: return null
        return InstalledStoreGame(sidecar, folder)
    }

    private fun declares(manifest: File, appId: Int): Boolean =
        runCatching { manifest.readText() }.getOrNull()?.let { Regex("\"appid\"\\s+\"$appId\"").containsMatchIn(it) } == true
}
