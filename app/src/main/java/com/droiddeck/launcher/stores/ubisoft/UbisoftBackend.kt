package com.droiddeck.launcher.stores.ubisoft

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
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.StoreLoginActivity
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.WebCookies
import org.json.JSONObject
import java.util.concurrent.atomic.AtomicBoolean

object UbisoftBackend : StoreBackend {
    override val store: Store = Store.UBISOFT

    init {
        LoginFlows.ubisoftFlow = { ua -> UbisoftLogin(ua) }
    }

    override fun signIn(context: Context) {
        context.startActivity(
            Intent(context, StoreLoginActivity::class.java)
                .putExtra(StoreLoginActivity.EXTRA_STORE, store.id)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    override fun signOut(context: Context) {
        UbisoftAuth.signOut(context.applicationContext)
        UbisoftLibrary.reset()
        runCatching { WebCookies.expire(UbisoftAuth.SIGN_IN_URLS, ".ubisoft.com", listOf("/")) {} }
    }

    override fun syncLibrary(context: Context, force: Boolean) {
        val app = context.applicationContext
        Thread({ UbisoftLibrary.sync(app, force) }, "ubisoft-sync").start()
    }

    override fun loadShelves(context: Context, force: Boolean) {}

    override fun install(context: Context, item: CatalogItem, root: java.io.File) {
        val app = context.applicationContext
        if (!UbisoftNative.ready()) {
            StoresState.logLine(UbisoftNative.ENGINE_MISSING)
            StoresState.post { StoresState.problems[store] = UbisoftNative.ENGINE_MISSING }
            return
        }
        val folder = StoreInstallRoot.folderFor(app, store, item.id, item.title, root)
        val entry = DownloadEntry(store, item.id, item.title, cover = item.tallImageUrl ?: item.imageUrl,
            bytesTotal = item.sizeBytes, location = StoreInstallRoot.labelFor(app, folder))
        DownloadQueue.enqueue(app, entry) { UbisoftInstaller(app, item, folder) }
    }

    override fun uninstall(context: Context, game: InstalledStoreGame) {}
}

internal class UbisoftLogin(override val userAgent: String) : StoreLoginActivity.Flow {
    override val startUrl: String get() = UbisoftAuth.loginUrl
    override val readsPage: Boolean get() = true
    override val pageScript: String get() = UbisoftAuth.PAGE_SCRIPT
    override fun pageReady(result: String): Boolean = result.contains("ticket", ignoreCase = true)
    override fun isRedirect(url: String): Boolean = UbisoftAuth.isLoggedInPage(url)

    override fun forgetSignIn(done: () -> Unit) {
        runCatching { WebCookies.expire(UbisoftAuth.SIGN_IN_URLS, ".ubisoft.com", listOf("/")) { done() } }.onFailure { done() }
    }

    override fun finish(activity: StoreLoginActivity, view: WebView, url: String): String? = try {
        UbisoftAuth.completeWebSignIn(activity, url)?.let {
            Log.w("UbisoftLogin", "sign-in incomplete: $it")
            activity.getString(R.string.stores_login_error_verification)
        }
    } catch (e: Exception) {
        Log.w("UbisoftLogin", "sign-in failed: ${e.javaClass.simpleName}")
        activity.getString(R.string.stores_login_error_generic)
    }
}

object UbisoftLibrary {
    private const val GRAPHQL = "https://public-ubiservices.ubi.com/v1/profiles/me/uplay/graphql"
    private const val PAGE = 50
    private const val QUERY = "query OwnedGames(\$limit: Int, \$offset: Int) { viewer { games(limit: \$limit, offset: \$offset) { totalCount nodes { id spaceId name coverUrl backgroundUrl bannerUrl releaseDate } } } }"
    private val running = AtomicBoolean(false)

    fun reset() {}

    internal fun parsePage(body: String): Pair<List<CatalogItem>, Int> {
        val o = JSONObject(body)
        o.optJSONArray("errors")?.optJSONObject(0)?.let { throw IllegalStateException(it.optString("message")) }
        val games = o.optJSONObject("data")?.optJSONObject("viewer")?.optJSONObject("games") ?: return emptyList<CatalogItem>() to 0
        val nodes = games.optJSONArray("nodes")
        val items = (0 until (nodes?.length() ?: 0)).mapNotNull { nodes!!.optJSONObject(it) }.mapNotNull { n ->
            val space = n.optString("spaceId").takeIf { it.isNotEmpty() } ?: return@mapNotNull null
            fun s(k: String) = n.optString(k).takeIf { it.isNotEmpty() && it != "null" }
            CatalogItem(
                Store.UBISOFT, space, n.optString("name").trim(), s("backgroundUrl") ?: s("bannerUrl"),
                tallImageUrl = s("coverUrl"), releaseDate = s("releaseDate")?.take(10).orEmpty(), owned = true,
                extra = mapOf("spaceId" to space, "gameId" to n.optString("id")),
            )
        }
        return items to games.optInt("totalCount", items.size)
    }

    /** Base games with a manifest, by UbiServices space; add-ons and bundles share spaces with their game. */
    internal fun ownedBySpace(owned: JSONObject?): Map<String, JSONObject> {
        val list = owned?.optJSONArray("games") ?: return emptyMap()
        return (0 until list.length()).mapNotNull { list.optJSONObject(it) }
            .filter { it.optString("spaceId").isNotEmpty() && it.optString("manifest").isNotEmpty() && it.optInt("productType") == 0 && it.optBoolean("owned", true) }
            .groupBy { it.optString("spaceId") }.mapValues { it.value.first() }
    }

    private val RELATIVE = Regex("""relative:\s*['"]?([^'"\r\n]+?)['"]?\s*$""", RegexOption.MULTILINE)

    private val NAME = Regex("""^\s{1,4}name:\s*['"]?(.+?)['"]?\s*$""", RegexOption.MULTILINE)

    /** The product's name from its configuration, following a localisation key such as `l1` to its default text. */
    internal fun title(configuration: String): String? {
        val raw = NAME.find(configuration)?.groupValues?.get(1)?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (!Regex("""l\d+""").matches(raw)) return raw
        val local = configuration.indexOf("localizations:").takeIf { it >= 0 } ?: return raw
        return Regex("""^\s+${raw}:\s*['"]?(.+?)['"]?\s*$""", RegexOption.MULTILINE).find(configuration, local)?.groupValues?.get(1)?.trim() ?: raw
    }

    /** The first launch path under `start_game` in a product's configuration. */
    internal fun executable(configuration: String): String? {
        val start = configuration.indexOf("start_game").takeIf { it >= 0 } ?: return null
        return RELATIVE.find(configuration, start)?.groupValues?.get(1)?.trim()?.takeIf { it.endsWith(".exe", true) }
    }

    fun sync(app: Context, force: Boolean) {
        if (!running.compareAndSet(false, true)) return
        try {
            val s = UbisoftAuth.current(app)
            if (s == null) {
                StoresState.post { StoresState.status.remove(Store.UBISOFT); StoresState.problems[Store.UBISOFT] = app.getString(R.string.stores_sync_sign_in) }
                return
            }
            if (StoresState.library[Store.UBISOFT].isNullOrEmpty()) StoresState.post { StoresState.status[Store.UBISOFT] = app.getString(R.string.stores_sync_game_list) }
            val all = ArrayList<CatalogItem>()
            var offset = 0
            while (true) {
                val body = JSONObject().put("operationName", "OwnedGames").put("query", QUERY)
                    .put("variables", JSONObject().put("limit", PAGE).put("offset", offset)).toString()
                val reply = UbisoftAuth.request(GRAPHQL, "POST", "Ubi_v1 t=${s.ticket}", s.sessionId, body)
                if (reply.status == 401) StoresState.markSignInExpired(Store.UBISOFT)
                if (!reply.ok) throw IllegalStateException("library ${reply.status}")
                val (items, total) = parsePage(reply.body)
                all += items
                offset += PAGE
                if (items.isEmpty() || offset >= total) break
            }
            val owned = UbisoftNative.call("owned", JSONObject().put("ticket", s.ticket).put("sessionId", s.sessionId))
            owned?.optString("error")?.takeIf { it.isNotEmpty() }?.let { StoresState.logLine("Ubisoft: ownership: $it") }
            val bySpace = ownedBySpace(owned)
            fun download(o: JSONObject) = listOfNotNull(
                "productId" to o.optLong("productId").toString(),
                o.optString("manifest").takeIf { it.isNotEmpty() }?.let { "manifest" to it },
                executable(o.optString("configuration"))?.let { "exe" to it },
            )
            val listed = all.distinctBy { it.id }
            val matched = listed.map { g -> bySpace[g.id]?.let { g.copy(extra = g.extra + download(it)) } ?: g }
            val unlisted = bySpace.filterKeys { space -> listed.none { it.id == space } }.mapNotNull { (space, o) ->
                val title = title(o.optString("configuration")) ?: return@mapNotNull null
                CatalogItem(Store.UBISOFT, space, title, null, owned = true, extra = mapOf("spaceId" to space) + download(o))
            }
            val games = (matched + unlisted).sortedBy { it.title.lowercase() }
            StoresState.logLine("Ubisoft: ${bySpace.size} downloadable products; " +
                games.filter { it.extra.containsKey("productId") }.joinToString(", ") { "${it.title}=${it.extra["productId"]}" })
            StoresState.post {
                StoresState.status.remove(Store.UBISOFT)
                StoresState.problems.remove(Store.UBISOFT)
                StoresState.library[Store.UBISOFT] = games
            }
            StoresState.logLine("Ubisoft: ${games.size} owned games")
        } catch (e: Exception) {
            StoresState.logLine("Ubisoft: library sync failed: ${e.javaClass.simpleName} ${e.message.orEmpty().take(200)}")
            StoresState.post { StoresState.status.remove(Store.UBISOFT); StoresState.problems[Store.UBISOFT] = app.getString(R.string.stores_sync_failed) }
        } finally {
            running.set(false)
        }
    }
}
