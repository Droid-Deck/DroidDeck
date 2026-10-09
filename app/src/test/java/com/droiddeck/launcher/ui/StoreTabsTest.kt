package com.droiddeck.launcher.ui

import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Test

/** Amazon has no catalog of its own: Installed and Library only, and Store falls back to Library. */
class StoreTabsTest {
    @Test fun storeAndLibraryAmazonLibraryOnly() {
        assertEquals(listOf("store", "library"), tabsFor(Store.GOG))
        assertEquals(listOf("library"), tabsFor(Store.AMAZON))
        assertEquals("library", tabFor(Store.AMAZON, "store"))
        // An old saved Installed or All tab opens Library.
        assertEquals("library", tabFor(Store.GOG, "all"))
        assertEquals("library", tabFor(Store.EPIC, "installed"))
        assertEquals("store", tabFor(Store.GOG, "store"))
    }
}
