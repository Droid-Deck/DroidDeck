package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.LoginFlows
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import org.json.JSONObject

object AmazonBackend : StoreBackend {
    override val store: Store = Store.AMAZON

    init {
        LoginFlows.amazonFlow = { AmazonLogin.create() }
    }

    private fun engineMissing(): Boolean {
        if (AmazonNative.ready()) return false
        StoresState.logLine(AmazonNative.ENGINE_MISSING)
        StoresState.post { StoresState.problems[store] = AmazonNative.ENGINE_MISSING }
        return true
    }

    override fun signIn(context: Context) {
        if (engineMissing()) return
        context.startActivity(
            Intent(context, StoreLoginActivity::class.java)
                .putExtra(StoreLoginActivity.EXTRA_STORE, store.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun signOut(context: Context) {
        val app = context.applicationContext
        runCatching {
            val token = AmazonCredentialStore.load(app)?.accessToken
            if (!token.isNullOrEmpty()) {
                Thread({
                    runCatching { AmazonNative.call("auth.deregister", JSONObject().put("accessToken", token)) }
                }, "amazon-deregister").start()
            }
        }
        runCatching { AmazonLibrary.forget(app) }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({ AmazonLibrary.sync(app, force) }, "amazon-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        val app = context.applicationContext
        AmazonShelves.cached(app)?.let { s -> StoresState.post { withArt(s.art) } }
        Thread({
            val shelves = AmazonShelves.featured(app, force)
            if (shelves != null) StoresState.post { withArt(shelves.art) }
        }, "amazon-shelves").start()
    }

    private fun withArt(art: List<String>) {
        val s = StoresState.shelves[store]
        StoresState.shelves[store] = if (s == null) com.droiddeck.launcher.stores.StoreShelves(art = art)
            else com.droiddeck.launcher.stores.StoreShelves(s.whatsNew, s.deals, s.free, s.trending, art)
    }

    override fun install(context: Context, item: CatalogItem, root: java.io.File) {
        val app = context.applicationContext
        if (item.extra["entitlementId"].isNullOrEmpty()) {
            StoresState.logLine("amazon: \"${item.title}\" has no entitlement to download with")
            return
        }
        val folder = StoreInstallRoot.folderFor(app, Store.AMAZON, item.id, item.title, root)
        val entry = DownloadEntry(
            Store.AMAZON, item.id, item.title, cover = item.imageUrl, bytesTotal = item.sizeBytes,
            location = StoreInstallRoot.labelFor(app, folder),
        )
        DownloadQueue.enqueue(app, entry) { AmazonInstaller(app, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
