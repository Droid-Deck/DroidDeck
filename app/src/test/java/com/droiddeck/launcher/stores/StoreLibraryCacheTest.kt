package com.droiddeck.launcher.stores

import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class StoreLibraryCacheTest {
    @Test
    fun listsRoundTripWithTheirStoreAndExtras() {
        val games = listOf(
            CatalogItem(
                Store.UBISOFT, "4553", "Far Cry 3 Blood Dragon", "https://img/wide.jpg", tallImageUrl = "https://img/tall.jpg",
                logoUrl = "https://img/logo.png", owned = true, sizeBytes = 2_000_000L, extra = mapOf("productId" to "205", "spaceId" to "abc"),
                subscription = true, includedWith = "Ubisoft+",
            ),
            CatalogItem(Store.UBISOFT, "1", "No art", null),
        )
        assertEquals(games, StoreLibraryCache.decode(Store.UBISOFT, StoreLibraryCache.encode(games)))
    }

    @Test
    fun anEmptyLibraryStaysEmptyRatherThanUnknown() {
        assertEquals(emptyList<CatalogItem>(), StoreLibraryCache.decode(Store.ROCKSTAR, StoreLibraryCache.encode(emptyList())))
    }
}
