package com.droiddeck.launcher.stores

import android.content.SharedPreferences
import org.json.JSONArray
import org.json.JSONObject
import java.text.Normalizer
import java.util.Locale

/** Store shelves fetched from a public catalog, kept in memory and on disk between refreshes. */
internal class StoreFront(
    private val store: Store,
    private val prefs: () -> SharedPreferences,
    private val fetch: () -> Catalog,
    private val key: String = "store_front",
    private val ttlMs: Long = 3 * 60 * 60 * 1000L,
) {
    /** One refresh: the shelves and every game the store lists, for search. */
    class Catalog(val shelves: StoreShelves, val all: List<CatalogItem> = shelves.all)

    @Volatile private var memory: Catalog? = null
    @Volatile private var memoryAt = 0L

    fun cached(): StoreShelves? = cachedCatalog()?.shelves

    fun everything(): List<CatalogItem> = cachedCatalog()?.all.orEmpty()

    private fun cachedCatalog(): Catalog? = memory ?: decode(prefs().getString(key, null))?.also { memory = it }

    /** The shelves, refreshed when stale; a refresh that fails keeps the last good set. */
    fun shelves(force: Boolean): StoreShelves? {
        val now = System.currentTimeMillis()
        if (!force) {
            memory?.takeIf { now - memoryAt in 0 until ttlMs }?.let { return it.shelves }
            val at = prefs().getLong("${key}_at", 0L)
            if (now - at in 0 until ttlMs) decode(prefs().getString(key, null))?.let { memory = it; memoryAt = at; return it.shelves }
        }
        val fresh = runCatching(fetch).onFailure { StoresState.logLine("${store.label}: store shelves failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(160)}") }
            .getOrNull()?.takeUnless { it.shelves.isEmpty }
            ?: return cached()
        prefs().edit().putString(key, encode(fresh)).putLong("${key}_at", now).apply()
        memory = fresh
        memoryAt = now
        return fresh.shelves
    }

    private fun encode(c: Catalog): String = JSONObject().apply {
        val s = c.shelves
        listOf(s.whatsNew, s.deals, s.free, s.trending).zip(SHELVES).forEach { (items, k) -> put(k, JSONArray(items.map { it.id })) }
        put("art", JSONArray(s.art))
        put("items", JSONArray().apply { (c.all + s.all).distinctBy { it.id }.forEach { put(itemJson(it)) } })
    }.toString()

    private fun decode(text: String?): Catalog? {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::itemFrom) } }.orEmpty()
        val byId = items.associateBy { it.id }
        fun shelf(k: String) = o.optJSONArray(k)?.let { a -> (0 until a.length()).mapNotNull { byId[a.optString(it)] } }.orEmpty()
        val art = o.optJSONArray("art")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        val shelves = StoreShelves(shelf("new"), shelf("deals"), shelf("free"), shelf("trending"), art).takeUnless { it.isEmpty } ?: return null
        return Catalog(shelves, items)
    }

    private fun itemJson(i: CatalogItem) = JSONObject()
        .put("id", i.id).put("title", i.title).put("image", i.imageUrl.orEmpty()).put("tall", i.tallImageUrl.orEmpty()).put("tags", i.tags)
        .put("free", i.isFree).put("hasPrice", i.hasPrice).put("final", i.finalPrice).put("orig", i.originalPrice).put("disc", i.discountPercent)
        .put("url", i.storeUrl).put("dev", i.developer).put("pub", i.publisher).put("rel", i.releaseDate).put("desc", i.description)
        .put("mature", i.mature).put("logo", i.logoUrl.orEmpty()).put("extra", JSONObject(i.extra as Map<*, *>))

    private fun itemFrom(o: JSONObject): CatalogItem? {
        val extra = LinkedHashMap<String, String>()
        o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
        return CatalogItem(
            store = store, id = o.optString("id").ifEmpty { return null }, title = o.optString("title").ifEmpty { return null },
            imageUrl = o.optString("image").ifEmpty { null }, tallImageUrl = o.optString("tall").ifEmpty { null }, tags = o.optString("tags"),
            isFree = o.optBoolean("free"), hasPrice = o.optBoolean("hasPrice"), finalPrice = o.optString("final"), originalPrice = o.optString("orig"),
            discountPercent = o.optInt("disc"), storeUrl = o.optString("url"), developer = o.optString("dev"), publisher = o.optString("pub"),
            releaseDate = o.optString("rel"), description = o.optString("desc"), mature = o.optBoolean("mature"),
            logoUrl = o.optString("logo").ifEmpty { null }, extra = extra,
        )
    }

    companion object {
        /** On an owned game standing in for a store card: the card's id, so a page opened on the card still finds it. */
        const val STORE_CARD = "storeCard"
        private val SHELVES = listOf("new", "deals", "free", "trending")
        private val MARKS = Regex("[\\u2122\\u00AE\\u00A9]")
        private val COMBINING = Regex("\\p{M}+")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** A title reduced to letters and digits, so "Far Cry® 6" and "Far Cry 6" meet, and "Pokémon" meets "Pokemon". */
        fun titleKey(title: String): String =
            Normalizer.normalize(title.replace(MARKS, ""), Normalizer.Form.NFD).replace(COMBINING, "")
                .lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim()

        /**
         * Shelf cards the account owns become its library entry, which carries what installing needs,
         * keeping the shelf's art and price.
         */
        fun markOwned(s: StoreShelves, library: List<CatalogItem>?, match: (CatalogItem, CatalogItem) -> Boolean): StoreShelves {
            if (library.isNullOrEmpty()) return s
            fun mark(list: List<CatalogItem>) = markOwned(list, library, match)
            return StoreShelves(mark(s.whatsNew), mark(s.deals), mark(s.free), mark(s.trending), s.art)
        }

        fun markOwned(list: List<CatalogItem>, library: List<CatalogItem>?, match: (CatalogItem, CatalogItem) -> Boolean): List<CatalogItem> {
            if (library.isNullOrEmpty()) return list
            return list.map { shelf ->
                val owned = library.firstOrNull { match(shelf, it) } ?: return@map shelf
                owned.copy(
                    imageUrl = shelf.imageUrl ?: owned.imageUrl, tallImageUrl = owned.tallImageUrl ?: shelf.tallImageUrl,
                    tags = owned.tags.ifBlank { shelf.tags }, description = owned.description.ifBlank { shelf.description },
                    releaseDate = owned.releaseDate.ifBlank { shelf.releaseDate }, storeUrl = shelf.storeUrl.ifBlank { owned.storeUrl },
                    developer = owned.developer.ifBlank { shelf.developer }, mature = shelf.mature,
                    extra = owned.extra + (STORE_CARD to shelf.id),
                )
            }.distinctBy { it.id }
        }
    }
}
