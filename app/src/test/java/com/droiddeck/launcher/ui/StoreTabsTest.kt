package com.droiddeck.launcher.ui

import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Test

/** A store's modes are Store, Library and Installed; Amazon has no catalog of its own, so no Store. */
class StoreTabsTest {
    @Test fun storeLibraryInstalledAmazonWithoutStore() {
        assertEquals(listOf("store", "library", "installed"), tabsFor(Store.GOG))
        assertEquals(listOf("library", "installed"), tabsFor(Store.AMAZON))
        assertEquals("library", tabFor(Store.AMAZON, "store"))
        // An old saved All tab opens Library; Installed is a mode again.
        assertEquals("library", tabFor(Store.GOG, "all"))
        assertEquals("installed", tabFor(Store.EPIC, "installed"))
        assertEquals("store", tabFor(Store.GOG, "store"))
    }
}
