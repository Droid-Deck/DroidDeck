package com.droiddeck.launcher.ui

import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Test

/** Amazon has no catalog of its own: Installed and Library only, and Store falls back to Library. */
class StoreTabsTest {
    @Test fun amazonHasNoStoreOrAllTab() {
        assertEquals(listOf("installed", "library"), tabsFor(Store.AMAZON))
        assertEquals("library", tabFor(Store.AMAZON, "store"))
        assertEquals("library", tabFor(Store.AMAZON, "all"))
        assertEquals("installed", tabFor(Store.AMAZON, "installed"))
        assertEquals("store", tabFor(Store.GOG, "store"))
    }
}
