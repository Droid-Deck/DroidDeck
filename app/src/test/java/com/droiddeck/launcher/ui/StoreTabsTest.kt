package com.droiddeck.launcher.ui

import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Test

class StoreTabsTest {
    @Test fun storeLibraryInstalledAmazonWithoutStore() {
        assertEquals(listOf("store", "library", "installed"), tabsFor(Store.GOG))
        assertEquals(listOf("library", "installed"), tabsFor(Store.AMAZON))
        assertEquals("library", tabFor(Store.AMAZON, "store"))
        assertEquals("library", tabFor(Store.GOG, "all"))
        assertEquals("installed", tabFor(Store.EPIC, "installed"))
        assertEquals("store", tabFor(Store.GOG, "store"))
    }
}
