package com.droiddeck.launcher.stores.itch

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState
import org.json.JSONArray
import java.io.File
import java.security.MessageDigest

internal data class BrowseQuery(
    val sort: String = ItchBrowse.SORT_POPULAR,
    val price: String = ItchBrowse.PRICE_ANY,
    val platforms: Set<String> = emptySet(),
    val tags: List<String> = emptyList(),
) {
    val isDefault: Boolean get() = this == BrowseQuery()
}

internal class BrowsePage(val items: List<CatalogItem>, val code: Int, val cached: Boolean)

internal object ItchBrowse {
    const val SORT_POPULAR = "popular"
    const val SORT_NEW_POPULAR = "new-and-popular"
    const val SORT_TOP_SELLERS = "top-sellers"
    const val SORT_TOP_RATED = "top-rated"
    const val SORT_NEWEST = "newest"
    val sorts = listOf(SORT_POPULAR, SORT_NEW_POPULAR, SORT_TOP_SELLERS, SORT_TOP_RATED, SORT_NEWEST)

    const val PRICE_ANY = "any"
    const val PRICE_FREE = "free"
    const val PRICE_SALE = "on-sale"
    const val PRICE_PAID = "store"
    const val PRICE_UNDER_5 = "5-dollars-or-less"
    const val PRICE_UNDER_15 = "15-dollars-or-less"
    val prices = listOf(PRICE_ANY, PRICE_FREE, PRICE_SALE, PRICE_PAID, PRICE_UNDER_5, PRICE_UNDER_15)

    val platformNames = listOf("windows", "osx", "linux", "android", "ios", "web", "mobile-web")

    private const val BASE = "https://itch.io/games"
    private const val FRESH_MS = 3 * 60 * 60 * 1000L
    private const val GAP_MS = 1_200L
    private const val RETRY_MS = 8_000L
    private const val FOLDER = "itch-browse"

    @Volatile private var lastRequestAt = 0L
    private val lock = Any()

    private fun sortSegment(sort: String): String? = sort.takeIf { it != SORT_POPULAR && it in sorts }

    private fun priceSegment(price: String): String? = price.takeIf { it != PRICE_ANY && it in prices }

    fun path(query: BrowseQuery): String {
        val segments = listOfNotNull(sortSegment(query.sort), priceSegment(query.price)) +
            platformNames.filter { it in query.platforms }.map { "platform-$it" } +
            query.tags.map { "tag-" + tagSlug(it) }
        return if (segments.isEmpty()) BASE else BASE + "/" + segments.joinToString("/")
    }

    fun url(query: BrowseQuery, page: Int): String = path(query) + ".xml" + if (page > 1) "?page=$page" else ""

    fun tagSlug(tag: String): String = tag.trim().lowercase().replace(Regex("[^a-z0-9]+"), "-").trim('-')

    fun page(context: Context, query: BrowseQuery, page: Int, force: Boolean = false): BrowsePage {
        val app = context.applicationContext
        val url = url(query, page)
        if (!force) read(app, url)?.let { return BrowsePage(it, 200, true) }
        val (status, body) = fetch(url)
        val items = if (status in 200..299 && body != null) ItchShelves.parse(body) else emptyList()
        StoresState.logLine("itch.io browse: $url answered $status with ${items.size} games")
        if (items.isNotEmpty()) write(app, url, items)
        return BrowsePage(items, status, false)
    }

    private fun fetch(url: String): Pair<Int, String?> {
        synchronized(lock) {
            pause()
            var answer = ItchShelves.open(url)
            if (answer.first == 429) {
                StoresState.logLine("itch.io browse: 429, waiting before one more try")
                Thread.sleep(RETRY_MS)
                pause()
                answer = ItchShelves.open(url)
            }
            lastRequestAt = System.currentTimeMillis()
            return answer
        }
    }

    private fun pause() {
        val gap = GAP_MS - (System.currentTimeMillis() - lastRequestAt)
        if (gap > 0) Thread.sleep(gap)
    }

    private fun file(context: Context, url: String): File {
        val digest = MessageDigest.getInstance("SHA-1").digest(url.toByteArray()).joinToString("") { "%02x".format(it) }
        return File(File(context.cacheDir, FOLDER), "$digest.json")
    }

    private fun read(context: Context, url: String): List<CatalogItem>? {
        val file = file(context, url)
        if (!file.isFile || System.currentTimeMillis() - file.lastModified() > FRESH_MS) return null
        return runCatching { ItchCatalog.list(JSONArray(file.readText())) }.getOrNull()?.takeIf { it.isNotEmpty() }
    }

    private fun write(context: Context, url: String, items: List<CatalogItem>) {
        val file = file(context, url)
        runCatching {
            file.parentFile?.mkdirs()
            file.writeText(ItchCatalog.array(items).toString())
        }
    }
}

internal object ItchBrowseState {
    var query by mutableStateOf(BrowseQuery())
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

    val active: Boolean get() = !query.isDefault

    fun choose(context: Context, wanted: BrowseQuery) {
        if (wanted == query) return
        generation++
        query = wanted
        items = emptyList()
        ended = false
        failed = false
        loading = false
        next = 1
        if (active) more(context)
    }

    fun more(context: Context, retry: Boolean = false) {
        if (loading || ended || !active || (failed && !retry)) return
        loading = true
        failed = false
        val app = context.applicationContext
        val mine = generation
        val asked = query
        val page = next
        Thread({
            val answer = runCatching { ItchBrowse.page(app, asked, page) }.getOrNull()
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
        }, "itch-browse").start()
    }
}
