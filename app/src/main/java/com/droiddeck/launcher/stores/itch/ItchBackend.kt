package com.droiddeck.launcher.stores.itch

import android.content.Context
import android.content.Intent
import android.net.Uri
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreSizes
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import java.io.File

object ItchBackend : StoreBackend {
    override val store: Store = Store.ITCH

    override fun signIn(context: Context) {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(ItchApi.KEYS_PAGE)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun signOut(context: Context) {
        ItchPrefs.get(context).edit().remove("library_cache").remove("library_synced_at").apply()
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
        Thread({ ItchLibrary.sync(app, force) }, "itch-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        val app = context.applicationContext
        ItchShelves.cached(app)?.let { cached -> StoresState.post { StoresState.shelves[store] = cached } }
        Thread({
            val shelves = ItchShelves.featured(app, force)
            if (shelves != null) {
                StoresState.post { StoresState.shelves[store] = shelves }
            } else {
                StoresState.post { StoresState.problems[store] = app.getString(R.string.stores_itch_no_catalog) }
            }
        }, "itch-shelves").start()
    }

    override fun search(context: Context, query: String): List<CatalogItem> = ItchShelves.search(context, query)

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        if (item.extra["downloadKeyId"].isNullOrEmpty() && !item.isFree) {
            StoresState.logLine("itch.io: \"${item.title}\" is not in the library")
            return
        }
        val folder = StoreInstallRoot.folderFor(app, Store.ITCH, item.id, item.title, root)
        val entry = DownloadEntry(
            Store.ITCH, item.id, item.title, cover = item.imageUrl, bytesTotal = StoreSizes.size(item),
            location = StoreInstallRoot.labelFor(app, folder),
        )
        DownloadQueue.enqueue(app, entry) { ItchInstaller(app, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
