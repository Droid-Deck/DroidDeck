package com.droiddeck.launcher.stores.rockstar

import android.content.Context
import android.content.Intent
import android.util.Log
import android.webkit.WebView
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.LoginFlows
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreBackend
import com.droiddeck.launcher.stores.StoreFront
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.WebCookies
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import org.w3c.dom.Element

object RockstarBackend : StoreBackend {
    override val store: Store = Store.ROCKSTAR

    init {
        LoginFlows.rockstarFlow = { ua -> RockstarLogin(ua) }
    }

    override fun signIn(context: Context) {
        context.startActivity(
            Intent(context, StoreLoginActivity::class.java)
                .putExtra(StoreLoginActivity.EXTRA_STORE, store.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun signOut(context: Context) {
        RockstarAuth.signOut(context.applicationContext)
        RockstarLibrary.reset()
        runCatching { WebCookies.expire(RockstarAuth.SIGN_IN_URLS, RockstarAuth.COOKIE_DOMAIN, listOf("/")) {} }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({ RockstarLibrary.sync(app) }, "rockstar-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {
        StoresState.post { StoresState.shelves[store] = StoreFront.markOwned(RockstarCatalog.shelves(), StoresState.library[store], RockstarCatalog::sameGame) }
    }

    override fun search(context: Context, query: String): List<CatalogItem> =
        StoreFront.markOwned(RockstarCatalog.search(query), StoresState.library[store], RockstarCatalog::sameGame)

    override fun install(context: Context, item: CatalogItem, root: java.io.File) {
        val app = context.applicationContext
        val folder = StoreInstallRoot.folderFor(app, store, item.id, item.title, root)
        val entry = DownloadEntry(store, item.id, item.title, cover = item.tallImageUrl ?: item.imageUrl,
            bytesTotal = item.sizeBytes, location = StoreInstallRoot.labelFor(app, folder))
        DownloadQueue.enqueue(app, entry) { RockstarInstaller(app, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}

internal class RockstarLogin(override val userAgent: String) : StoreLoginActivity.Flow {
    override val startUrl: String get() = RockstarAuth.START_URL
    override fun isRedirect(url: String): Boolean = RockstarAuth.isCodeUrl(url)

    override fun pageFinished(view: WebView, url: String) {
        if (!url.startsWith(RockstarAuth.START_URL)) return
        val title = "launcher"
        view.evaluateJavascript(RockstarAuth.bridgeScript(RockstarAuth.fingerprint(view.context), title)) { Log.d("RockstarLogin", "bridge: $it") }
    }

    override fun forgetSignIn(done: () -> Unit) {
        runCatching { WebCookies.expire(RockstarAuth.SIGN_IN_URLS, RockstarAuth.COOKIE_DOMAIN, listOf("/")) { done() } }.onFailure { done() }
    }

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? = if (RockstarAuth.cancelled(url)) null else try {
        RockstarAuth.completeSignIn(activity, url)?.let {
            Log.w("RockstarLogin", "sign-in incomplete: $it")
            activity.getString(R.string.stores_login_error_verification)
        }
    } catch (e: Exception) {
        Log.w("RockstarLogin", "sign-in failed: ${e.javaClass.simpleName}")
        activity.getString(R.string.stores_login_error_generic)
    }
}

internal data class RockstarApp(val id: String, val name: String, val titleId: String, val branches: List<RockstarBranch>, val parent: String = "")

internal data class RockstarBranch(val id: String, val name: String, val buildId: String, val isDefault: Boolean, val accessToken: String) {
    val hasBuild: Boolean get() = (buildId.toLongOrNull() ?: 0L) > 0 && accessToken.isNotEmpty()
}

internal data class RockstarPackage(val title: String, val featured: Boolean)

internal class RockstarAccountApps(val apps: List<RockstarApp>, val packages: List<RockstarPackage>)

internal object RockstarApps {
    fun parse(root: Element): List<RockstarApp> = RockstarRos.all(root, "App").map { a ->
        val branches = RockstarRos.first(a, "Branches")?.let { RockstarRos.children(it, "Branch") }.orEmpty().map { b ->
            RockstarBranch(b.getAttribute("Id"), b.getAttribute("Name"), b.getAttribute("BuildId"),
                b.getAttribute("IsDefault").equals("true", true), RockstarRos.text(b, "AccessToken").orEmpty())
        }
        val parent = (a.parentNode?.parentNode as? Element)?.takeIf { it.tagName == "App" }?.getAttribute("Name").orEmpty()
        RockstarApp(a.getAttribute("Id"), a.getAttribute("Name"), a.getAttribute("TitleId"), branches, parent)
    }

    fun packages(root: Element): List<RockstarPackage> = RockstarRos.all(root, "PackageJson").mapNotNull { p ->
        val o = runCatching { org.json.JSONObject(p.textContent) }.getOrNull() ?: return@mapNotNull null
        o.optString("title").takeIf { it.isNotEmpty() }?.let { RockstarPackage(it, o.optBoolean("isFeatured")) }
    }

    fun fetch(ticket: String, log: (String) -> Unit): RockstarAccountApps {
        val root = RockstarRos.result("app.asmx/GetAppsAndPackages", listOf("ticket" to ticket))
        val out = RockstarAccountApps(parse(root), packages(root))
        log("Rockstar: packages " + out.packages.joinToString(", ") { it.title + if (it.featured) " (featured)" else "" }.ifEmpty { "none" } +
            "; builds " + out.apps.filter { a -> a.branches.any { it.hasBuild } }.joinToString(", ") { it.name }.ifEmpty { "none" })
        return out
    }

    fun branch(app: RockstarApp): RockstarBranch? = app.branches.firstOrNull { it.isDefault && it.hasBuild } ?: app.branches.firstOrNull { it.hasBuild }

    fun deliverer(apps: List<RockstarApp>, title: RockstarTitle): RockstarApp? = apps
        .filter { RockstarTitles.byId(it.name) == title && !it.name.endsWith("_steam") && !it.name.endsWith("_epic") }
        .sortedBy { if (it.name == title.id) 0 else 1 }
        .firstOrNull { branch(it) != null }
}

object RockstarLibrary {
    private val generation = java.util.concurrent.atomic.AtomicInteger()
    private val running = java.util.concurrent.atomic.AtomicReference<Int?>(null)

    fun reset() {
        generation.incrementAndGet()
        StoresState.post {
            StoresState.library.remove(Store.ROCKSTAR)
            StoresState.shelves.remove(Store.ROCKSTAR)
        }
    }

    private fun post(gen: Int, block: () -> Unit) = StoresState.post { if (generation.get() == gen) block() }

    internal fun owned(account: RockstarAccountApps): List<RockstarTitle> =
        account.packages.filter { !it.featured }.flatMap { RockstarTitles.forName(it.title) }.distinct()

    internal fun owns(account: RockstarAccountApps, rosId: Int, id: String): Boolean =
        owned(account).any { it.rosTitleId == rosId } || account.packages.any { !it.featured && it.title == id }

    internal fun games(account: RockstarAccountApps): List<CatalogItem> = owned(account).mapNotNull { t ->
        RockstarApps.deliverer(account.apps, t)?.let { RockstarTitles.item(t, owned = true, extra = mapOf("app" to it.name, "appId" to it.id)) }
    }.sortedBy { it.title.lowercase() }

    fun sync(app: Context) {
        val gen = generation.get()
        if (running.getAndSet(gen) == gen) return
        try {
            val t = RockstarAuth.ticket(app)
            if (t == null) {
                post(gen) { StoresState.status.remove(Store.ROCKSTAR); StoresState.problems[Store.ROCKSTAR] = app.getString(R.string.stores_sync_sign_in) }
                return
            }
            if (StoresState.library[Store.ROCKSTAR].isNullOrEmpty()) post(gen) { StoresState.status[Store.ROCKSTAR] = app.getString(R.string.stores_sync_game_list) }
            val account = RockstarApps.fetch(t.ticket) { StoresState.logLine(it) }
            val games = games(account)
            owned(account).filter { o -> games.none { it.id == o.id } }.takeIf { it.isNotEmpty() }?.let { elsewhere ->
                StoresState.logLine("Rockstar: owned through another store, installs from there: " + elsewhere.joinToString(", ") { it.name })
            }
            post(gen) {
                StoresState.status.remove(Store.ROCKSTAR)
                StoresState.problems.remove(Store.ROCKSTAR)
                StoresState.library[Store.ROCKSTAR] = games
                StoresState.shelves[Store.ROCKSTAR]?.let { StoresState.shelves[Store.ROCKSTAR] = StoreFront.markOwned(it, games, RockstarCatalog::sameGame) }
            }
            StoresState.logLine("Rockstar: ${games.size} owned games")
        } catch (e: Exception) {
            StoresState.logLine("Rockstar: library sync failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(300)}")
            post(gen) { StoresState.status.remove(Store.ROCKSTAR); StoresState.problems[Store.ROCKSTAR] = app.getString(R.string.stores_sync_failed) }
        } finally {
            running.compareAndSet(gen, null)
        }
    }
}

internal object RockstarCatalog {
    fun all(): List<CatalogItem> = RockstarTitles.all.map { RockstarTitles.item(it) }

    fun shelves(): StoreShelves {
        val all = all()
        val newest = all.sortedByDescending { it.releaseDate }
        val headline = listOf("gta5_gen9", "rdr2", "rdr", "gta4", "gtasaunreal", "lanoire", "mp3")
        return StoreShelves(
            whatsNew = newest,
            trending = headline.mapNotNull { id -> all.firstOrNull { it.id == id } } + all.filter { it.id !in headline },
            art = all.mapNotNull { it.imageUrl },
        )
    }

    fun search(query: String): List<CatalogItem> {
        val q = StoreFront.titleKey(query)
        if (q.length < 2) return emptyList()
        return all().filter { StoreFront.titleKey(it.title).contains(q) }
    }

    fun sameGame(shelf: CatalogItem, owned: CatalogItem): Boolean = shelf.id == owned.id
}
