package com.droiddeck.launcher.stores.ea

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import androidx.compose.runtime.mutableStateMapOf
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.LoginFlows
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.WebCookies
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue

object EaBackend : StoreBackend {
    override val store: Store = Store.EA

    @Volatile internal var app: Context? = null

    init {
        LoginFlows.eaFlow = { ua -> app?.let { EaLogin(EaAuth.begin(it), ua) } }
    }

    override fun signIn(context: Context) {
        app = context.applicationContext
        context.startActivity(
            Intent(context, StoreLoginActivity::class.java)
                .putExtra(StoreLoginActivity.EXTRA_STORE, store.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun signOut(context: Context) {
        val app = context.applicationContext
        EaAuth.signOut(app)
        EaLibrary.invalidate()
        runCatching { EaLibrary.forget(app) }
        EaUpdates.available.clear()
        runCatching { WebCookies.expire(EaLogin.SIGN_IN_URLS, ".ea.com", listOf("/")) {} }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        this.app = app
        Thread({ EaLibrary.sync(app, force) }, "ea-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {}

    override fun install(context: Context, item: CatalogItem, root: java.io.File) {
        val app = context.applicationContext
        if (!EaNative.ready()) {
            StoresState.logLine(EaNative.ENGINE_MISSING)
            StoresState.post { StoresState.problems[store] = EaNative.ENGINE_MISSING }
            return
        }
        val folder = StoreInstallRoot.folderFor(app, store, item.id, item.title, root)
        val entry = DownloadEntry(store, item.id, item.title, cover = item.tallImageUrl ?: item.imageUrl, bytesTotal = item.sizeBytes, location = StoreInstallRoot.labelFor(app, folder))
        DownloadQueue.enqueue(app, entry) { EaInstaller(app, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}

internal class EaLogin(private val pending: EaAuth.Pending, override val userAgent: String) : StoreLoginActivity.Flow {
    override val startUrl: String get() = pending.url

    override fun isRedirect(url: String): Boolean = url.startsWith("qrc:", ignoreCase = true)

    override fun forgetSignIn(done: () -> Unit) {
        runCatching { WebCookies.expire(SIGN_IN_URLS, ".ea.com", listOf("/")) { done() } }.onFailure { done() }
    }

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? {
        val code = EaAuth.redirectValue(url, "code") ?: return activity.getString(R.string.stores_login_error_verification)
        return try {
            EaAuth.exchange(activity, code, pending.verifier)
            null
        } catch (e: Exception) {
            Log.w("EaLogin", "sign-in failed: ${e.message}")
            activity.getString(R.string.stores_login_error_generic)
        }
    }

    companion object {
        val SIGN_IN_URLS = listOf("https://accounts.ea.com", "https://signin.ea.com", "https://www.ea.com")
    }
}

object EaUpdates {
    val available = mutableStateMapOf<String, String>()
    private val checked = java.util.concurrent.ConcurrentHashMap<String, Long>()
    private const val RECHECK_MS = 30 * 60 * 1000L

    fun check(context: Context, game: InstalledStoreGame, force: Boolean = false) {
        val app = context.applicationContext
        val id = game.sidecar.id
        val now = System.currentTimeMillis()
        if (!force && now - (checked[id] ?: 0L) < RECHECK_MS) return
        checked[id] = now
        Thread({
            val token = EaAuth.accessToken(app)
            val offer = token?.let { runCatching { EaApi.offers(it, listOf(id)) }.getOrNull() }?.firstOrNull()
            if (offer == null) {
                checked.remove(id)
                return@Thread
            }
            val live = offer.gameVersion
            val installed = game.sidecar.installVersion
            StoresState.post {
                if (live.isNotEmpty() && installed.isNotEmpty() && live != installed) available[game.key] = live else available.remove(game.key)
            }
        }, "ea-update-check").start()
    }

    fun start(context: Context, game: InstalledStoreGame) {
        val app = context.applicationContext
        val root = game.folder.parentFile?.parentFile ?: return
        val known = EaLibrary.game(app, game.sidecar.id)
        val item = known?.let { EaLibrary.toCatalogItem(app, it) } ?: CatalogItem(
            Store.EA, game.sidecar.id, game.sidecar.title, game.sidecar.hero, tallImageUrl = game.sidecar.cover, owned = true, extra = game.sidecar.extra,
        )
        available.remove(game.key)
        StoresState.install(app, item, root)
    }
}
