package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Test

class StoreLibrarySortTest {
    private fun game(id: String, title: String, bytes: Long = 0, store: Store = Store.GOG) =
        CatalogItem(store, id, title, imageUrl = null, owned = true, sizeBytes = bytes)

    @Test fun namesSortCaseInsensitivelyInBothDirectionsForEveryStore() {
        Store.entries.forEach { store ->
            val games = listOf(game("z", "zebra", store = store), game("a", "Alpha", store = store), game("b", "beta", store = store))
            assertEquals(listOf("a", "b", "z"), sortStoreLibrary(games, StoreLibrarySort.NAME_ASC).map { it.id })
            assertEquals(listOf("z", "b", "a"), sortStoreLibrary(games, StoreLibrarySort.NAME_DESC).map { it.id })
            assertEquals(listOf("z", "a", "b"), games.map { it.id })
        }
    }

    @Test fun sizesAreNumericAndUnknownSizesAreLastInBothDirections() {
        val games = listOf(
            game("unknown", "Unknown"), game("large", "Large", 158_000_000_000L),
            game("negative", "Missing", -1), game("small", "Small", 655_800_000L),
            game("medium", "Medium", 1_500_000_000L),
        )
        assertEquals(listOf("small", "medium", "large", "negative", "unknown"),
            sortStoreLibrary(games, StoreLibrarySort.SIZE_ASC).map { it.id })
        assertEquals(listOf("large", "medium", "small", "negative", "unknown"),
            sortStoreLibrary(games, StoreLibrarySort.SIZE_DESC).map { it.id })
    }

    @Test fun equalSizesAndUnknownSizesUseAscendingNamesWithStableKeys() {
        val games = listOf(game("z", "beta", 10), game("b", "ALPHA", 10), game("a", "alpha", 10), game("u", "zebra"), game("v", "delta"))
        listOf(StoreLibrarySort.SIZE_ASC, StoreLibrarySort.SIZE_DESC).forEach { order ->
            assertEquals(listOf("a", "b", "z", "v", "u"), sortStoreLibrary(games, order).map { it.id })
        }
    }

    @Test fun snapshotOverridesCatalogSizesAndUsesStoreQualifiedKeys() {
        val gog = game("same", "GOG", 100)
        val epic = game("same", "Epic", 200, Store.EPIC)
        val amazon = game("same", "Amazon", store = Store.AMAZON)
        val sizes = mapOf(gog.key to 300L, amazon.key to 50L)
        assertEquals(listOf(amazon.key, epic.key, gog.key),
            sortStoreLibrary(listOf(gog, epic, amazon), StoreLibrarySort.SIZE_ASC, sizes).map { it.key })
    }

    @Test fun newlyLearnedSizesDoNotChangeOrderUntilSnapshotIsReplaced() {
        val games = listOf(game("large", "Large", 100), game("small", "Small"))
        val liveSizes = mutableMapOf<String, Long>()
        val snapshot = liveSizes.toMap()
        liveSizes[games[1].key] = 10
        assertEquals(listOf("large", "small"), sortStoreLibrary(games, StoreLibrarySort.SIZE_ASC, snapshot).map { it.id })
        assertEquals(listOf("small", "large"), sortStoreLibrary(games, StoreLibrarySort.SIZE_ASC, liveSizes.toMap()).map { it.id })
    }

    @Test fun filteredResultsUseTheSameOrder() {
        val games = listOf(game("two", "Game Two", 20), game("other", "Other", 1), game("one", "Game One", 10))
        val matches = games.filter { it.title.contains("Game", ignoreCase = true) }
        assertEquals(listOf("one", "two"), sortStoreLibrary(matches, StoreLibrarySort.SIZE_ASC).map { it.id })
        assertEquals(listOf("two", "one"), sortStoreLibrary(matches, StoreLibrarySort.NAME_DESC).map { it.id })
    }

    @Test fun emptyAndEntirelyUnknownLibrariesAreHandled() {
        StoreLibrarySort.entries.forEach { order ->
            assertEquals(emptyList<CatalogItem>(), sortStoreLibrary(emptyList(), order))
        }
        val games = listOf(game("z", "Zebra"), game("a", "Alpha"))
        assertEquals(listOf("a", "z"), sortStoreLibrary(games, StoreLibrarySort.SIZE_DESC).map { it.id })
    }
}
