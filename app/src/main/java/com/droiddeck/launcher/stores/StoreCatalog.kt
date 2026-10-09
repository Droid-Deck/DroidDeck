package com.droiddeck.launcher.stores

/**
 * One title as the Stores page draws it, whichever store produced it: a card on a shelf, a tile
 * in a grid, the hero of a game page. Library games and storefront results both project into
 * this, so one card composable serves every tab.
 */
data class CatalogItem(
    val store: Store,
    /** Store-native id: GOG product id, Epic catalog item id (offer id for storefront results), Amazon product id. */
    val id: String,
    val title: String,
    /** Wide (16:9-ish) art for cards and heroes. */
    val imageUrl: String?,
    /** Tall (2:3) box art when the store publishes one; falls back to [imageUrl]. */
    val tallImageUrl: String? = null,
    /** Comma-joined genre / tag line. */
    val tags: String = "",
    val isFree: Boolean = false,
    /** False when the endpoint gave no price at all: the price row then draws nothing. */
    val hasPrice: Boolean = false,
    /** Pre-formatted by the store ("$9.99"). */
    val finalPrice: String = "",
    val originalPrice: String = "",
    val discountPercent: Int = 0,
    /** The web product page: where "Get for free" and "View on <store>" land. */
    val storeUrl: String = "",
    val developer: String = "",
    val releaseDate: String = "",
    val description: String = "",
    /** True for a title the signed-in account owns (a library entry). */
    val owned: Boolean = false,
    /** The install size in bytes when known, else 0. */
    val sizeBytes: Long = 0L,
    /** Per-store identifiers the install needs (Epic namespace / catalog id / app name, Amazon entitlement / sku). */
    val extra: Map<String, String> = emptyMap(),
) {
    val isDiscounted: Boolean get() = discountPercent > 0 && originalPrice.isNotBlank()
    /** Stable key across stores ("gog:1207658924"). */
    val key: String get() = "${store.id}:$id"
}

/** A store's storefront shelves: what the Store tab shows above the account's own library. */
class StoreShelves(
    val whatsNew: List<CatalogItem> = emptyList(),
    val deals: List<CatalogItem> = emptyList(),
    val free: List<CatalogItem> = emptyList(),
    val trending: List<CatalogItem> = emptyList(),
) {
    val isEmpty: Boolean get() = whatsNew.isEmpty() && deals.isEmpty() && free.isEmpty() && trending.isEmpty()
    val all: List<CatalogItem> get() = (whatsNew + deals + free + trending).distinctBy { it.id }
}

/** A game a store installed, as found on disk through its sidecar. */
class InstalledStoreGame(val sidecar: StoreGameSidecar, val folder: java.io.File) {
    val key: String get() = "${sidecar.store.id}:${sidecar.id}"
}

private val TEMPLATE_KEY = Regex("product_(description|feature)_\\d+")
private val HTML_TAG = Regex("<[^>]+>")
private val ENTITY = Regex("&(#\\d+|#x[0-9a-fA-F]+|[a-zA-Z]+);")
private val NAMED_ENTITIES = mapOf("amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'", "nbsp" to " ", "ndash" to "–", "mdash" to "—", "hellip" to "…", "trade" to "™", "reg" to "®", "copy" to "©")

/**
 * Store copy as plain text: GOG's account endpoints return template keys
 * (`product_description_2015545325`) for some products instead of words, and every store's
 * descriptions carry HTML. Those keys go, tags go, entities are decoded, whitespace is collapsed;
 * "" when nothing readable is left, so a page shows nothing rather than a placeholder.
 */
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

/** Human sizes the way the Downloads page and the cards print them ("810.2 MB", "3.9 GB"). */
fun formatBytes(bytes: Long): String = when {
    bytes >= 1_073_741_824L -> "%.1f GB".format(bytes / 1_073_741_824.0)
    bytes >= 1_048_576L -> "%.1f MB".format(bytes / 1_048_576.0)
    bytes >= 1024L -> "%.0f KB".format(bytes / 1024.0)
    bytes >= 0L -> "$bytes B"
    else -> ""
}

fun formatSpeed(bytesPerSec: Long): String = if (bytesPerSec <= 0L) "" else "${formatBytes(bytesPerSec)}/s"
