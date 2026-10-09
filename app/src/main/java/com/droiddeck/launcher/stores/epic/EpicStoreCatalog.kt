package com.droiddeck.launcher.stores.epic

import android.content.Context
import android.util.Log
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreNet
import com.droiddeck.launcher.stores.StoreShelves
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import java.util.concurrent.ConcurrentHashMap

/**
 * The Epic Games Store catalog through the GraphQL endpoint the store website uses
 * (`store.epicgames.com/graphql`, which wants a browser User-Agent): `Catalog.searchStore` with
 * the base-game category, sorted by price / release date / relevance, on sale or free. Elements
 * carry the offer id, the namespace, the catalog item ids (what the library records, so ownership
 * is matched on those), key images and pre-formatted prices. "Free this week" comes from the
 * static `freeGamesPromotions` feed.
 */
object EpicStoreCatalog {
    private const val TAG = "EpicStore"
    private const val GRAPHQL = "https://store.epicgames.com/graphql"
    private const val PROMOS = "https://store-site-backend-static-ipv4.ak.epicgames.com/freeGamesPromotions"
    private const val FEATURED_TTL_MS = 30 * 60 * 1000L
    private const val SEARCH_TTL_MS = 5 * 60 * 1000L
    private const val KEY_FEATURED = "store_featured"

    private const val ELEMENT_FIELDS =
        "title id namespace description effectiveDate releaseDate offerType productSlug urlSlug " +
            "items{id namespace} keyImages{type url} seller{name} tags{name} " +
            "catalogNs{mappings(pageType:\"productHome\"){pageSlug pageType}} " +
            "price(country:\$country){totalPrice{discountPrice originalPrice discount currencyCode " +
            "fmtPrice(locale:\$locale){originalPrice discountPrice}}}"

    private class Cached<T>(val value: T, val at: Long)
    private var featuredCache: Cached<StoreShelves>? = null
    private val searchCache = ConcurrentHashMap<String, Cached<List<CatalogItem>>>()

    private fun country(): String = Locale.getDefault().country.takeIf { it.length == 2 }?.uppercase(Locale.ROOT) ?: "US"

