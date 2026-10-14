package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Test

class SortCatalogItemsTest {
    private fun item(id: String, title: String, size: Long = 0, releaseDate: String = "") =
        CatalogItem(Store.GOG, id, title, imageUrl = null, sizeBytes = size, releaseDate = releaseDate, owned = true)

    @Test fun titleOrderIsCaseInsensitiveAndDescendingIsItsMirror() {
        val items = listOf(item("1", "Zelda"), item("2", "alpha"), item("3", "Beta"))
        assertEquals(listOf("alpha", "Beta", "Zelda"), sortCatalogItems(items, "titleAsc").map { it.title })
        assertEquals(listOf("Zelda", "Beta", "alpha"), sortCatalogItems(items, "titleDesc").map { it.title })
    }

    @Test fun playedAndInstalledComeFirstWithTitlesBreakingTies() {
        val items = listOf(item("1", "Zelda"), item("2", "alpha"), item("3", "Beta"))
        val played = mapOf("gog:3" to 900L, "gog:1" to 900L)
        assertEquals(listOf("Beta", "Zelda", "alpha"), sortCatalogItems(items, "lastPlayed", lastPlayed = { played[it.key] ?: 0L }).map { it.title })
        val installed = mapOf("gog:2" to 500L)
        assertEquals(listOf("alpha", "Beta", "Zelda"), sortCatalogItems(items, "recentlyInstalled", installedAt = { installed[it.key] ?: 0L }).map { it.title })
    }

    @Test fun sizeAscendingPutsUnknownSizesLast() {
        val items = listOf(item("1", "Zelda"), item("2", "alpha", size = 500), item("3", "Beta", size = 900))
        assertEquals(listOf("alpha", "Beta", "Zelda"), sortCatalogItems(items, "sizeAsc").map { it.title })
        assertEquals(listOf("Beta", "alpha", "Zelda"), sortCatalogItems(items, "sizeDesc").map { it.title })
    }

    @Test fun releaseDatesSortNewestFirstAndUnparsableOnesLast() {
        val items = listOf(item("1", "Zelda", releaseDate = "1986-02-21"), item("2", "alpha", releaseDate = "2023-05-12"), item("3", "Beta", releaseDate = "TBA"))
        assertEquals(listOf("alpha", "Zelda", "Beta"), sortCatalogItems(items, "releaseDateDesc").map { it.title })
    }

    @Test fun unknownSortKeepsTheCatalogsOwnOrder() {
        val items = listOf(item("1", "Zelda"), item("2", "alpha"))
        assertEquals(listOf("Zelda", "alpha"), sortCatalogItems(items, "whatever").map { it.title })
    }
}
