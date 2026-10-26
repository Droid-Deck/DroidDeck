package com.droiddeck.launcher.stores.itch

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoreNet
import org.json.JSONArray

internal class ItchBuild(
    val uploadId: String,
    val filename: String,
    val size: Long,
    val updatedAt: String,
    val platforms: Set<String>,
    val demo: Boolean,
) {
    val installable: Boolean get() = "windows" in platforms
}

internal object ItchGame {
    private val PATH_AFTER = Regex("""name="itch:path"[^>]*content="games/(\d+)"""")
    private val PATH_BEFORE = Regex("""content="games/(\d+)"[^>]*name="itch:path"""")

    fun idFromPage(html: String): String? =
        PATH_AFTER.find(html)?.groupValues?.get(1) ?: PATH_BEFORE.find(html)?.groupValues?.get(1)

    fun resolveId(item: CatalogItem): String? {
        item.extra["gameId"]?.takeIf { it.isNotBlank() }?.let { return it }
        if (item.storeUrl.isBlank()) return null
        val html = StoreNet.get(item.storeUrl) ?: return null
        return idFromPage(html)
    }

    fun builds(uploads: JSONArray?): List<ItchBuild> {
        if (uploads == null) return emptyList()
        return (0 until uploads.length()).mapNotNull { uploads.optJSONObject(it) }
            .filter { it.optString("storage") != "external" }
            .map {
                ItchBuild(
                    it.optString("id"), ItchUploads.filename(it), ItchUploads.size(it),
                    it.optString("updated_at", it.optString("updatedAt")), ItchUploads.platforms(it), it.optBoolean("demo"),
                )
            }
            .sortedWith(compareByDescending<ItchBuild> { it.installable }.thenByDescending { it.updatedAt })
    }
}
