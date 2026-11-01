package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.LoginFlows
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoreSizes
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import java.io.File

object GameJoltBackend : StoreBackend {
    override val store: Store = Store.GAMEJOLT

    init {
        LoginFlows.gameJoltFlow = { agent -> GameJoltLogin(agent) }
    }

    override fun signIn(context: Context) {
        context.startActivity(
            Intent(context, StoreLoginActivity::class.java)
                .putExtra(StoreLoginActivity.EXTRA_STORE, store.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun signOut(context: Context) {
        val app = context.applicationContext
        GameJoltPrefs.get(app).edit().clear().apply()
        GameJoltAuth.clear(app)
        GameJoltLibrary.reset()
        StoresState.post {
            StoresState.library.remove(store)
            StoresState.shelves.remove(store)
            StoresState.found.remove(store)
            StoresState.problems.remove(store)
            StoresState.status.remove(store)
        }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        if (force) GameJoltLibrary.reset()
        Thread({ GameJoltLibrary.sync(app, force) }, "gamejolt-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        StoresState.post { StoresState.shelves[store] = StoreShelves() }
    }

    override fun search(context: Context, query: String): List<CatalogItem> {
        val cookies = GameJoltAuth.cookies(context)
        val items = ArrayList<CatalogItem>()
        for (page in 1..2) {
            val answer = GameJoltApi.search(query, page, cookies)
            val found = if (answer.ok) GameJoltCatalog.items(answer.payload?.optJSONArray("games")) else emptyList()
            if (found.isEmpty()) break
            items.addAll(found)
        }
        StoresState.logLine("Game Jolt search: \"$query\" returned ${items.size} games")
        return items.distinctBy { it.id }
    }

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        if (!item.isFree && !item.owned) {
            StoresState.logLine("Game Jolt: \"${item.title}\" is a paid game, opening its page instead")
            return
        }
        val folder = StoreInstallRoot.folderFor(app, Store.GAMEJOLT, item.id, item.title, root)
        val entry = DownloadEntry(
            Store.GAMEJOLT, item.id, item.title, cover = item.imageUrl, bytesTotal = StoreSizes.size(item),
            location = StoreInstallRoot.labelFor(app, folder),
        )
        DownloadQueue.enqueue(app, entry) { GameJoltInstaller(app, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
