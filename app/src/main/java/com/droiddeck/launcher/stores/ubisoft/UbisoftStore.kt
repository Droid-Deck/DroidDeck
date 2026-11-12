package com.droiddeck.launcher.stores.ubisoft

import android.content.Context
import android.net.Uri
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreFront
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.cleanStoreText
import com.droiddeck.launcher.stores.matureByAge
import org.json.JSONArray
import org.json.JSONObject
import java.text.NumberFormat
import java.time.Instant
import java.util.Currency
import java.util.Locale

/** Ubisoft's PC catalog, from the product search the Ubisoft Store website uses. */
object UbisoftStore {
    /** The Ubisoft Store website's own read-only search key, sent by every store page. */
    private const val APP = "XELY3U4LOD"
    private const val KEY = "5638539fd9edb8f2c6b024b49ec375bd"
    private const val SHELF = 24
    private val RATING_AGE = mapOf("esrb-m" to 17, "esrb-ao" to 18, "pegi-18" to 18, "pegi-16" to 16, "usk-18" to 18)

    /** The store sites with an English catalog: site, index locale, currency. */
    private val SITES = mapOf(
        "US" to Triple("us", "en_US", "USD"), "GB" to Triple("uk", "en_GB", "GBP"),
        "CA" to Triple("ca", "en_CA", "CAD"), "AU" to Triple("anz", "en_AU", "AUD"), "NZ" to Triple("anz", "en_AU", "NZD"),
    )

    private fun site() = SITES[Locale.getDefault().country.uppercase(Locale.ROOT)] ?: SITES.getValue("US")

    private fun index(order: String) = site().let { (s, l, _) -> "production__${s}_ubisoft__products__${l}__$order" }

    private var front: StoreFront? = null

    private fun front(context: Context): StoreFront = synchronized(this) {
        front ?: StoreFront(Store.UBISOFT, { context.applicationContext.getSharedPreferences("stores.ubisoft", Context.MODE_PRIVATE) }, ::fetch).also { front = it }
    }

    fun cached(context: Context): StoreShelves? = front(context).cached()

    fun featured(context: Context, force: Boolean): StoreShelves? = front(context).shelves(force)

    fun search(query: String): List<CatalogItem> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        return query("best_sellers", 40, query = term)
    }

    /** Whether a store card and a library entry are the same game. */
    fun sameGame(shelf: CatalogItem, owned: CatalogItem): Boolean {
        val product = shelf.extra["productId"].orEmpty()
        if (product.isNotEmpty() && product == owned.extra["productId"]) return true
        val key = StoreFront.titleKey(owned.title)
        return key.isNotEmpty() && key == baseTitle(shelf.title)
    }

    /** A product title without its edition, so "Far Cry® 6 - Gold Edition" is "far cry 6". */
    internal fun baseTitle(title: String): String = StoreFront.titleKey(title.substringBefore(" - "))

    private fun query(order: String, hits: Int, query: String = "", filters: String = "", facets: List<String> = emptyList()): List<CatalogItem> {
        val facetFilters = JSONArray().put("product_type:Games").put("Platform:PC (Digital)").apply { facets.forEach { put(it) } }
        val url = Uri.parse("https://$APP-dsn.algolia.net/1/indexes/${index(order)}").buildUpon()
            .appendQueryParameter("query", query)
            .appendQueryParameter("hitsPerPage", (hits * 2).toString())
            .appendQueryParameter("facetFilters", facetFilters.toString())
            .apply { if (filters.isNotEmpty()) appendQueryParameter("filters", filters) }
            .build().toString()
        val body = StoreNet.get(url, headers = mapOf("X-Algolia-Application-Id" to APP, "X-Algolia-API-Key" to KEY)) ?: return emptyList()
        return parse(JSONObject(body), site().third).take(hits)
    }

    internal fun parse(answer: JSONObject, currency: String, now: Instant = Instant.now()): List<CatalogItem> {
        val hits = answer.optJSONArray("hits") ?: return emptyList()
        return (0 until hits.length()).mapNotNull { hits.optJSONObject(it) }.mapNotNull { item(it, currency, now) }
            .distinctBy { baseTitle(it.title) }
    }

    private fun money(amount: Double, currency: String): String = NumberFormat.getCurrencyInstance(Locale.US).apply {
        this.currency = Currency.getInstance(currency)
    }.format(amount)

    internal fun item(h: JSONObject, currency: String, now: Instant): CatalogItem? {
        fun s(k: String) = h.optString(k).takeIf { it.isNotBlank() && it != "null" }
        val id = s("objectID") ?: s("id") ?: return null
        val title = s("title") ?: s("name") ?: return null
        if (s("Free_offer") == "demo") return null
        val price = h.optJSONObject("price")?.optDouble(currency)?.takeUnless { it.isNaN() }
        val base = h.optJSONObject("default_price")?.optDouble(currency)?.takeUnless { it.isNaN() }
        val discount = s("promotion_percentage")?.toDoubleOrNull()?.toInt() ?: 0
        val released = s("release_date")?.let { runCatching { Instant.parse(it) }.getOrNull() }
        if (released != null && released.isAfter(now) && s("preorder") != "true") return null
        val free = s("Free_offer") == "freeplay" || price == 0.0
        val image = s("image_link")?.substringBefore("?")
        return CatalogItem(
            store = Store.UBISOFT, id = "ubisoft-store:$id", title = title.trim(),
            imageUrl = image, tallImageUrl = image, tags = listOfNotNull(s("Genre"), s("Edition")).joinToString(", "),
            isFree = free, hasPrice = price != null, finalPrice = price?.let { money(it, currency) }.orEmpty(),
            originalPrice = if (discount > 0 && base != null) money(base, currency) else "", discountPercent = if (discount > 0 && base != null) discount else 0,
            storeUrl = s("link") ?: s("linkWeb").orEmpty(), publisher = "Ubisoft", releaseDate = released?.toString()?.take(10).orEmpty(),
            description = cleanStoreText(s("longDescription")),
            mature = s("adult").equals("TRUE", true) || matureByAge(listOfNotNull(s("Rating")?.let(RATING_AGE::get))),
            extra = buildMap {
                s("productLauncherIDString")?.let { put("productId", it) }
                if (s("partOfUbisoftPlus").equals("true", true)) put("ubisoftPlus", "true")
            },
        )
    }

    private fun fetch(): StoreShelves {
        val trending = query("best_sellers", SHELF)
        val newest = query("release_date", SHELF)
        val deals = query("best_sellers", SHELF, filters = "promotion_percentage > 0")
        val free = query("best_sellers", SHELF, facets = listOf("Free_offer:freeplay"))
        return StoreShelves(whatsNew = newest, deals = deals, free = free, trending = trending, art = trending.mapNotNull { it.imageUrl })
    }
}
