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
    private val fetch: () -> StoreShelves?,
    private val ttlMs: Long = 3 * 60 * 60 * 1000L,
) {
    @Volatile private var memory: StoreShelves? = null
    @Volatile private var memoryAt = 0L

    fun cached(): StoreShelves? = memory ?: decode(prefs().getString(KEY, null))?.also { memory = it }

    fun shelves(force: Boolean): StoreShelves? {
        val now = System.currentTimeMillis()
        if (!force) {
            memory?.takeIf { now - memoryAt in 0 until ttlMs }?.let { return it }
            val at = prefs().getLong(KEY_AT, 0L)
            if (now - at in 0 until ttlMs) decode(prefs().getString(KEY, null))?.let { memory = it; memoryAt = at; return it }
        }
        val fresh = runCatching(fetch).onFailure { StoresState.logLine("${store.label}: store shelves failed: ${it.javaClass.simpleName} ${it.message.orEmpty().take(160)}") }
            .getOrNull()?.takeUnless { it.isEmpty }
            ?: return cached()
        prefs().edit().putString(KEY, encode(fresh)).putLong(KEY_AT, now).apply()
        memory = fresh
        memoryAt = now
        return fresh
    }

    private fun encode(s: StoreShelves): String = JSONObject().apply {
        listOf(s.whatsNew, s.deals, s.free, s.trending).zip(SHELVES).forEach { (items, key) -> put(key, JSONArray().apply { items.forEach { put(itemJson(it)) } }) }
        put("art", JSONArray(s.art))
    }.toString()

    private fun decode(text: String?): StoreShelves? {
        val o = text?.let { runCatching { JSONObject(it) }.getOrNull() } ?: return null
        fun shelf(key: String) = o.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::itemFrom) } }.orEmpty()
        val art = o.optJSONArray("art")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        return StoreShelves(shelf("new"), shelf("deals"), shelf("free"), shelf("trending"), art).takeUnless { it.isEmpty }
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
        private const val KEY = "store_front"
        private const val KEY_AT = "store_front_at"
        private val SHELVES = listOf("new", "deals", "free", "trending")
        private val MARKS = Regex("[\\u2122\\u00AE\\u00A9]")
        private val NOT_WORD = Regex("[^a-z0-9]+")

        /** A title reduced to letters and digits, so "Far Cry® 6" and "Far Cry 6" meet. */
        fun titleKey(title: String): String =
            Normalizer.normalize(title.replace(MARKS, ""), Normalizer.Form.NFD).lowercase(Locale.ROOT).replace(NOT_WORD, " ").trim()

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
                )
            }.distinctBy { it.id }
        }
    }
}
