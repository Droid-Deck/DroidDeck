package com.droiddeck.launcher.stores.steam

import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.frontend.SteamOwnedLibrary
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreLibraryCache
import com.droiddeck.launcher.stores.StoresState

object SteamBackend : StoreBackend {
    override val store: Store = Store.STEAM

    override fun signIn(context: Context) {
        SteamClientSignIn.open(context)
    }

    override fun signOut(context: Context) {
        val app = context.applicationContext
        SteamOwnedLibrary.clear(app)
        StoreLibraryCache.clear(app, store)
        StoresState.library.remove(store)
        StoresState.status.remove(store)
        StoresState.problems.remove(store)
        Handler(Looper.getMainLooper()).post {
            com.droiddeck.launcher.stores.WebCookies.expire(
                listOf("https://store.steampowered.com/", "https://steamcommunity.com/"),
                ".steampowered.com",
                listOf("/", "/login"),
            ) {}
            com.droiddeck.launcher.stores.WebCookies.expire(
                listOf("https://steamcommunity.com/"),
                ".steamcommunity.com",
                listOf("/", "/login"),
            ) {}
        }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        StoresState.post { StoresState.status[store] = app.getString(R.string.stores_sync_library) }
        SteamOwnedLibrary.syncAsync(app, force) { steamId, games ->
            if (steamId == null) return@syncAsync
            StoresState.status.remove(store)
            StoresState.problems.remove(store)
            StoresState.library[store] = games.map { game ->
                CatalogItem(
                    store = store,
                    id = game.appId.toString(),
                    title = game.name,
                    imageUrl = game.remoteHero,
                    tallImageUrl = game.remoteArt,
                    storeUrl = "https://store.steampowered.com/app/${game.appId}/",
                    owned = true,
                )
            }
        }
    }

    override fun loadShelves(context: Context, force: Boolean) = Unit
    override fun install(context: Context, item: CatalogItem, root: java.io.File) = Unit
    override fun uninstall(context: Context, game: InstalledStoreGame) = Unit
}
