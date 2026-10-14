package com.droiddeck.launcher.stores

import java.util.Locale

internal enum class StoreLibrarySort {
    NAME_ASC, NAME_DESC, SIZE_ASC, SIZE_DESC;

    val bySize: Boolean get() = this == SIZE_ASC || this == SIZE_DESC
}

internal fun sortStoreLibrary(
    items: List<CatalogItem>,
    order: StoreLibrarySort,
    sizes: Map<String, Long> = emptyMap(),
): List<CatalogItem> {
    val names = compareBy<CatalogItem> { it.title.lowercase(Locale.ROOT) }.thenBy { it.key }
    fun size(item: CatalogItem) = sizes[item.key] ?: item.sizeBytes
    val comparator = when (order) {
        StoreLibrarySort.NAME_ASC -> names
        StoreLibrarySort.NAME_DESC -> compareByDescending<CatalogItem> { it.title.lowercase(Locale.ROOT) }.thenBy { it.key }
        StoreLibrarySort.SIZE_ASC, StoreLibrarySort.SIZE_DESC -> {
            val known = compareBy<CatalogItem> { size(it) <= 0L }
            val bytes = if (order == StoreLibrarySort.SIZE_ASC) compareBy<CatalogItem> { size(it).coerceAtLeast(0L) }
                else compareByDescending<CatalogItem> { size(it).coerceAtLeast(0L) }
            known.then(bytes).then(names)
        }
    }
    return items.sortedWith(comparator)
}
