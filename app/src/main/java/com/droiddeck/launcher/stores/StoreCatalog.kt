package com.droiddeck.launcher.stores

data class CatalogItem(
    val store: Store,
    val id: String,
    val title: String,
    val imageUrl: String?,
    val tallImageUrl: String? = null,
    val tags: String = "",
    val isFree: Boolean = false,
    val hasPrice: Boolean = false,
    val finalPrice: String = "",
    val originalPrice: String = "",
    val discountPercent: Int = 0,
    val storeUrl: String = "",
    val developer: String = "",
    val publisher: String = "",
    val releaseDate: String = "",
    val description: String = "",
    val owned: Boolean = false,
    val sizeBytes: Long = 0L,
    val extra: Map<String, String> = emptyMap(),
    val mature: Boolean = false,
) {
    val isDiscounted: Boolean get() = discountPercent > 0 && originalPrice.isNotBlank()
    val key: String get() = "${store.id}:$id"
}

class StoreShelves(
    val whatsNew: List<CatalogItem> = emptyList(),
    val deals: List<CatalogItem> = emptyList(),
    val free: List<CatalogItem> = emptyList(),
    val trending: List<CatalogItem> = emptyList(),
    val art: List<String> = emptyList(),
) {
    val isEmpty: Boolean get() = whatsNew.isEmpty() && deals.isEmpty() && free.isEmpty() && trending.isEmpty()
    val all: List<CatalogItem> get() = (whatsNew + deals + free + trending).distinctBy { it.id }
}

private val MATURE_TAGS = setOf("mature", "nsfw", "adult", "adult only", "adults only", "nudity", "sexual content", "sexual-content")

fun matureByTags(tags: Collection<String>): Boolean = tags.any { it.trim().lowercase() in MATURE_TAGS }

fun matureByAge(ages: Collection<Int>): Boolean = ages.any { it >= 17 }

fun installedCards(store: Store, installed: List<InstalledStoreGame>, library: List<CatalogItem>): List<CatalogItem> {
    val byId = library.associateBy { it.id }
    val byTitle = library.associateBy { it.title.lowercase() }
    return installed.filter { it.sidecar.store == store }.map { game ->
        val s = game.sidecar
        byId[s.id] ?: byTitle[s.title.lowercase()]
            ?: CatalogItem(store, s.id, s.title, imageUrl = s.hero ?: s.cover, tallImageUrl = s.cover ?: s.hero, owned = true)
    }.distinctBy { it.id }.sortedBy { it.title.lowercase() }
}

private fun releaseDateValue(item: CatalogItem): Long =
    item.releaseDate.take(10).takeIf { it.length == 10 }?.let { raw ->
        runCatching { java.time.LocalDate.parse(raw).toEpochDay() }.getOrDefault(0L)
    } ?: 0L

fun sortCatalogItems(
    items: List<CatalogItem>,
    sort: String,
    lastPlayed: (CatalogItem) -> Long = { 0L },
    installedAt: (CatalogItem) -> Long = { 0L },
    size: (CatalogItem) -> Long = { it.sizeBytes },
): List<CatalogItem> = when (sort) {
    "lastPlayed" -> items.sortedWith(compareByDescending(lastPlayed).thenBy { it.title.lowercase() })
    "recentlyInstalled" -> items.sortedWith(compareByDescending(installedAt).thenBy { it.title.lowercase() })
    "titleAsc" -> items.sortedBy { it.title.lowercase() }
    "titleDesc" -> items.sortedByDescending { it.title.lowercase() }
    "sizeDesc" -> items.sortedWith(compareByDescending(size).thenBy { it.title.lowercase() })
    "sizeAsc" -> items.sortedWith(compareBy<CatalogItem> { size(it).takeIf { b -> b > 0 } ?: Long.MAX_VALUE }.thenBy { it.title.lowercase() })
    "releaseDateDesc" -> items.sortedWith(compareByDescending(::releaseDateValue).thenBy { it.title.lowercase() })
    else -> items
}

internal fun retainCatalogOrder(items: List<CatalogItem>, previous: List<CatalogItem>): List<CatalogItem> {
    if (items.size != previous.size) return items
    val byKey = items.associateBy { it.key }
    if (previous.any { it.key !in byKey }) return items
    return previous.map { byKey.getValue(it.key) }
}

fun mergeItems(old: List<CatalogItem>?, new: List<CatalogItem>): List<CatalogItem> {
    if (old.isNullOrEmpty()) return new
    val before = old.associateBy { it.key }
    val merged = new.map { n -> before[n.key]?.takeIf { it == n } ?: n }
    return if (merged.size == old.size && merged.indices.all { merged[it] === old[it] }) old else merged
}

class InstalledStoreGame(val sidecar: StoreGameSidecar, val folder: java.io.File) {
    val key: String get() = "${sidecar.store.id}:${sidecar.id}"
}

private val TEMPLATE_KEY = Regex("product_(description|feature)_\\d+")
private val HTML_TAG = Regex("<[^>]+>")
private val ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")
private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…", "trade" to "™", "reg" to "®", "copy" to "©")

fun cleanStoreText(raw: String?): String {
    if (raw.isNullOrBlank()) return ""
    var s = raw.replace(TEMPLATE_KEY, " ")
    s = s.replace(Regex("(?i)<br\\s*/?>|</p>|</li>|</div>"), "\n").replace(HTML_TAG, " ")
    s = ENTITY.replace(s) { m ->
        val e = m.groupValues[1]
        when {
            e.startsWith("#x") -> e.substring(2).toIntOrNull(16)?.toChar()?.toString() ?: " "
            e.startsWith("#") -> e.substring(1).toIntOrNull()?.toChar()?.toString() ?: " "
            else -> NAMED_ENTITIES[e.lowercase()] ?: " "
        }
    }
    return s.lines().map { it.replace(Regex("[ \\t\\u00A0]+"), " ").trim() }.filter { it.isNotEmpty() }.joinToString("\n")
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    bytes >= 0L -> "$bytes B"
    else -> ""
}

fun formatSpeed(bytesPerSec: Long): String = if (bytesPerSec <= 0L) "" else "${formatBytes(bytesPerSec)}/s"
