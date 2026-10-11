package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.content.Intent
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** GOG in the Stores section: sign-in, the library, the public shelves, installs. */
object GogBackend : StoreBackend {
    override val store: Store = Store.GOG

    override fun signIn(context: Context) {
        context.startActivity(Intent(context, StoreLoginActivity::class.java).putExtra(StoreLoginActivity.EXTRA_STORE, store.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun signOut(context: Context) {
        GogPrefs.get(context).edit().remove("library_cache").remove("library_cache_v2").remove("library_synced_at").apply()
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            // The cache first, so the tab has something while the sync runs; read here, not on the
            // main thread. With a cache on screen a background sync keeps quiet: only Refresh shows its bar.
            val cached = GogLibrary.cached(app).map { GogLibrary.toCatalogItem(app, it) }
            val quiet = cached.isNotEmpty() && !force
            StoresState.post {
                if (cached.isNotEmpty()) StoresState.library[store] = com.droiddeck.launcher.stores.mergeItems(StoresState.library[store], cached)
                if (!quiet) StoresState.status[store] = "Fetching your library…"
            }
            val result = GogLibrary.sync(app, force) { line -> if (!quiet) StoresState.post { StoresState.status[store] = line } }
            StoresState.post {
                StoresState.status.remove(store)
                when (result) {
                    is GogLibrary.SyncResult.Ok -> { StoresState.library[store] = com.droiddeck.launcher.stores.mergeItems(StoresState.library[store], result.games.map { GogLibrary.toCatalogItem(app, it) }); StoresState.problems.remove(store) }
                    is GogLibrary.SyncResult.Failed -> StoresState.problems[store] = result.message
                    GogLibrary.SyncResult.NotLoggedIn -> StoresState.problems[store] = StoresState.notSignedInLine(store)
                    GogLibrary.SyncResult.Busy -> {}
                }
            }
        }, "gog-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        val app = context.applicationContext
        GogStoreCatalog.cachedFeatured(app)?.let { StoresState.post { StoresState.shelves[store] = it } }
        Thread({
            val shelves = GogStoreCatalog.featured(app, force)
            StoresState.post { if (shelves != null) StoresState.shelves[store] = markOwned(shelves) }
        }, "gog-shelves").start()
    }

    /** The shelves with the library's titles marked owned, so their cards offer Install. */
    private fun markOwned(s: com.droiddeck.launcher.stores.StoreShelves): com.droiddeck.launcher.stores.StoreShelves {
        val owned = StoresState.library[store]?.associateBy { it.id } ?: return s
        fun mark(l: List<CatalogItem>) = l.map { i -> owned[i.id]?.let { o -> i.copy(owned = true, sizeBytes = o.sizeBytes) } ?: i }
        return com.droiddeck.launcher.stores.StoreShelves(mark(s.whatsNew), mark(s.deals), mark(s.free), mark(s.trending))
    }

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        val game = GogGame(item.id, item.title, item.imageUrl ?: "", item.description, item.developer, item.tags, 2, item.tallImageUrl)
        val folder = StoreInstallRoot.folderFor(app, Store.GOG, game.gameId, game.title, root)
        val entry = DownloadEntry(store, item.id, item.title, cover = item.imageUrl, bytesTotal = item.sizeBytes, location = StoreInstallRoot.labelFor(app, folder))
        DownloadQueue.enqueue(app, entry) { InstallJob(app, game, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}

    /** One GOG install, as the queue runs it. */
    private class InstallJob(val app: Context, val game: GogGame, val item: CatalogItem, val folder: File) : DownloadQueue.DownloadJob {
        private val cancelled = AtomicBoolean(false)

        override fun run(handle: DownloadQueue.JobHandle): String? {
            handle.stage(DownloadStage.MANIFEST)
            StoreInstalls.begin(folder, Store.GOG, game.gameId, game.title, item.tallImageUrl ?: item.imageUrl, item.imageUrl)
            var downloading = false
            val result = GogDownloadManager.install(app, game, folder, object : GogDownloadManager.Callback {
                override fun onProgress(message: String, pct: Int) {
                    when {
                        message.startsWith("Finishing") -> handle.stage(DownloadStage.INSTALL, message)
                        message.startsWith("Downloading") || message.startsWith("Verified") || message.startsWith("Resuming") -> {
                            if (!downloading) { downloading = true; handle.stage(DownloadStage.DOWNLOAD, message) } else handle.progress(-1, -1, message)
                        }
                        else -> handle.stage(DownloadStage.MANIFEST, message)
                    }
                }
                override fun onBytes(done: Long, total: Long, speedBps: Long) { handle.progress(done, total, null, speedBps) }
                override fun onLog(line: String) { handle.log(line) }
                override fun onSizes(downloadBytes: Long, diskBytes: Long) { handle.diskSize(diskBytes) }
                override fun onStage(stage: String, done: Long, total: Long, items: Int, itemsTotal: Int) {
                    handle.stage(when (stage) { "check" -> DownloadStage.MANIFEST; "verify" -> DownloadStage.VERIFY; else -> DownloadStage.INSTALL })
                    handle.stageProgress(done, total, items, itemsTotal, bytes = stage == "install")
                }
            }, cancelled) ?: return null
            if (cancelled.get()) return null
            handle.stage(DownloadStage.INSTALL, "Registering with Steam…")
            val sidecar = StoreGameSidecar(
                Store.GOG, game.gameId, game.title, exe = result.exeRelative.ifEmpty { "" },
                installVersion = result.buildId, installedAt = System.currentTimeMillis(),
                cover = item.tallImageUrl ?: item.imageUrl, hero = item.imageUrl,
                // Kept even when empty: the build was read, and lists none.
                extra = mapOf(com.droiddeck.launcher.frontend.WinCompSources.GOG_DEPENDENCIES to result.dependencies.joinToString(","))
                    + (if (result.dependenciesRead) mapOf(com.droiddeck.launcher.frontend.WinCompSources.LIST_READ to "1") else emptyMap()),
            )
            if (sidecar.exe.isEmpty()) handle.log("gog: no exe found in ${folder.name}; pick one in Steam settings › Added games")
            StoreInstalls.complete(app, folder, onStep = { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) }, sidecar = if (sidecar.exe.isEmpty()) sidecar.copy(exe = "game.exe") else sidecar)
            return folder.path
        }

        override fun cancel(deleteFiles: Boolean) {
            cancelled.set(true)
            if (deleteFiles) StoreInstalls.discard(app, folder, listOf(File(folder, ".gog_chunks")))
        }
    }
}
