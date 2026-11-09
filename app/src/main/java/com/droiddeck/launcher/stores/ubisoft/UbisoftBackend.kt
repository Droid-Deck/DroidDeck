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
        StoresState.logLine("Ubisoft: downloads are not in this build yet")
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
            val games = all.distinctBy { it.id }.sortedBy { it.title.lowercase() }
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
