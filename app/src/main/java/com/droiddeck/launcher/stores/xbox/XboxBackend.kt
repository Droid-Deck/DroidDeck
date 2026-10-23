package com.droiddeck.launcher.stores.xbox

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.*
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import java.io.File

object XboxBackend : StoreBackend {
    override val store = Store.XBOX
    @Volatile private var access: Map<String, String> = emptyMap()
    @Volatile private var generation = 0
    private var syncing: Int? = null

    override fun signIn(context: Context) {
        context.startActivity(Intent(context, XboxLoginActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun signOut(context: Context) {
        XboxCredentials.invalidate(context) {
            synchronized(this) {
                generation++
                access = emptyMap()
            }
            XboxLibraryCache.clear(context)
            XboxRuntime.stop()
        }
        StoresState.post { packageChoice = null; StoresState.shelves.remove(store); StoresState.found.remove(store) }
    }

    @Synchronized override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        val current = generation
        if (syncing == current) return
        syncing = current
        Thread({
            try {
                val account = StoreAccounts.signedInAs(app, store) ?: error("Sign in to Xbox first.")
                val market = Xodus.market()
                val cached = XboxLibraryCache.read(app, account, market)
                synchronized(this) {
                    if (current != generation) return@Thread
                    if (cached != null) access = cached.games.associate { it.id to it.extra.getValue("access") }
                }
                StoresState.post {
                    if (current == generation) {
                        if (cached != null) StoresState.library[store] = mergeItems(StoresState.library[store], cached.games)
                        StoresState.problems.remove(store)
                        if (cached == null || force) StoresState.status[store] = "Syncing Xbox library…"
                        else StoresState.status.remove(store)
                    }
                }
                if (cached != null && !cached.needsRefresh(force)) { XboxGamertag.ensure(app); return@Thread }
                val response = Xodus.run(app, listOf("library", "--json", "--market", market))
                val licenses = XboxCatalog.access(response, account, market, report = { StoresState.logLine("Xbox: $it") })
                val products = XboxCatalog.rawProducts(licenses.keys.toList(), market)
                val games = products.mapNotNull { XboxCatalog.product(it, licenses) }.sortedBy { it.title.lowercase() }
                StoresState.logLine("Xbox: library catalog requested=${licenses.size} returned=${products.size} supported=${games.size} " +
                    "filtered=${products.size - games.size} backend-skipped=${response.optInt("skippedEntitlements")}")
                synchronized(this) {
                    if (current != generation || StoreAccounts.signedInAs(app, store) != account) return@Thread
                    access = licenses
                    runCatching { XboxLibraryCache.write(app, account, market, games) }
                        .onFailure { StoresState.logLine("Xbox: library cache could not be saved") }
                }
                StoresState.post {
                    if (current == generation) {
                        StoresState.library[store] = mergeItems(StoresState.library[store], games)
                        StoresState.status.remove(store)
                        StoresState.problems.remove(store)
                    }
                }
                if (current == generation) XboxGamertag.ensure(app)
            } catch (e: Exception) {
                StoresState.post {
                    if (current == generation) {
                        StoresState.status.remove(store)
                        StoresState.problems[store] = e.message ?: "Xbox library could not be loaded."
                    }
                }
            } finally {
                synchronized(this) { if (syncing == current) syncing = null }
            }
        }, "xbox-library").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        val current = generation
        Thread({
            runCatching { XboxCatalog.featured(access) }.onSuccess { games ->
                if (current == generation) StoresState.post { StoresState.shelves[store] = StoreShelves(trending = games) }
            }.onFailure { if (current == generation) StoresState.post { StoresState.problems[store] = "Microsoft Store could not be reached. Try again." } }
        }, "xbox-store").start()
    }

    override fun search(context: Context, query: String): List<CatalogItem> = XboxCatalog.search(query, access)

    class PackageChoice(val item: CatalogItem, val root: File, val choices: List<org.json.JSONObject>)
    var packageChoice by androidx.compose.runtime.mutableStateOf<PackageChoice?>(null)
        private set

    fun dismissPackages() { packageChoice = null }
    fun selectPackage(context: Context, choice: org.json.JSONObject) {
        val pending = packageChoice ?: return
        packageChoice = null
        val item = pending.item.copy(extra = pending.item.extra + ("contentId" to choice.getString("contentId")))
        com.droiddeck.launcher.stores.download.SavedQueue.remember(context, item, pending.root)
        enqueue(context.applicationContext, item, pending.root)
    }

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        if (!item.extra["baseId"].isNullOrBlank()) { XboxContent.install(app, item); return }
        if (!item.extra["contentId"].isNullOrBlank()) { enqueue(app, item, root); return }
        Thread({
            try {
                val plan = Xodus.run(app, listOf("resolve-package", item.id, "--json", "--market", item.extra["market"] ?: Xodus.market()))
                require(plan.getInt("schema") == 1 && plan.getString("rootProductId") == item.id)
                val choices = XboxCatalog.objects(plan.getJSONArray("choices")).filter { it.optBoolean("supported") && it.optString("kind") == "Game" }
                check(choices.isNotEmpty()) { "This offer has no supported PC package." }
                if (plan.getString("status") == "ready" && choices.size == 1) {
                    val selected = item.copy(extra = item.extra + ("contentId" to choices.single().getString("contentId")))
                    com.droiddeck.launcher.stores.download.SavedQueue.remember(app, selected, root)
                    enqueue(app, selected, root)
                } else StoresState.post { packageChoice = PackageChoice(item, root, choices) }
            } catch (e: Exception) {
                DownloadQueue.recordFailed(app, DownloadEntry(store, item.id, item.title), e.message ?: "Xbox package could not be resolved.") { install(app, item, root) }
            }
        }, "xbox-package").start()
    }

    fun update(context: Context, game: InstalledStoreGame) {
        check(!XboxRuntime.isActive(game.sidecar.id)) { "Close this Xbox game before updating it." }
        val sidecar = game.sidecar
        val item = CatalogItem(store, sidecar.id, sidecar.title, sidecar.hero, tallImageUrl = sidecar.cover,
            owned = sidecar.extra["access"] == "owned", subscription = sidecar.extra["access"] == "gamepass", extra = sidecar.extra)
        val root = game.folder.parentFile!!.parentFile!!
        com.droiddeck.launcher.stores.download.SavedQueue.remember(context, item, root)
        enqueue(context, item, root)
    }

    private fun enqueue(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        val folder = StoreInstallRoot.folderFor(app, store, item.id, item.title, root)
        val entry = DownloadEntry(store, item.id, item.title, cover = item.imageUrl, bytesTotal = item.sizeBytes,
            location = StoreInstallRoot.labelFor(app, folder))
        DownloadQueue.enqueue(app, entry) { XboxInstaller(app, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}
