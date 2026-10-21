package com.droiddeck.launcher.stores.itch

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreShelves
import org.json.JSONArray
import org.json.JSONObject

internal object ItchCatalog {
    fun slug(url: String): String = url.substringAfter("://", url).trimEnd('/')

    fun shelvesJson(shelves: StoreShelves): String = JSONObject()
        .put("new", array(shelves.whatsNew))
        .put("deals", array(shelves.deals))
        .put("free", array(shelves.free))
        .put("top", array(shelves.trending))
        .toString()

    fun shelves(raw: String?): StoreShelves? {
        if (raw.isNullOrBlank()) return null
        val o = runCatching { JSONObject(raw) }.getOrNull() ?: return null
        val shelves = StoreShelves(
            whatsNew = list(o.optJSONArray("new")),
            deals = list(o.optJSONArray("deals")),
            free = list(o.optJSONArray("free")),
            trending = list(o.optJSONArray("top")),
        )
        return shelves.takeUnless { it.isEmpty }
    }

    fun array(items: List<CatalogItem>): JSONArray {
        val out = JSONArray()
        items.forEach { out.put(json(it)) }
        return out
    }

    fun list(raw: JSONArray?): List<CatalogItem> {
        if (raw == null) return emptyList()
        return (0 until raw.length()).mapNotNull { raw.optJSONObject(it)?.let(::item) }
    }

    private fun json(i: CatalogItem): JSONObject = JSONObject()
        .put("id", i.id)
        .put("title", i.title)
        .put("image", i.imageUrl ?: "")
        .put("tall", i.tallImageUrl ?: "")
        .put("tags", i.tags)
        .put("free", i.isFree)
        .put("hasPrice", i.hasPrice)
        .put("finalPrice", i.finalPrice)
        .put("originalPrice", i.originalPrice)
        .put("discount", i.discountPercent)
        .put("url", i.storeUrl)
        .put("developer", i.developer)
        .put("publisher", i.publisher)
        .put("releaseDate", i.releaseDate)
        .put("description", i.description)
        .put("owned", i.owned)
        .put("size", i.sizeBytes)
        .put("extra", JSONObject(i.extra))
        .put("mature", i.mature)

    private fun item(o: JSONObject): CatalogItem {
        val extra = o.optJSONObject("extra")
        val map = HashMap<String, String>()
        extra?.keys()?.forEach { k -> map[k] = extra.optString(k) }
        return CatalogItem(
            store = Store.ITCH,
            id = o.optString("id"),
            title = o.optString("title"),
            imageUrl = o.optString("image").ifBlank { null },
            tallImageUrl = o.optString("tall").ifBlank { null },
            tags = o.optString("tags"),
            isFree = o.optBoolean("free"),
            hasPrice = o.optBoolean("hasPrice"),
            finalPrice = o.optString("finalPrice"),
            originalPrice = o.optString("originalPrice"),
            discountPercent = o.optInt("discount"),
            storeUrl = o.optString("url"),
            developer = o.optString("developer"),
            publisher = o.optString("publisher"),
            releaseDate = o.optString("releaseDate"),
            description = o.optString("description"),
            owned = o.optBoolean("owned"),
            sizeBytes = o.optLong("size"),
            extra = map,
            mature = o.optBoolean("mature"),
        )
    }
}
