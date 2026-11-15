package com.droiddeck.launcher.stores.ea

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreFront
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.cleanStoreText
import org.json.JSONObject
import java.time.Instant
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** EA's PC catalog, read from the game pages ea.com renders for the EA app's games. */
object EaStore {
    private const val SITE = "https://www.ea.com"
    private const val CATALOG = "$SITE/games/library/pc-download"
    private const val PAGE_SIZE = 12
    private const val MAX_PAGES = 20
    private const val SHELF = 24
    private val NEXT_DATA = Regex("""<script id="__NEXT_DATA__" type="application/json">(.*?)</script>""", RegexOption.DOT_MATCHES_ALL)

    private var front: StoreFront? = null

    private fun front(context: Context): StoreFront = synchronized(this) {
        front ?: StoreFront(Store.EA, { EaPrefs.get(context) }, { fetchAll().let { StoreFront.Catalog(shelves(it), it) } }, key = "store_front_catalog").also { front = it }
    }

    fun cached(context: Context): StoreShelves? = front(context).cached()

    fun featured(context: Context, force: Boolean): StoreShelves? = front(context).shelves(force)

    fun search(context: Context, query: String): List<CatalogItem> {
        val q = StoreFront.titleKey(query)
        if (q.length < 2) return emptyList()
        return front(context).everything().filter { StoreFront.titleKey(it.title).contains(q) }
    }

    /** Whether a store card and a library entry are the same game. */
    fun sameGame(shelf: CatalogItem, owned: CatalogItem): Boolean {
        val slug = shelf.extra["slug"].orEmpty()
        if (slug.isNotEmpty() && slug == owned.extra["slug"]) return true
        val shelfKey = StoreFront.titleKey(shelf.title).ifEmpty { return false }
        return listOf(owned.title, owned.extra["productName"].orEmpty()).any { StoreFront.titleKey(it) == shelfKey }
    }

    /**
     * The device's currency. ea.com's pages are cached with whichever region asked first, so one page can carry
     * roubles and the next dollars; a price in another currency is left off rather than shown wrong.
     */
    private fun localCurrency(): String =
        runCatching { java.util.Currency.getInstance(java.util.Locale.getDefault()).currencyCode }.getOrNull() ?: "USD"

    internal fun parsePage(html: String, currency: String = localCurrency()): Pair<List<CatalogItem>, Int> {
        val data = NEXT_DATA.find(html)?.groupValues?.get(1) ?: return emptyList<CatalogItem>() to 0
        val props = JSONObject(data).optJSONObject("props")?.optJSONObject("pageProps") ?: return emptyList<CatalogItem>() to 0
        val games = props.optJSONArray("games") ?: return emptyList<CatalogItem>() to 0
        val items = (0 until games.length()).mapNotNull { games.optJSONObject(it) }.mapNotNull { item(it, currency) }
        return items to props.optInt("totalItems", items.size)
    }

    internal fun item(g: JSONObject, currency: String): CatalogItem? {
        fun s(o: JSONObject?, k: String) = o?.optString(k)?.takeIf { it.isNotBlank() && it != "null" }
        val slug = s(g, "slug") ?: return null
        val title = s(g, "title")?.replace("\n", " ")?.trim() ?: return null
        val art = g.optJSONObject("packArt")
        val price = g.optJSONObject("price")
        val total = s(price, "displayTotal").orEmpty()
        val now = s(price, "displayTotalWithDiscount") ?: total
        val discount = price?.optInt("discountPercentage") ?: 0
        val free = g.optBoolean("isFreeToPlay") || now.filter { it.isDigit() }.trimStart('0').isEmpty() && now.isNotEmpty()
        val shown = !free && s(price, "currency").equals(currency, true)
        val genres = g.optJSONArray("genres")
        val franchise = s(g, "franchiseSlug")
        return CatalogItem(
            store = Store.EA, id = "ea-store:$slug", title = title,
            imageUrl = s(art, "ar16X9") ?: s(art, "ar2X1"), tallImageUrl = s(art, "ar2X3") ?: s(art, "ar3X4"),
            tags = (0 until (genres?.length() ?: 0)).mapNotNull { genres!!.optJSONObject(it)?.optString("name")?.takeIf(String::isNotBlank) }.take(3).joinToString(", "),
            isFree = free, hasPrice = shown, finalPrice = if (shown) now else "",
            originalPrice = if (shown && discount > 0) total else "", discountPercent = discount.coerceAtLeast(0),
            storeUrl = if (franchise != null) "$SITE/games/$franchise/$slug" else "$SITE/games/$slug",
            publisher = "Electronic Arts", releaseDate = s(g, "releaseDate")?.take(10).orEmpty(),
            description = cleanStoreText(s(g, "shortDescription")),
            extra = buildMap { put("slug", slug); s(g, "tier")?.let { put("tier", it) } },
        )
    }

    private fun page(n: Int): Pair<List<CatalogItem>, Int>? =
        StoreNet.get(if (n == 1) CATALOG else "$CATALOG?page=$n", headers = mapOf("Accept" to "text/html"))?.let(::parsePage)

    /** Every page of the catalog; a page that fails fails the whole refresh, so a partial list never replaces a good one. */
    private fun fetchAll(): List<CatalogItem> {
        val (first, total) = page(1) ?: throw java.io.IOException("ea.com catalog page 1 failed")
        val pages = ((total + PAGE_SIZE - 1) / PAGE_SIZE).coerceIn(1, MAX_PAGES)
        val rest = java.util.concurrent.ConcurrentHashMap<Int, List<CatalogItem>>()
        val pool = Executors.newFixedThreadPool(4)
        try {
            for (n in 2..pages) pool.execute { page(n)?.first?.let { rest[n] = it } }
        } finally {
            pool.shutdown()
            if (!pool.awaitTermination(1, TimeUnit.MINUTES)) pool.shutdownNow()
        }
        (2..pages).firstOrNull { !rest.containsKey(it) }?.let { throw java.io.IOException("ea.com catalog page $it failed") }
        return (first + (2..pages).flatMap { rest.getValue(it) }).distinctBy { it.id }
    }

    internal fun shelves(all: List<CatalogItem>, now: Instant = Instant.now()): StoreShelves {
        val today = now.toString().take(10)
        val released = all.filter { it.releaseDate.isNotEmpty() && it.releaseDate <= today }
        val headline = released.filter { it.extra["tier"] == "Tier1" || it.extra["tier"] == "Tier2" }
        return StoreShelves(
            whatsNew = released.sortedByDescending { it.releaseDate }.take(SHELF),
            deals = released.filter { it.discountPercent > 0 }.sortedByDescending { it.discountPercent }.take(SHELF),
            free = released.filter { it.isFree }.take(SHELF),
            trending = (headline + released).distinctBy { it.id }.take(SHELF),
            art = all.mapNotNull { it.imageUrl }.take(SHELF),
        )
    }
}
