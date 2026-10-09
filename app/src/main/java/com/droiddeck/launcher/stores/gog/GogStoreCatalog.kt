package com.droiddeck.launcher.stores.gog

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.util.Calendar
import java.util.Currency
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/**
 * GOG's public catalog - the same unauthenticated service the gog.com store page uses:
 * `catalog.gog.com/v1/catalog?order=desc:trending&productType=in:game,pack&...` with `discounted`,
 * `price=between:0,0` and `query=like:` filters. Products carry pre-formatted prices and a
 * discount string; Windows-only titles are kept. Cached in-process (shelves 30 min, searches
 * 5 min) and the last good shelves mirrored to prefs so the tab paints offline too.
 */
object GogStoreCatalog {
    private const val TAG = "GogStore"
    private const val CATALOG = "https://catalog.gog.com/v1/catalog"
    private const val FEATURED_TTL_MS = 30 * 60 * 1000L
    private const val SEARCH_TTL_MS = 5 * 60 * 1000L
    // v2: entries carry the mature mark; an older cache would show everything until it aged out.
    private const val KEY_FEATURED = "store_featured_v2"

    private val supportedCurrencies = setOf(
        "USD", "EUR", "GBP", "AUD", "CAD", "CHF", "DKK", "NOK", "PLN", "SEK", "BRL", "CNY",
        "HKD", "ILS", "JPY", "KRW", "MXN", "NZD", "SGD", "TRY", "UAH", "ZAR", "INR", "RUB",
    )

    private class Cached<T>(val value: T, val at: Long)
    private var featuredCache: Cached<StoreShelves>? = null
    private val searchCache = ConcurrentHashMap<String, Cached<List<CatalogItem>>>()

    private fun country(): String = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"

    private fun currency(): String {
        val cc = runCatching { Currency.getInstance(Locale("", country())).currencyCode }.getOrNull()
        return if (cc != null && cc in supportedCurrencies) cc else "USD"
    }

    private fun baseQuery(limit: Int, order: String, extra: String = ""): String =
        "$CATALOG?limit=$limit&order=$order&productType=in:game,pack&page=1&countryCode=${country()}&locale=en-US&currencyCode=${currency()}$extra"

    /** GET the catalog; on a refusal of the locale's currency, retry as US / USD. */
    private fun fetchProducts(url: String): List<CatalogItem> {
        var body = StoreNet.get(url)
        if (body == null && !url.contains("countryCode=US&locale=en-US&currencyCode=USD")) {
            body = StoreNet.get(url.replace(Regex("countryCode=[A-Z]{2}"), "countryCode=US").replace(Regex("currencyCode=[A-Z]{3}"), "currencyCode=USD"))
        }
        return if (body == null) emptyList() else parseProducts(body)
    }

    /** The Store tab's shelves. Null only when every feed failed and nothing is cached. Blocking. */
    fun featured(context: Context, force: Boolean = false): StoreShelves? {
        val now = System.currentTimeMillis()
        featuredCache?.let { if (!force && now - it.at < FEATURED_TTL_MS) return it.value }
        val trending = fetchProducts(baseQuery(24, "desc:trending"))
        val newest = fetchProducts(baseQuery(40, "desc:releaseDate")).filter { !isFuture(it.releaseDate) }.take(24)
        val deals = fetchProducts(baseQuery(24, "desc:discount", "&discounted=eq:true"))
        val free = fetchProducts(baseQuery(24, "desc:trending", "&price=between:0,0"))
        val result = StoreShelves(whatsNew = newest, deals = deals, free = free, trending = trending)
        if (!result.isEmpty) {
            featuredCache = Cached(result, now)
            persist(context, result)
            Log.i(TAG, "shelves: trending=${trending.size} new=${newest.size} deals=${deals.size} free=${free.size}")
            return result
        }
        Log.w(TAG, "every catalog feed came back empty; using the on-disk mirror")
        return loadPersisted(context)
    }

    fun cachedFeatured(context: Context): StoreShelves? = featuredCache?.value ?: loadPersisted(context)