    private fun nowIso(): String {
        val f = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.US)
        f.timeZone = TimeZone.getTimeZone("UTC")
        return f.format(Date())
    }

    private fun searchStore(count: Int, keywords: String? = null, sortBy: String = "relevancy", sortDir: String = "DESC", onSale: Boolean? = null, freeGame: Boolean? = null, releasedOnly: Boolean = false): List<CatalogItem> {
        val args = StringBuilder("category:\"games/edition/base\",count:\$count,country:\$country,locale:\$locale,sortBy:\$sortBy,sortDir:\$sortDir,allowCountries:\$country")
        if (keywords != null) args.append(",keywords:\$keywords")
        if (onSale != null) args.append(",onSale:$onSale")
        if (freeGame != null) args.append(",freeGame:$freeGame")
        if (releasedOnly) args.append(",releaseDate:\"[,${nowIso()}]\"")
        val query = "query q(\$count:Int,\$country:String!,\$locale:String,\$sortBy:String,\$sortDir:String" +
            (if (keywords != null) ",\$keywords:String" else "") + "){Catalog{searchStore($args){elements{$ELEMENT_FIELDS} paging{total}}}}"
        val vars = JSONObject().put("count", count).put("country", country()).put("locale", "en-US").put("sortBy", sortBy).put("sortDir", sortDir)
        if (keywords != null) vars.put("keywords", keywords)
        val resp = StoreNet.postJson(GRAPHQL, JSONObject().put("query", query).put("variables", vars).toString()) ?: return emptyList()
        val elements = runCatching { JSONObject(resp).optJSONObject("data")?.optJSONObject("Catalog")?.optJSONObject("searchStore")?.optJSONArray("elements") }
            .onFailure { Log.w(TAG, "searchStore parse failed: ${it.message}") }.getOrNull() ?: return emptyList()
        return parseElements(elements)
    }

    private fun parseElements(arr: JSONArray): List<CatalogItem> {
        val out = ArrayList<CatalogItem>(arr.length())
        for (i in 0 until arr.length()) arr.optJSONObject(i)?.let { e -> parseElement(e)?.let { out.add(it) } }
        return out.distinctBy { it.id }
    }

    fun parseElement(e: JSONObject): CatalogItem? {
        val id = e.optString("id", "")
        val title = e.optString("title", "")
        if (id.isEmpty() || title.isEmpty()) return null
        val ns = e.optString("namespace", "")
        var wide: String? = null; var tall: String? = null; var thumb: String? = null
        e.optJSONArray("keyImages")?.let { imgs ->
            for (k in 0 until imgs.length()) {
                val img = imgs.optJSONObject(k) ?: continue
                val url = img.optString("url", "")
                if (url.isBlank()) continue
                // The opaque box images first; the logo variants are transparent and never used.
                when (img.optString("type", "")) {
                    "DieselGameBox" -> wide = url
                    "OfferImageWide", "DieselStoreFrontWide" -> if (wide == null) wide = url
                    "DieselGameBoxTall" -> tall = url
                    "OfferImageTall", "DieselStoreFrontTall" -> if (tall == null) tall = url
                    "Thumbnail" -> if (thumb == null) thumb = url
                }
            }
        }
        val price = e.optJSONObject("price")?.optJSONObject("totalPrice")
        val original = price?.optLong("originalPrice", -1L) ?: -1L
        val discounted = price?.optLong("discountPrice", -1L) ?: -1L
        val fmt = price?.optJSONObject("fmtPrice")
        val fmtOriginal = fmt?.optString("originalPrice", "").orEmpty()
        val fmtDiscount = fmt?.optString("discountPrice", "").orEmpty()
        val hasPrice = original >= 0 && discounted >= 0
        val isFree = hasPrice && discounted == 0L
        val discountPct = if (hasPrice && original > 0 && discounted < original) ((original - discounted) * 100 / original).toInt() else 0
        val finalPrice = when { isFree -> "Free"; fmtDiscount.isNotBlank() && fmtDiscount != "0" -> fmtDiscount; else -> fmtOriginal }
        val tagLine = buildList {
            e.optJSONArray("tags")?.let { tags -> for (t in 0 until tags.length()) { val n = tags.optJSONObject(t)?.optString("name").orEmpty(); if (n.isNotBlank() && n != "Windows" && size < 3) add(n) } }
        }.joinToString(", ")
        // Epic's public store GraphQL has no age rating on an offer (no ageGatings/ratings field on
        // CatalogOffer or StoreConfig); only a content tag, if Epic sets one, can mark it.
        val allTags = buildList { e.optJSONArray("tags")?.let { t -> for (k in 0 until t.length()) t.optJSONObject(k)?.optString("name")?.let { add(it) } } }
        val itemIds = buildList {
            e.optJSONArray("items")?.let { items -> for (j in 0 until items.length()) items.optJSONObject(j)?.optString("id")?.takeIf { it.isNotBlank() }?.let { add(it) } }
        }
        val slug = pageSlugOf(e)
        val storeUrl = if (slug.isNotBlank()) "https://store.epicgames.com/en-US/p/$slug" else "https://store.epicgames.com/en-US/browse?q=${java.net.URLEncoder.encode(title, "UTF-8")}"
        return CatalogItem(
            store = Store.EPIC, id = id, title = title, imageUrl = wide ?: thumb, tallImageUrl = tall ?: thumb, tags = tagLine,
            isFree = isFree, hasPrice = hasPrice, finalPrice = finalPrice, originalPrice = if (discountPct > 0) fmtOriginal else "", discountPercent = discountPct,
            storeUrl = storeUrl, developer = e.optJSONObject("seller")?.optString("name", "").orEmpty(),
            releaseDate = e.optString("releaseDate", e.optString("effectiveDate", "")), description = com.droiddeck.launcher.stores.cleanStoreText(e.optString("description", "")),
            extra = buildMap { put("namespace", ns); if (itemIds.isNotEmpty()) put("items", itemIds.joinToString(",")); if (slug.isNotBlank()) put("slug", slug) },
            mature = com.droiddeck.launcher.stores.matureByTags(allTags),
        )
    }

    private fun pageSlugOf(e: JSONObject): String {
        e.optJSONObject("catalogNs")?.optJSONArray("mappings")?.let { m ->
            for (i in 0 until m.length()) { val map = m.optJSONObject(i) ?: continue; if (map.optString("pageType") == "productHome") { val s = map.optString("pageSlug", ""); if (s.isNotBlank()) return s } }
        }
        var slug = e.optString("productSlug", "")
        if (slug == "null") slug = ""
        return slug.removeSuffix("/home")
    }

    /** The Store tab's shelves: this week's giveaways as Free, sales as Deals, newest releases, trending = free-to-play picks. Blocking. */
    fun featured(context: Context, force: Boolean = false): StoreShelves? {
        val now = System.currentTimeMillis()
        featuredCache?.let { if (!force && now - it.at < FEATURED_TTL_MS) return it.value }
        val freeNow = fetchPromos()
        val onSale = searchStore(24, sortBy = "currentPrice", sortDir = "ASC", onSale = true).filter { it.discountPercent > 0 }
        val newest = searchStore(24, sortBy = "releaseDate", sortDir = "DESC", releasedOnly = true)
        val freeToPlay = searchStore(24, sortBy = "relevancy", sortDir = "DESC", freeGame = true)
        val result = StoreShelves(whatsNew = newest, deals = onSale, free = (freeNow + freeToPlay).distinctBy { it.id }, trending = searchStore(24, sortBy = "relevancy", sortDir = "DESC", releasedOnly = true))
        if (!result.isEmpty) {
            featuredCache = Cached(result, now)
            persist(context, result)
            Log.i(TAG, "shelves: freeNow=${freeNow.size} sale=${onSale.size} new=${newest.size} f2p=${freeToPlay.size}")
            return result
        }
        Log.w(TAG, "every store feed came back empty; using the on-disk mirror")
        return loadPersisted(context)
    }

    fun cachedFeatured(context: Context): StoreShelves? = featuredCache?.value ?: loadPersisted(context)

    fun search(query: String): List<CatalogItem> {
        val term = query.trim()
        if (term.length < 2) return emptyList()
        val key = term.lowercase(Locale.ROOT)
        val now = System.currentTimeMillis()
        searchCache[key]?.let { if (now - it.at < SEARCH_TTL_MS) return it.value }
        val results = searchStore(30, keywords = term)
        searchCache[key] = Cached(results, now)
        return results
    }

    /** This week's giveaway titles (100% off right now). */
    private fun fetchPromos(): List<CatalogItem> {
        val body = StoreNet.get("$PROMOS?locale=en-US&country=${country()}&allowCountries=${country()}") ?: return emptyList()
        return runCatching {
            val elements = JSONObject(body).optJSONObject("data")?.optJSONObject("Catalog")?.optJSONObject("searchStore")?.optJSONArray("elements") ?: JSONArray()
            val out = ArrayList<CatalogItem>()
            for (i in 0 until elements.length()) {
                val el = elements.optJSONObject(i) ?: continue
                val current = el.optJSONObject("promotions")?.optJSONArray("promotionalOffers")
                if (current == null || current.length() == 0) continue
                val inner = current.optJSONObject(0)?.optJSONArray("promotionalOffers") ?: continue
                if (inner.length() == 0) continue
                val discount = inner.optJSONObject(0)?.optJSONObject("discountSetting")
                if (discount == null || discount.optInt("discountPercentage", -1) != 0) continue
                val end = inner.optJSONObject(0)?.optString("endDate", "").orEmpty()
                val base = parseElement(el) ?: continue
                out.add(base.copy(isFree = true, hasPrice = true, finalPrice = "Free", originalPrice = base.originalPrice.ifBlank {
                    el.optJSONObject("price")?.optJSONObject("totalPrice")?.optJSONObject("fmtPrice")?.optString("originalPrice", "").orEmpty()
                }, discountPercent = 100, tags = if (end.length >= 10) "Free until ${end.take(10)}" else base.tags))
            }
            out.distinctBy { it.id }
        }.onFailure { Log.w(TAG, "promos parse failed: ${it.message}") }.getOrDefault(emptyList())
    }

    private fun itemToJson(i: CatalogItem): JSONObject = JSONObject().apply {
        put("id", i.id); put("title", i.title); put("image", i.imageUrl ?: ""); put("tall", i.tallImageUrl ?: ""); put("tags", i.tags)
        put("free", i.isFree); put("hasPrice", i.hasPrice); put("final", i.finalPrice); put("orig", i.originalPrice); put("disc", i.discountPercent)
        put("url", i.storeUrl); put("dev", i.developer); put("rel", i.releaseDate); put("desc", i.description); put("extra", JSONObject(i.extra as Map<*, *>))
        if (i.mature) put("mature", true)
    }

    private fun itemFromJson(o: JSONObject): CatalogItem {
        val extra = HashMap<String, String>()
        o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
        return CatalogItem(
            store = Store.EPIC, id = o.optString("id"), title = o.optString("title"), imageUrl = o.optString("image").ifBlank { null }, tallImageUrl = o.optString("tall").ifBlank { null },
            tags = o.optString("tags"), isFree = o.optBoolean("free"), hasPrice = o.optBoolean("hasPrice"), finalPrice = o.optString("final"), originalPrice = o.optString("orig"),
            discountPercent = o.optInt("disc"), storeUrl = o.optString("url"), developer = o.optString("dev"), releaseDate = o.optString("rel"), description = o.optString("desc"), extra = extra,
            mature = o.optBoolean("mature"),
        )
    }

    private fun listToJson(l: List<CatalogItem>) = JSONArray().apply { l.forEach { put(itemToJson(it)) } }
    private fun listFromJson(a: JSONArray?): List<CatalogItem> = if (a == null) emptyList() else List(a.length()) { a.optJSONObject(it) }.filterNotNull().map(::itemFromJson)

    private fun persist(context: Context, f: StoreShelves) {
        runCatching {
            EpicPrefs.get(context).edit().putString(KEY_FEATURED, JSONObject().put("new", listToJson(f.whatsNew)).put("deals", listToJson(f.deals)).put("free", listToJson(f.free)).put("trending", listToJson(f.trending)).toString()).apply()
        }
    }

    private fun loadPersisted(context: Context): StoreShelves? = runCatching {
        val s = EpicPrefs.get(context).getString(KEY_FEATURED, null) ?: return null
        val o = JSONObject(s)
        StoreShelves(listFromJson(o.optJSONArray("new")), listFromJson(o.optJSONArray("deals")), listFromJson(o.optJSONArray("free")), listFromJson(o.optJSONArray("trending"))).takeIf { !it.isEmpty }
    }.getOrNull()
}
