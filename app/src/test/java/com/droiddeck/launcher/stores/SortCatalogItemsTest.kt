package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Test

class SortCatalogItemsTest {
    private fun item(id: String, title: String, size: Long = 0, releaseDate: String = "") =
        CatalogItem(Store.GOG, id, title, imageUrl = null, sizeBytes = size, releaseDate = releaseDate, owned = true)

    private val a = item("a", "Alpha")
    private val b = item("b", "Beta")
    private val c = item("c", "gamma", size = 300, releaseDate = "2021-05-01")
    private val d = item("d", "Delta", size = 900, releaseDate = "2023-01-15")
    private val items = listOf(a, b, c, d)

    @Test fun titleAscendingAndDescending() {
        assertEquals(listOf(a, b, d, c), sortCatalogItems(items, "titleAsc"))
        assertEquals(listOf(c, d, b, a), sortCatalogItems(items, "titleDesc"))
    }

    @Test fun sizeDescendingTiesByTitle() {
        assertEquals(listOf(d, c, a, b), sortCatalogItems(items, "sizeDesc"))
    }

    @Test fun releaseDateDescending() {
        assertEquals(listOf(d, c, a, b), sortCatalogItems(items, "releaseDateDesc"))
    }

    @Test fun lastPlayedDescendingWithTies() {
        val played = mapOf(a.key to 5L, c.key to 50L)
        assertEquals(listOf(c, a, b, d), sortCatalogItems(items, "lastPlayed", lastPlayed = { played[it.key] ?: 0L }))
    }

    @Test fun recentlyInstalledDescending() {
        val installed = mapOf(b.key to 10L, d.key to 100L)
        assertEquals(listOf(d, b, a, c), sortCatalogItems(items, "recentlyInstalled", installedAt = { installed[it.key] ?: 0L }))
    }

    @Test fun unknownSortReturnsInput() {
        assertEquals(items, sortCatalogItems(items, "nope"))
    }
}
