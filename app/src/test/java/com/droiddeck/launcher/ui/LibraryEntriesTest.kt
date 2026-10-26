package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.xbox.XboxLibraryFilter
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

    @Test fun aSavedCloudGameStaysNotInstalledAndKeepsItsLicenseFilters() {
        val game = Library.SteamGame(-20, "Cloud game", null, Library.XBOX_CLOUD, source = "xbox", storeId = "cloud")
        val product = owned(Store.XBOX, "cloud").copy(subscription = true, extra = mapOf("cloud" to "1"))
        val entries = libraryEntries(listOf(game), mapOf(Store.XBOX to listOf(product)), emptySet())
        val entry = entries.single()
        assertFalse(entry.installed)
        for (filter in XboxLibraryFilter.ALL_FILTERS) assertTrue(filter, keepsXbox(filter, entry))
        assertEquals(listOf("local", entry.key), installedFirst(listOf(entry, LibraryEntry("local", "steam", "Local", null, null, true))).map { it.key })
    }

    @Test fun anInstalledPcCopyTakesPrecedenceOverTheSavedCloudRow() {
        val local = Library.SteamGame(-5, "PC game", null, Library.ADDED, source = "xbox", storeId = "same")
        val cloud = Library.SteamGame(-20, "PC game", null, Library.XBOX_CLOUD, source = "xbox", storeId = "same")
        val entries = libraryEntries(listOf(cloud, local), mapOf(Store.XBOX to listOf(owned(Store.XBOX, "same"))), setOf("xbox:same"))
        assertEquals(1, entries.size)
        assertEquals(local, entries.single().game)
        assertTrue(entries.single().installed)
    }

    @Test fun aSavedCloudGameWithoutACatalogStillAppearsInTheCloudFilter() {
        val cloud = Library.SteamGame(-20, "Cloud game", null, Library.XBOX_CLOUD, source = "xbox", storeId = "cloud")
        val entry = libraryEntries(listOf(cloud), emptyMap(), emptySet()).single()
        assertFalse(entry.installed)
        assertTrue(keepsXbox(XboxLibraryFilter.CLOUD, entry))
        assertFalse(keepsXbox(XboxLibraryFilter.OWNED, entry))
    }

    @Test fun distinctLocalShortcutsForTheSameProductArePreserved() {
        val first = Library.SteamGame(-5, "First install", null, Library.ADDED, source = "xbox", storeId = "same")
        val second = Library.SteamGame(-6, "Second install", null, Library.ADDED, source = "xbox", storeId = "same")
        val cloud = Library.SteamGame(-20, "Cloud game", null, Library.XBOX_CLOUD, source = "xbox", storeId = "same")
        val entries = libraryEntries(listOf(cloud, first, second), mapOf(Store.XBOX to listOf(owned(Store.XBOX, "same"))), setOf("xbox:same"))
        assertEquals(listOf(first, second), entries.map { it.game })
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