    fun search(query: String): List<CatalogItem> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        val key = term.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.at < SEARCH_TTL_MS) return it.value }
        val results = fetchProducts(baseQuery(40, "desc:score", "&query=like:" + URLEncoder.encode(term, "UTF-8")))
        searchCache[key] = Cached(results, now)
        return results
    }

    private fun parseProducts(body: String): List<CatalogItem> = try {
        val arr = JSONObject(body).optJSONArray("products") ?: JSONArray()
        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { p -> parseProduct(p)?.let { out.add(it) } }
        out.distinctBy { it.id }
    } catch (e: Exception) {
        Log.w(TAG, "catalog parse failed: ${e.message}")
        emptyList()
    }

    fun parseProduct(p: JSONObject): CatalogItem? {
        val id = p.optString("id", "")
        val title = p.optString("title", "")
        if (id.isEmpty() || title.isEmpty()) return null
        // Windows only: the app cannot run anything else.
        val os = p.optJSONArray("operatingSystems")
        if (os != null && os.length() > 0) {
            var windows = false
            for (i in 0 until os.length()) if (os.optString(i).equals("windows", true)) windows = true
            if (!windows) return null
        }
        val price = p.optJSONObject("price")
        val finalAmount = price?.optJSONObject("finalMoney")?.optString("amount")?.toDoubleOrNull()
        val finalStr = price?.optString("final", "").orEmpty()
        val baseStr = price?.optString("base", "").orEmpty()
        val discount = price?.optString("discount", "").orEmpty().filter { it.isDigit() }.toIntOrNull() ?: 0
        val isFree = price != null && (finalAmount == 0.0 || finalStr == "$0.00" || finalStr == "0")
        val genres = p.optJSONArray("genres")
        val tags = buildList {
            if (genres != null) for (i in 0 until minOf(3, genres.length())) genres.optJSONObject(i)?.optString("name")?.takeIf { it.isNotBlank() }?.let { add(it) }
        }.joinToString(", ")
        val slug = p.optString("slug", "")
        val storeLink = p.optString("storeLink", "").ifBlank { if (slug.isNotBlank()) "https://www.gog.com/en/game/$slug" else "" }
        val mature = isMature(p)
        return CatalogItem(
            store = Store.GOG, id = id, title = title,
            imageUrl = p.optString("coverHorizontal", "").ifBlank { null },
            tallImageUrl = p.optString("coverVertical", "").ifBlank { null },
            tags = tags, isFree = isFree, hasPrice = price != null && finalStr.isNotBlank(),
            finalPrice = finalStr, originalPrice = if (discount > 0) baseStr else "",
            discountPercent = if (discount > 0 && baseStr.isNotBlank()) discount else 0,
            storeUrl = storeLink, developer = p.optJSONArray("developers")?.optString(0, "").orEmpty(),
            releaseDate = p.optString("releaseDate", ""),
            extra = buildMap { if (slug.isNotBlank()) put("slug", slug) },
            mature = mature,
        )
    }

    /**
     * GOG's own data: `ratings[]` carries each system's age (`esrbRating` 17, `pegiRating` 18,
     * `uskRating` 18, `gogRating` 18, ...) and `tags[]` its content tags (`mature`, `nsfw`,
     * `sexual-content`, `nudity`). The catalog has no filter for either, so this is client-side.
     */
    fun isMature(p: JSONObject): Boolean {
        val ages = ArrayList<Int>()
        p.optJSONArray("ratings")?.let { r -> for (i in 0 until r.length()) r.optJSONObject(i)?.optString("ageRating")?.toIntOrNull()?.let { ages.add(it) } }
        val tags = ArrayList<String>()
        p.optJSONArray("tags")?.let { t -> for (i in 0 until t.length()) t.optJSONObject(i)?.let { o -> tags.add(o.optString("slug")); tags.add(o.optString("name")) } }
        return com.droiddeck.launcher.stores.matureByAge(ages) || com.droiddeck.launcher.stores.matureByTags(tags)
    }

    /** GOG returns protocol-relative `//images…` URLs in a few places. */
    @JvmStatic
    fun absolutize(url: String?): String {
        if (url.isNullOrBlank()) return ""
        return if (url.startsWith("//")) "https:$url" else url
    }

    /** "YYYY.MM.DD" later than today: a pre-order, kept out of What's new. */
    private fun isFuture(date: String): Boolean {
        if (date.length < 10) return false
        val parts = date.substring(0, 10).split('.', '-')
        if (parts.size != 3) return false
        val y = parts[0].toIntOrNull() ?: return false
        val m = parts[1].toIntOrNull() ?: return false
        val d = parts[2].toIntOrNull() ?: return false
        val cal = Calendar.getInstance()
        val today = cal.get(Calendar.YEAR) * 10000 + (cal.get(Calendar.MONTH) + 1) * 100 + cal.get(Calendar.DAY_OF_MONTH)
        return y * 10000 + m * 100 + d > today
    }

    private fun itemToJson(i: CatalogItem): JSONObject = JSONObject().apply {
        put("id", i.id); put("title", i.title); put("image", i.imageUrl ?: ""); put("tall", i.tallImageUrl ?: "")
        put("tags", i.tags); put("free", i.isFree); put("hasPrice", i.hasPrice); put("final", i.finalPrice); put("orig", i.originalPrice)
        put("disc", i.discountPercent); put("url", i.storeUrl); put("dev", i.developer); put("rel", i.releaseDate)
        if (i.mature) put("mature", true)
    }

    private fun itemFromJson(o: JSONObject): CatalogItem = CatalogItem(
        store = Store.GOG, id = o.optString("id"), title = o.optString("title"),
        imageUrl = o.optString("image").ifBlank { null }, tallImageUrl = o.optString("tall").ifBlank { null },
        tags = o.optString("tags"), isFree = o.optBoolean("free"), hasPrice = o.optBoolean("hasPrice"),
        finalPrice = o.optString("final"), originalPrice = o.optString("orig"), discountPercent = o.optInt("disc"),
        storeUrl = o.optString("url"), developer = o.optString("dev"), releaseDate = o.optString("rel"),
        mature = o.optBoolean("mature"),
    )

    private fun listToJson(l: List<CatalogItem>) = JSONArray().apply { l.forEach { put(itemToJson(it)) } }
    private fun listFromJson(a: JSONArray?): List<CatalogItem> {
        if (a == null) return emptyList()
        return List(a.length()) { i -> a.optJSONObject(i) }.filterNotNull().map(::itemFromJson)
    }

    private fun persist(context: Context, f: StoreShelves) {
        runCatching {
            val o = JSONObject().put("trending", listToJson(f.trending)).put("new", listToJson(f.whatsNew)).put("deals", listToJson(f.deals)).put("free", listToJson(f.free))
            GogPrefs.get(context).edit().putString(KEY_FEATURED, o.toString()).apply()
        }
    }

    private fun loadPersisted(context: Context): StoreShelves? = runCatching {
        val s = GogPrefs.get(context).getString(KEY_FEATURED, null) ?: return null
        val o = JSONObject(s)
        StoreShelves(listFromJson(o.optJSONArray("new")), listFromJson(o.optJSONArray("deals")), listFromJson(o.optJSONArray("free")), listFromJson(o.optJSONArray("trending"))).takeIf { !it.isEmpty }
    }.getOrNull()
}
