package com.droiddeck.launcher.stores.epic

import android.content.Context
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreShelves
import com.droiddeck.launcher.stores.cleanStoreText
import com.droiddeck.launcher.stores.matureByTags
import org.json.JSONArray
import org.json.JSONObject
import java.util.Locale

object EpicShelves {
    const val KEY_FEATURED = "store_featured"
    const val KEY_FEATURED_AT = "store_featured_at"
    private const val FRESH_MS = 3 * 60 * 60 * 1000L
    private val SHELF_KEYS = listOf("new", "deals", "free", "trending")

    @Volatile private var memory: StoreShelves? = null
    @Volatile private var memoryAt = 0L

    private fun fresh(at: Long) = System.currentTimeMillis() - at in 0 until FRESH_MS

    fun itemFrom(o: JSONObject): CatalogItem? {
        val id = o.optString("id", "").ifEmpty { return null }
        val title = o.optString("title", "").ifEmpty { return null }
        val extra = LinkedHashMap<String, String>()
        o.optJSONObject("extra")?.let { e -> e.keys().forEach { k -> extra[k] = e.optString(k) } }
        val mature = if (o.has("mature")) o.optBoolean("mature") else {
            val tags = o.optJSONArray("allTags")
            matureByTags(List(tags?.length() ?: 0) { tags!!.optString(it) })
        }
        return CatalogItem(
            store = Store.EPIC, id = id, title = title,
            imageUrl = o.optString("image", "").ifEmpty { null },
            tallImageUrl = o.optString("tall", "").ifEmpty { null },
            tags = o.optString("tags", ""),
            isFree = o.optBoolean("free", false),
            hasPrice = o.optBoolean("hasPrice", false),
            finalPrice = o.optString("final", ""),
            originalPrice = o.optString("orig", ""),
            discountPercent = o.optInt("disc", 0),
            storeUrl = o.optString("url", ""),
            developer = o.optString("dev", ""),
            publisher = o.optString("pub", ""),
            releaseDate = o.optString("rel", ""),
            description = cleanStoreText(o.optString("desc", "")),
            extra = extra,
            mature = mature,
        )
    }

    fun itemJson(i: CatalogItem): JSONObject = JSONObject()
        .put("id", i.id).put("title", i.title).put("image", i.imageUrl ?: "").put("tall", i.tallImageUrl ?: "")
        .put("tags", i.tags).put("free", i.isFree).put("hasPrice", i.hasPrice).put("final", i.finalPrice)
        .put("orig", i.originalPrice).put("disc", i.discountPercent).put("url", i.storeUrl).put("dev", i.developer)
        .put("pub", i.publisher).put("rel", i.releaseDate).put("desc", i.description)
        .put("extra", JSONObject(i.extra as Map<*, *>)).put("mature", i.mature)

    fun fromJson(text: String?): StoreShelves? {
        if (text.isNullOrBlank()) return null
        val o = runCatching { JSONObject(text) }.getOrNull() ?: return null
        fun shelf(key: String): List<CatalogItem> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.mapNotNull(::itemFrom).distinctBy { it.id }
        }
        val art = o.optJSONArray("art")?.let { a -> List(a.length()) { a.optString(it) }.filter { it.isNotEmpty() } }.orEmpty()
        val s = StoreShelves(shelf("new"), shelf("deals"), shelf("free"), shelf("trending"), art)
        return if (s.isEmpty && art.isEmpty()) null else s
    }

    fun toJson(s: StoreShelves): String = JSONObject().apply {
        listOf(s.whatsNew, s.deals, s.free, s.trending).zip(SHELF_KEYS).forEach { (items, key) ->
            put(key, JSONArray().apply { items.forEach { put(itemJson(it)) } })
        }
        put("art", JSONArray(s.art))
    }.toString()

    fun cached(context: Context): StoreShelves? =
        memory ?: fromJson(EpicPrefs.get(context).getString(KEY_FEATURED, null))?.also { memory = it }

    fun featured(context: Context, force: Boolean): StoreShelves? {
        val prefs = EpicPrefs.get(context)
        if (!force) {
            memory?.takeIf { fresh(memoryAt) }?.let { return it }
            if (fresh(prefs.getLong(KEY_FEATURED_AT, 0L))) fromJson(prefs.getString(KEY_FEATURED, null))?.let {
                memory = it; memoryAt = prefs.getLong(KEY_FEATURED_AT, 0L); return it
            }
        }
        val country = Locale.getDefault().country.takeIf { it.length == 2 && it.all(Char::isLetter) }?.uppercase() ?: "US"
        val answer = EpicNative.call("shop.shelves", JSONObject().put("country", country))
        val shelves = fromJson(answer?.toString())
        if (shelves == null) return fromJson(prefs.getString(KEY_FEATURED, null))
        val now = System.currentTimeMillis()
        prefs.edit().putString(KEY_FEATURED, toJson(shelves)).putLong(KEY_FEATURED_AT, now).apply()
        memory = shelves
        memoryAt = now
        return shelves
    }

    fun markOwned(s: StoreShelves, library: List<CatalogItem>?): StoreShelves {
        if (library.isNullOrEmpty()) return s
        val byCatalogId = HashMap<String, CatalogItem>()
        for (item in library) item.extra["catalogItemId"]?.takeIf { it.isNotEmpty() }?.let { byCatalogId[it] = item }
        fun mark(list: List<CatalogItem>) = list.map { shelf ->
            val owned = shelf.extra["items"].orEmpty().split(',').map { it.trim() }.firstNotNullOfOrNull { byCatalogId[it] }
                ?: return@map shelf
            owned.copy(
                imageUrl = shelf.imageUrl ?: owned.imageUrl,
                tallImageUrl = shelf.tallImageUrl ?: owned.tallImageUrl,
                developer = owned.developer.ifBlank { shelf.developer },
                publisher = owned.publisher.ifBlank { shelf.publisher },
                releaseDate = owned.releaseDate.ifBlank { shelf.releaseDate },
            )
        }
        return StoreShelves(mark(s.whatsNew), mark(s.deals), mark(s.free), mark(s.trending), s.art)
    }
}
