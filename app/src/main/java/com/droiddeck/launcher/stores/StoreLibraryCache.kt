package com.droiddeck.launcher.stores

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

internal object StoreLibraryCache {
    private fun file(context: Context, store: Store) = File(context.filesDir, "store-library/${store.id}.json")

    fun load(context: Context, store: Store): List<CatalogItem>? {
        val f = file(context, store)
        if (!f.isFile) return null
        return runCatching { decode(store, f.readText()) }.getOrNull()
    }

    private val locks = Store.entries.associateWith { Any() }

    fun save(context: Context, store: Store, items: List<CatalogItem>, current: () -> Boolean) = synchronized(locks.getValue(store)) {
        if (!current()) return@synchronized
        val f = file(context, store)
        runCatching {
            f.parentFile?.mkdirs()
            val tmp = File(f.parentFile, f.name + ".tmp")
            tmp.writeText(encode(items))
            if (!tmp.renameTo(f)) { f.delete(); tmp.renameTo(f) }
        }
    }

    fun clear(context: Context, store: Store) = synchronized(locks.getValue(store)) {
        file(context, store).delete()
    }

    internal fun encode(items: List<CatalogItem>): String {
        val out = JSONArray()
        items.forEach { i ->
            out.put(JSONObject()
                .put("id", i.id).put("title", i.title)
                .put("image", i.imageUrl ?: "").put("tall", i.tallImageUrl ?: "").put("logo", i.logoUrl ?: "")
                .put("tags", i.tags).put("free", i.isFree).put("hasPrice", i.hasPrice)
                .put("finalPrice", i.finalPrice).put("originalPrice", i.originalPrice).put("discount", i.discountPercent)
                .put("url", i.storeUrl).put("developer", i.developer).put("publisher", i.publisher)
                .put("releaseDate", i.releaseDate).put("description", i.description)
                .put("owned", i.owned).put("size", i.sizeBytes).put("extra", JSONObject(i.extra))
                .put("mature", i.mature).put("subscription", i.subscription).put("includedWith", i.includedWith))
        }
        return out.toString()
    }

    internal fun decode(store: Store, text: String): List<CatalogItem> {
        val raw = JSONArray(text)
        return (0 until raw.length()).mapNotNull { n ->
            val o = raw.optJSONObject(n) ?: return@mapNotNull null
            val extra = o.optJSONObject("extra")
            val map = HashMap<String, String>()
            extra?.keys()?.forEach { k -> map[k] = extra.optString(k) }
            CatalogItem(
                store = store, id = o.optString("id"), title = o.optString("title"),
                imageUrl = o.optString("image").ifBlank { null }, tallImageUrl = o.optString("tall").ifBlank { null },
                logoUrl = o.optString("logo").ifBlank { null },
                tags = o.optString("tags"), isFree = o.optBoolean("free"), hasPrice = o.optBoolean("hasPrice"),
                finalPrice = o.optString("finalPrice"), originalPrice = o.optString("originalPrice"), discountPercent = o.optInt("discount"),
                storeUrl = o.optString("url"), developer = o.optString("developer"), publisher = o.optString("publisher"),
                releaseDate = o.optString("releaseDate"), description = o.optString("description"),
                owned = o.optBoolean("owned"), sizeBytes = o.optLong("size"), extra = map,
                mature = o.optBoolean("mature"), subscription = o.optBoolean("subscription"), includedWith = o.optString("includedWith"),
            )
        }
    }
}
