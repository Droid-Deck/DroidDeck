package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.EpicLaunchSupport
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.LoginFlows
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreExe
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadStage
import org.json.JSONObject
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Epic Games in the Stores section: sign-in, the library, the public store, installs and the per-launch sign-in code. */
object EpicBackend : StoreBackend, EpicLaunchSupport {
    private const val TAG = "EpicBackend"
    override val store: Store = Store.EPIC

    init { LoginFlows.epicFlow = { LoginFlow } }

    override fun signIn(context: Context) {
        context.startActivity(Intent(context, StoreLoginActivity::class.java).putExtra(StoreLoginActivity.EXTRA_STORE, store.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun signOut(context: Context) {
        EpicPrefs.get(context).edit().remove("library_cache").remove("library_synced_at").apply()
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        val cached = EpicLibrary.cached(app)
        if (cached.isNotEmpty()) StoresState.post { StoresState.library[store] = cached.map { EpicLibrary.toCatalogItem(app, it) } }
        StoresState.post { StoresState.status[store] = "Fetching your library…" }
        Thread({
            val result = EpicLibrary.sync(app, force) { line -> StoresState.post { StoresState.status[store] = line } }
            StoresState.post {
                StoresState.status.remove(store)
                when (result) {
                    is EpicLibrary.SyncResult.Ok -> { StoresState.library[store] = result.games.map { EpicLibrary.toCatalogItem(app, it) }; StoresState.problems.remove(store) }
                    is EpicLibrary.SyncResult.Failed -> StoresState.problems[store] = result.message
                    EpicLibrary.SyncResult.NotLoggedIn -> StoresState.problems[store] = "Epic session expired: sign in again."
                    EpicLibrary.SyncResult.Throttled -> if (!StoresState.library.containsKey(store)) StoresState.library[store] = cached.map { EpicLibrary.toCatalogItem(app, it) }
                    EpicLibrary.SyncResult.Busy -> {}
                }
            }
        }, "epic-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        val app = context.applicationContext
        EpicStoreCatalog.cachedFeatured(app)?.let { StoresState.post { StoresState.shelves[store] = markOwned(it) } }
        Thread({
            val shelves = EpicStoreCatalog.featured(app, force)
            StoresState.post { if (shelves != null) StoresState.shelves[store] = markOwned(shelves) }
        }, "epic-shelves").start()
    }

    /** Store offers whose catalog item is in the library are owned: their cards offer Install under the library's id. */
    private fun markOwned(s: StoreShelves): StoreShelves {
        val owned = StoresState.library[store] ?: return s
        val byCatalogItem = owned.associateBy { it.extra["catalogItemId"] ?: "" }
        fun mark(l: List<CatalogItem>) = l.map { i ->
            val match = i.extra["items"]?.split(',')?.firstNotNullOfOrNull { byCatalogItem[it] }
            if (match != null) match.copy(imageUrl = i.imageUrl ?: match.imageUrl, tallImageUrl = i.tallImageUrl ?: match.tallImageUrl) else i
        }
        return StoreShelves(mark(s.whatsNew), mark(s.deals), mark(s.free), mark(s.trending))
    }

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        val namespace = item.extra["namespace"] ?: ""
        val catalogItemId = item.extra["catalogItemId"] ?: ""
        if (namespace.isEmpty() || catalogItemId.isEmpty()) { StoresState.logLine("Epic: \"${item.title}\" is not in your library"); return }
        val folder = StoreInstallRoot.folderFor(app, Store.EPIC, item.id, item.title, root)
        val entry = DownloadEntry(store, item.id, item.title, cover = item.imageUrl, bytesTotal = item.sizeBytes, location = StoreInstallRoot.labelFor(app, folder))
        DownloadQueue.enqueue(app, entry) { InstallJob(app, item, namespace, catalogItemId, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}

    /** A fresh exchange code for the signed-in account; null when signed out or offline. Blocking. */
    override fun exchangeCode(context: Context): String? {
        val token = EpicCredentialStore.getValidAccessToken(context) ?: return null
        return EpicAuthClient.getExchangeCode(token)
    }

    private class InstallJob(val app: Context, val item: CatalogItem, val namespace: String, val catalogItemId: String, val folder: File) : DownloadQueue.DownloadJob {
        private val cancelled = AtomicBoolean(false)
        // On a card the in-flight chunks go to the app's cache instead: the card's write rate paces
        // the whole install, and chunks are read back once at assembly. Internal installs keep the
        // cache beside the game. A resume keeps whichever cache the stopped run filled: a
        // `.chunks` already beside the game is reused, not fetched again into the scratch folder.
        private val scratch: File? = if (StoreInstallRoot.isRemovable(app, folder) && !hasChunks(File(folder, ".chunks"))) StoreInstallRoot.scratchDir(app, Store.EPIC, item.id) else null

        private fun hasChunks(dir: File): Boolean = dir.isDirectory && (dir.list()?.isNotEmpty() == true)

        override fun run(handle: DownloadQueue.JobHandle): String? {
            handle.stage(DownloadStage.MANIFEST, "Checking sign-in…")
            val token = EpicCredentialStore.getValidAccessToken(app) ?: throw EpicDownloadManager.InstallException("Not signed in to Epic Games")
            StoreInstalls.begin(folder, Store.EPIC, item.id, item.title, item.tallImageUrl ?: item.imageUrl, item.imageUrl)
            if (scratch == null && hasChunks(File(folder, ".chunks"))) handle.log("epic: resuming \"${item.title}\" with the chunks already beside it")
            val manifestJson = EpicApiClient.getManifestApiJson(token, namespace, catalogItemId, item.id)
                ?: throw EpicDownloadManager.InstallException("The manifest could not be fetched")
            val tags = EpicInstallTags.tagsForDevice()
            var downloading = false
            val result = EpicDownloadManager.install(app, manifestJson, folder.path, scratch?.path ?: "", tags, cancelled, object : EpicDownloadManager.Callback {
                override fun onProgress(message: String, pct: Int) {
                    when {
                        message.startsWith("Writing") || message.startsWith("Complete") -> handle.stage(DownloadStage.INSTALL, message)
                        message.startsWith("Downloading chunks") -> { if (!downloading) { downloading = true; handle.stage(DownloadStage.DOWNLOAD, message) } else handle.progress(-1, -1, message) }
                        message.startsWith("Verifying") -> handle.stage(DownloadStage.VERIFY, message)
                        else -> handle.stage(DownloadStage.MANIFEST, message)
                    }
                }
                override fun onBytes(done: Long, total: Long, speedBps: Long) { handle.progress(done, total, null, speedBps) }
                override fun onLog(line: String) { handle.log(line) }
                override fun onStage(stage: String, done: Long, total: Long, items: Int, itemsTotal: Int) {
                    handle.stage(if (stage == "verify") DownloadStage.VERIFY else DownloadStage.INSTALL)
                    handle.stageProgress(done, total, items, itemsTotal)
                }
            }) ?: return null
            if (cancelled.get()) return null
            handle.stage(DownloadStage.INSTALL, "Registering with Steam…")
            EpicLibrary.rememberSize(app, item.id, result.bytes)
            val deploymentId = runCatching { JSONObject(manifestJson).optString("deploymentId", "") }.getOrDefault("")
            val exe = StoreExe.pick(folder, result.launchExe, item.title)
            if (exe.isEmpty()) handle.log("epic: no exe found in ${folder.name}; pick one in Steam settings › Added games")
            val args = EpicLaunchData.arguments(app, token, item.id, namespace, catalogItemId, deploymentId)
            val sidecar = StoreGameSidecar(
                Store.EPIC, item.id, item.title, exe = exe.ifEmpty { "game.exe" }, args = args,
                installVersion = result.buildVersion, installedAt = System.currentTimeMillis(),
                cover = item.tallImageUrl ?: item.imageUrl, hero = item.imageUrl,
                extra = EpicLaunchData.extras(item.id, namespace, catalogItemId, deploymentId),
            )
            StoreInstalls.complete(app, folder, sidecar)
            return folder.path
        }

        override fun cancel(deleteFiles: Boolean) {
            cancelled.set(true)
            if (deleteFiles) Thread({ StoreInstalls.deleteTree(folder); scratch?.let { StoreInstalls.deleteTree(it) } }, "epic-cancel-clean").start()
        }
    }

    /**
     * Epic's web sign-in: the login page redirects to a JSON page carrying an authorization code,
     * read out of the document and exchanged for tokens.
     */
    private object LoginFlow : StoreLoginActivity.Flow {
        private const val REDIRECT = "https://www.epicgames.com/id/api/redirect"
        override val startUrl: String = "https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Fid%2Fapi%2Fredirect%3FclientId%3D${EpicAuthClient.CLIENT_ID}%26responseType%3Dcode"
        override val userAgent: String get() = EpicAuthClient.USER_AGENT
        override val readsPage: Boolean get() = true
        override fun isRedirect(url: String): Boolean = url.startsWith(REDIRECT)

        override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
            // The document's text comes JSON-quoted from evaluateJavascript.
            var text = url
            if (text.startsWith("\"")) text = text.substring(1, text.length - 1)
            text = text.replace("\\n", "").replace("\\r", "").replace("\\\"", "\"")
            val code = Regex("\"authorizationCode\":\"([^\"]+)\"").find(text)?.groupValues?.get(1)
            if (code.isNullOrEmpty()) { Log.w(TAG, "login: no authorization code on the redirect page"); return activity.getString(com.droiddeck.launcher.R.string.stores_login_error_verification) }
            val result = EpicAuthClient.exchangeCode(code) ?: return activity.getString(com.droiddeck.launcher.R.string.stores_login_error_generic)
            EpicCredentialStore.save(activity, result)
            Log.i(TAG, "signed in")
            return null
        }
    }
}
