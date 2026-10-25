package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryEntriesTest {
    private fun owned(store: Store, id: String, title: String = id) = CatalogItem(store, id, title, imageUrl = null, owned = true)

    @Test fun anInstalledStoreGameClaimsItsOwnedProductSoItShowsOnce() {
        val games = listOf(
            Library.SteamGame(440, "Team Fortress 2", null, "Steam library"),
            Library.SteamGame(-5, "Cyberpunk", null, Library.ADDED, source = "gog", storeId = "1423049311"),
        )
        val library = mapOf(Store.GOG to listOf(owned(Store.GOG, "1423049311", "Cyberpunk 2077"), owned(Store.GOG, "1207658924", "Witcher")))
        val entries = libraryEntries(games, library, emptySet())
        assertEquals(listOf("app:440", "app:-5", "app:gog:1207658924"), entries.map { it.key })
        assertEquals("gog:1423049311", entries[1].item?.key)
        assertTrue(entries[1].installed)
        assertFalse(entries[2].installed)
        assertNull(entries[2].game)
    }

    @Test fun theEpicLauncherIsNotAGame() {
        val launcher = Library.SteamGame(0, Library.EPIC_LAUNCHER_NAME, null, Library.EPIC_LAUNCHER)
        assertEquals(emptyList<String>(), libraryEntries(listOf(launcher), emptyMap(), emptySet()).map { it.key })
    }

    @Test fun aStoreInstallTheLibraryHasNotScannedYetCountsAsInstalled() {
        val entries = libraryEntries(emptyList(), mapOf(Store.EPIC to listOf(owned(Store.EPIC, "fn"))), setOf("epic:fn"))
        assertTrue(entries.single().installed)
    }

    @Test fun installedGamesComeFirstKeepingTheirSortedOrder() {
        val entries = listOf("a", "b", "c", "d").map { LibraryEntry(it, "steam", it, null, null, installed = it == "b" || it == "d") }
        assertEquals(listOf("b", "d", "a", "c"), installedFirst(entries).map { it.key })
    }

    @Test fun gameKeysNameEitherAnAppOrAStoreProduct() {
        GameRef.parse("app:440").let { assertEquals(440, it.appId); assertNull(it.store) }
        GameRef.parse("app:-12").let { assertEquals(-12, it.appId) }
        GameRef.parse("app:gog:1207658924").let { assertEquals(Store.GOG, it.store); assertEquals("1207658924", it.storeId); assertNull(it.appId) }
        GameRef.parse("xbox:9NBLGGH4R315").let { assertEquals(Store.XBOX, it.store); assertEquals("9NBLGGH4R315", it.storeId) }
        GameRef.parse("app:nope:1").let { assertNull(it.store); assertNull(it.appId) }
    }
}
