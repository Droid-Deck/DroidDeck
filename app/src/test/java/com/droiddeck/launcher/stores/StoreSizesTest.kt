package com.droiddeck.launcher.stores

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreSizesTest {
    private val ea = CatalogItem(Store.EA, "Origin.OFR.50.0001", "Game", imageUrl = null, owned = true)

    @After fun clear() {
        StoreSizes.known.clear()
    }

    @Test fun anUnresolvedSizeIsUnknown() {
        assertEquals(0L, StoreSizes.size(ea))
    }

    @Test fun aResolvedSizeReplacesTheCatalogValue() {
        StoreSizes.known[ea.key] = 4_200_000_000L
        assertEquals(4_200_000_000L, StoreSizes.size(ea))
    }

    @Test fun aResolvedSizeOnlyAppliesToItsOwnGame() {
        StoreSizes.known[ea.key] = 4_200_000_000L
        assertEquals(0L, StoreSizes.size(ea.copy(id = "Origin.OFR.50.0002")))
        assertEquals(7L, StoreSizes.size(ea.copy(id = "Origin.OFR.50.0002", sizeBytes = 7L)))
    }

    @Test fun anEaSizeDoesNotReplaceAnotherStoresCatalogSize() {
        StoreSizes.known[ea.key] = 4_200_000_000L
        val epic = ea.copy(store = Store.EPIC, id = "Epic.Game", sizeBytes = 7L)
        assertEquals(7L, StoreSizes.size(epic))
    }
}
