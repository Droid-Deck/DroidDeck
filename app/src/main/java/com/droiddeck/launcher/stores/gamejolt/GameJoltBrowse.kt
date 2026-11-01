package com.droiddeck.launcher.stores.gamejolt

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.StoresState
import java.net.URLEncoder

internal data class GameJoltQuery(
    val section: String = GameJoltBrowse.SECTION_FEATURED,
    val price: String = GameJoltBrowse.PRICE_ANY,
    val os: Set<String> = emptySet(),
    val browser: Set<String> = emptySet(),
    val maturity: Set<String> = emptySet(),
    val status: Set<String> = emptySet(),
    val partners: Boolean = false,
    val tags: List<String> = emptyList(),
)

internal object GameJoltBrowse {
    const val SECTION_FEATURED = "featured"
    const val SECTION_HOT = "hot"
    const val SECTION_BEST = "best"
    const val SECTION_NEW = "new"
    val sections = listOf(SECTION_FEATURED, SECTION_HOT, SECTION_BEST, SECTION_NEW)

    const val PRICE_ANY = "any"
    const val PRICE_FREE = "free"
    const val PRICE_SALE = "sale"
    const val PRICE_PAID = "paid"
    const val PRICE_UNDER_5 = "5-less"
    const val PRICE_UNDER_15 = "15-less"
    const val PRICE_UNDER_30 = "30-less"
    val prices = listOf(PRICE_ANY, PRICE_FREE, PRICE_SALE, PRICE_PAID, PRICE_UNDER_5, PRICE_UNDER_15, PRICE_UNDER_30)

    val systems = listOf("windows", "mac", "linux", "other")
    val browsers = listOf("html", "flash", "unity")
    val maturities = listOf("everyone", "teen", "adult")
    val statuses = listOf("complete", "wip", "devlog")

    fun tagSlug(tag: String): String = tag.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun queryString(query: GameJoltQuery, page: Int): String {
        val parts = ArrayList<String>()
        if (query.section != SECTION_FEATURED && query.section in sections) parts.add("section=${query.section}")
        query.tags.forEach { parts.add("tag=" + enc(tagSlug(it))) }
        if (query.price != PRICE_ANY && query.price in prices) parts.add("f_price=${query.price}")
        systems.filter { it in query.os }.forEach { parts.add("f_os%5B%5D=$it") }
        browsers.filter { it in query.browser }.forEach { parts.add("f_browser%5B%5D=$it") }
        maturities.filter { it in query.maturity }.forEach { parts.add("f_maturity%5B%5D=$it") }
        statuses.filter { it in query.status }.forEach { parts.add("f_status%5B%5D=$it") }
        if (query.partners) parts.add("f_partners%5B%5D=partners")
        if (page > 1) parts.add("page=$page")
        return parts.joinToString("&")
    }

    private fun enc(value: String): String = URLEncoder.encode(value, "UTF-8")

    class Page(val items: List<CatalogItem>, val code: Int)

    fun effective(query: GameJoltQuery, showMature: Boolean): GameJoltQuery {
        if (showMature) return query
        val allowed = query.maturity - "adult"
        return query.copy(maturity = allowed.ifEmpty { setOf("everyone", "teen") })
    }

    private val diagnosed = java.util.concurrent.atomic.AtomicBoolean(false)

    fun page(context: Context, query: GameJoltQuery, page: Int, showMature: Boolean): Page {
        val cookies = GameJoltAuth.cookies(context)
        val asked = effective(query, showMature)
        var answer = fetch(queryString(asked, page), cookies)
        if (!answer.ok && asked.maturity.isNotEmpty()) {
            if (diagnosed.compareAndSet(false, true)) diagnose(cookies)
            answer = fetch(queryString(asked.copy(maturity = emptySet()), page), cookies)
        }
        val items = if (answer.ok) GameJoltCatalog.items(answer.payload?.optJSONArray("games")) else emptyList()
        return Page(items, answer.code)
    }

    private fun fetch(qs: String, cookies: String): GameJoltAnswer {
        val answer = GameJoltApi.browse(qs, cookies)
        val games = if (answer.ok) answer.payload?.optJSONArray("games") else null
        StoresState.logLine("Game Jolt browse: ?$qs answered ${answer.code} with ${games?.length() ?: 0} games")
        return answer
    }

    private fun diagnose(cookies: String) {
        val variants = listOf(
            "maturity=everyone,teen", "f_maturity=everyone,teen", "f_maturity[]=everyone", "f_maturity%5B%5D=everyone",
            "f_maturity[]=everyone&f_maturity[]=teen", "f_maturity%5B%5D=teen", "f_status%5B%5D=complete", "f_os%5B%5D=windows", "f_price=free", "",
        )
        for (variant in variants) {
            val answer = GameJoltApi.browse(variant, cookies)
            StoresState.logLine("Game Jolt browse check: ?$variant answered ${answer.code} with ${answer.payload?.optJSONArray("games")?.length() ?: 0} games")
        }
    }
}

internal object GameJoltBrowseState {
    var query by mutableStateOf(GameJoltQuery())
        private set
    var items by mutableStateOf<List<CatalogItem>>(emptyList())
        private set
    var loading by mutableStateOf(false)
        private set
    var ended by mutableStateOf(false)
        private set
    var failed by mutableStateOf(false)
        private set

    private var next = 1
    private var generation = 0
    private var loadedMature: Boolean? = null

    fun sync(context: Context, showMature: Boolean) {
        val before = loadedMature ?: return
        if (before != showMature) more(context)
    }

    private fun reset() {
        generation++
        items = emptyList()
        ended = false
        failed = false
        loading = false
        next = 1
    }

    fun choose(context: Context, wanted: GameJoltQuery) {
        if (wanted == query) return
        reset()
        query = wanted
        more(context)
    }

    fun more(context: Context, retry: Boolean = false) {
        val app = context.applicationContext
        val mature = SessionPrefs.storesShowMature(app)
        if (loadedMature != null && loadedMature != mature) reset()
        if (loading || ended || (failed && !retry)) return
        loading = true
        failed = false
        val mine = generation
        val asked = query
        val page = next
        loadedMature = mature
        Thread({
            val answer = runCatching { GameJoltBrowse.page(app, asked, page, mature) }.getOrNull()
            StoresState.post {
                if (mine != generation) return@post
                loading = false
                if (answer == null || answer.code !in 200..299) {
                    failed = true
                    return@post
                }
                if (answer.items.isEmpty()) {
                    ended = true
                    return@post
                }
                val known = items.mapTo(HashSet()) { it.id }
                val fresh = answer.items.filter { known.add(it.id) }
                if (fresh.isEmpty()) ended = true else {
                    items = items + fresh
                    next = page + 1
                }
            }
        }, "gamejolt-browse").start()
    }
}
