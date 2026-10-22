package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class DetailCatalogItemTest {
    private fun item(store: Store, id: String, title: String) = CatalogItem(store, id, title, imageUrl = null)
    private val shelves = StoreShelves(trending = listOf(item(Store.XBOX, "9SHELF000001", "Shelf game")))

    @Test fun aSearchOnlyResultKeepsItsMetadataOnTheDetailPage() {
        val found = listOf(item(Store.XBOX, "9SEARCH00001", "Echo Weaver"))
        assertEquals("Echo Weaver", detailCatalogItem(Store.XBOX, "9SEARCH00001", emptyList(), found, shelves)?.title)
    }

    @Test fun theLibraryWinsOverSearchAndShelvesForTheSameProduct() {
        val library = listOf(item(Store.XBOX, "9SEARCH00001", "Echo Weaver").copy(owned = true, sizeBytes = 42))
        val found = listOf(item(Store.XBOX, "9SEARCH00001", "Echo Weaver").copy(owned = false))
        val resolved = detailCatalogItem(Store.XBOX, "9SEARCH00001", library, found, shelves)!!
        assertEquals(true, resolved.owned)
        assertEquals(42L, resolved.sizeBytes)
    }

    @Test fun shelvesRemainAFallbackAndTheItemStaysInItsStore() {
        assertEquals("Shelf game", detailCatalogItem(Store.XBOX, "9SHELF000001", emptyList(), emptyList(), shelves)?.title)
        assertNull(detailCatalogItem(Store.GOG, "9SEARCH00001", emptyList(), listOf(item(Store.XBOX, "9SEARCH00001", "Echo Weaver")), shelves))
        assertNull(detailCatalogItem(Store.XBOX, "9UNKNOWN0001", emptyList(), emptyList(), shelves))
    }
}
