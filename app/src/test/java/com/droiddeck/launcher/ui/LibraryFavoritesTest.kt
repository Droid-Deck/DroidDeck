package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFavoritesTest {
    private fun steam(id: Int, name: String) = LibraryEntry("app:$id", Library.SOURCE_STEAM, name, Library.SteamGame(id, name, null, "Steam library"), null, true)

    private fun owned(id: String, name: String): LibraryEntry {
        val item = CatalogItem(Store.GOG, id, name, imageUrl = null, owned = true)
        return LibraryEntry("app:gog:$id", Store.GOG.id, name, null, item, false)
    }

    @Test fun togglingAddsThenRemovesAKey() {
        val on = toggled(emptySet(), "app:440")
        assertEquals(setOf("app:440"), on)
        assertEquals(emptySet<String>(), toggled(on, "app:440"))
    }

    @Test fun aStoreGameIsKeyedByItsStoreProductSoInstallingItKeepsTheHeart() {
        val entry = owned("123", "Witcher")
        assertEquals("gog:123", entry.favoriteKey)
        val installed = LibraryEntry("app:-5", "gog", "Game", Library.SteamGame(-5, "Game", null, Library.ADDED, source = "gog", storeId = "123"), null, true)
        assertEquals(entry.favoriteKey, installed.favoriteKey)
    }

    @Test fun aGameWithoutAStoreIsKeyedByItsLibraryKey() {
        assertEquals("app:440", steam(440, "Team Fortress 2").favoriteKey)
    }

    @Test fun theFavoritesSourceKeepsOnlyHeartedGamesFromEveryStore() {
        val tf2 = steam(440, "Team Fortress 2")
        val witcher = owned("123", "Witcher")
        val other = steam(10, "Counter-Strike")
        val hearts = setOf(tf2.favoriteKey, witcher.favoriteKey)
        val shown = listOf(tf2, witcher, other).filter { inLibrarySource(LIBRARY_FAVORITES, it, hearts) }
        assertEquals(listOf(tf2, witcher), shown)
        assertTrue(inLibrarySource(LIBRARY_ALL, other, hearts))
        assertFalse(inLibrarySource(Store.GOG.id, tf2, hearts))
    }

    @Test fun theFavoritesTabComesFirstAndOnlyAppearsOnceSomethingIsHearted() {
        val entries = listOf(steam(440, "Team Fortress 2"), owned("123", "Witcher"))
        assertEquals(listOf("steam", "gog"), librarySources(entries, listOf("gog")))
        assertEquals(listOf(LIBRARY_FAVORITES, "steam", "gog"), librarySources(entries, listOf("gog"), favorites = setOf("gog:123")))
        assertEquals(listOf("steam", "gog"), librarySources(entries, listOf("gog"), favorites = setOf("gog:999")))
    }
}
