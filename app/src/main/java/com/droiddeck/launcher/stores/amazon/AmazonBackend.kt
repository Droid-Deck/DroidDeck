package com.droiddeck.launcher.stores.amazon

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.stores.CatalogItem
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
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Amazon Games in the Stores section. Amazon has no browsable catalog for third-party clients -
 * Prime Gaming's claims live on gaming.amazon.com, signed in - so the Store tab's shelves are cut
 * from the account's own library: owned-not-installed as Trending, installed as Your library.
 */
object AmazonBackend : StoreBackend {
    private const val TAG = "AmazonBackend"
    override val store: Store = Store.AMAZON

    init { LoginFlows.amazonFlow = { LoginFlow() } }

    override fun signIn(context: Context) {
        context.startActivity(Intent(context, StoreLoginActivity::class.java).putExtra(StoreLoginActivity.EXTRA_STORE, store.id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    override fun signOut(context: Context) {
        AmazonCredentialStore.load(context)?.let { Thread({ AmazonAuthClient.deregisterDevice(it.accessToken) }, "amazon-deregister").start() }
        AmazonPrefs.get(context).edit().remove("library_cache").remove("library_synced_at").apply()
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({
            // The cache off the main thread; with it on screen a background sync keeps quiet.
            val cached = AmazonLibrary.cached(app)
            val quiet = cached.isNotEmpty() && !force
            StoresState.post {
                if (cached.isNotEmpty()) publish(app, cached)
                if (!quiet) StoresState.status[store] = "Fetching your library…"
            }
            val result = AmazonLibrary.sync(app, force) { line -> if (!quiet) StoresState.post { StoresState.status[store] = line } }
            StoresState.post {
                StoresState.status.remove(store)
                when (result) {
                    is AmazonLibrary.SyncResult.Ok -> { publish(app, result.games); StoresState.problems.remove(store) }
                    is AmazonLibrary.SyncResult.Failed -> StoresState.problems[store] = result.message
                    AmazonLibrary.SyncResult.NotLoggedIn -> StoresState.problems[store] = StoresState.notSignedInLine(store)
                    AmazonLibrary.SyncResult.Throttled -> publish(app, AmazonLibrary.cached(app))
                    AmazonLibrary.SyncResult.Busy -> {}
                }
            }
        }, "amazon-sync").start()
    }

    /** The library and, from it, the shelves. */
    private fun publish(app: Context, games: List<AmazonGame>) {
        val items = games.map { AmazonLibrary.toCatalogItem(app, it) }
        StoresState.library[store] = com.droiddeck.launcher.stores.mergeItems(StoresState.library[store], items)
        val installed = StoresState.installed.filter { it.sidecar.store == store }.map { it.sidecar.id }.toSet()
        StoresState.shelves[store] = StoreShelves(trending = items.filter { it.id !in installed }, free = emptyList(), deals = emptyList(), whatsNew = emptyList())
    }

    override fun loadShelves(context: Context, force: Boolean) {
        // Nothing public to fetch; the shelves follow the library sync.
        if (!StoresState.library.containsKey(store)) syncLibrary(context, force)
    }

    override fun install(context: Context, item: CatalogItem, root: File) {
        val app = context.applicationContext
        val entitlementId = item.extra["entitlementId"] ?: ""
        if (entitlementId.isEmpty()) { StoresState.logLine("Amazon: \"${item.title}\" has no entitlement to download with"); return }
        val game = AmazonGame().apply { productId = item.id; this.entitlementId = entitlementId; title = item.title; productSku = item.extra["sku"] ?: ""; heroUrl = item.imageUrl ?: ""; artUrl = item.tallImageUrl ?: "" }
        val folder = StoreInstallRoot.folderFor(app, Store.AMAZON, game.productId, game.title, root)
        DownloadQueue.enqueue(app, DownloadEntry(store, item.id, item.title, cover = item.imageUrl, bytesTotal = item.sizeBytes, location = StoreInstallRoot.labelFor(app, folder))) { InstallJob(app, game, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}

    private class InstallJob(val app: Context, val game: AmazonGame, val item: CatalogItem, val folder: File) : DownloadQueue.DownloadJob {
        private val cancelled = AtomicBoolean(false)

        override fun run(handle: DownloadQueue.JobHandle): String? {
            handle.stage(DownloadStage.MANIFEST, "Checking sign-in…")
            val token = AmazonCredentialStore.getValidAccessToken(app) ?: throw AmazonDownloadManager.InstallException("Not signed in to Amazon Games")
            StoreInstalls.begin(folder, Store.AMAZON, game.productId, game.title, item.tallImageUrl ?: item.imageUrl, item.imageUrl)
            var downloading = false
            val result = AmazonDownloadManager.install(app, game, token, folder, cancelled, object : AmazonDownloadManager.Callback {
                override fun onProgress(message: String, pct: Int) {
                    when {
                        message.startsWith("Finishing") -> handle.stage(DownloadStage.INSTALL, message)
                        message.startsWith("Downloading") && !message.startsWith("Downloading manifest") -> { if (!downloading) { downloading = true; handle.stage(DownloadStage.DOWNLOAD, message) } else handle.progress(-1, -1, message) }
                        else -> handle.stage(DownloadStage.MANIFEST, message)
                    }
                }
                override fun onBytes(done: Long, total: Long, speedBps: Long) { handle.progress(done, total, null, speedBps) }
                override fun onLog(line: String) { handle.log(line) }
                override fun onSizes(downloadBytes: Long, diskBytes: Long) { handle.diskSize(diskBytes) }
            }) ?: return null
            if (cancelled.get()) return null
            handle.stage(DownloadStage.INSTALL, "Registering with Steam…")
            AmazonLibrary.rememberSize(app, game.productId, result.bytes)
            AmazonPrefs.get(app).edit().putString("version_${game.productId}", result.versionId).apply()
            val spec = AmazonLaunchHelper.buildLaunchSpec(folder, game.title)
            val exe = spec.exeRelative.ifEmpty { StoreExe.pick(folder, null, game.title) }
            if (exe.isEmpty()) handle.log("amazon: no exe found in ${folder.name}; pick one in Steam settings › Added games")
            val env = LinkedHashMap<String, String>()
            for (kv in AmazonLaunchHelper.buildFuelEnv(game.entitlementId, game.productSku)) env[kv.substringBefore('=')] = kv.substringAfter('=')
            val sidecar = StoreGameSidecar(
                Store.AMAZON, game.productId, game.title, exe = exe.ifEmpty { "game.exe" }, args = spec.args, env = env,
                installVersion = result.versionId ?: "", installedAt = System.currentTimeMillis(),
                cover = item.tallImageUrl ?: item.imageUrl, hero = item.imageUrl,
                extra = mapOf("entitlementId" to game.entitlementId, "sku" to game.productSku),
            )
            StoreInstalls.complete(app, folder, onStep = { done, steps -> handle.stageProgress(done.toLong(), steps.toLong()) }, sidecar = sidecar)
            return folder.path
        }

        override fun cancel(deleteFiles: Boolean) {
            cancelled.set(true)
            if (deleteFiles) StoreInstalls.discard(app, folder, emptyList())
        }
    }

    /** Amazon's device sign-in: a PKCE challenge on the sign-in page, the code on the return URL, a device registration for tokens. */
    private class LoginFlow : StoreLoginActivity.Flow {
        private val serial = AmazonPkce.generateDeviceSerial()
        private val clientId = AmazonPkce.generateClientId(serial)
        private val verifier = AmazonPkce.generateCodeVerifier()
        override val startUrl: String = "https://www.amazon.com/ap/signin" +
            "?openid.ns=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0" +
            "&openid.claimed_id=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
            "&openid.identity=http%3A%2F%2Fspecs.openid.net%2Fauth%2F2.0%2Fidentifier_select" +
            "&openid.mode=checkid_setup&openid.oa2.scope=device_auth_access" +
            "&openid.ns.oa2=http%3A%2F%2Fwww.amazon.com%2Fap%2Fext%2Foauth%2F2&openid.oa2.response_type=code" +
            "&openid.oa2.code_challenge_method=S256&openid.oa2.client_id=device%3A$clientId" +
            "&language=en_US&marketPlaceId=ATVPDKIKX0DER&openid.return_to=https%3A%2F%2Fwww.amazon.com" +
            "&openid.pape.max_auth_age=0&openid.ns.pape=http%3A%2F%2Fspecs.openid.net%2Fextensions%2Fpape%2F1.0" +
            "&openid.assoc_handle=amzn_sonic_games_launcher&pageId=amzn_sonic_games_launcher" +
            "&openid.oa2.code_challenge=${AmazonPkce.generateCodeChallenge(verifier)}"
        override val userAgent: String get() = AmazonAuthClient.USER_AGENT
        override fun isRedirect(url: String): Boolean = url.contains("openid.oa2.authorization_code=")

        override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
            val code = Uri.parse(url).getQueryParameter("openid.oa2.authorization_code")
            if (code.isNullOrEmpty()) { Log.w(TAG, "login: return URL without a code"); return activity.getString(com.droiddeck.launcher.R.string.stores_login_error_verification) }
            val result = AmazonAuthClient.registerDevice(code, verifier, serial, clientId) ?: return activity.getString(com.droiddeck.launcher.R.string.stores_login_error_generic)
            AmazonCredentialStore.save(activity, result.accessToken, result.refreshToken, serial, clientId, System.currentTimeMillis() + result.expiresIn * 1000L, result.name)
            Log.i(TAG, "signed in")
            return null
        }
    }
}
