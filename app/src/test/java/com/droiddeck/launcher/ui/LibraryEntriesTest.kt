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

    @Test fun ownedSteamGamesJoinTheSteamTabAndAnInstalledOneShowsOnce() {
        val games = listOf(Library.SteamGame(440, "Team Fortress 2", null, "Steam library"))
        val library = mapOf(Store.STEAM to listOf(owned(Store.STEAM, "440", "Team Fortress 2"), owned(Store.STEAM, "620", "Portal 2")))
        val entries = libraryEntries(games, library, emptySet())
        assertEquals(listOf("app:440", "app:steamstore:620"), entries.map { it.key })
        assertEquals(listOf(Library.SOURCE_STEAM, Library.SOURCE_STEAM), entries.map { it.source })
        assertFalse(entries[1].installed)
        assertEquals("app:620", entries[1].favoriteKey)
        assertEquals(listOf(Store.STEAM.id), librarySources(entries, listOf(Store.STEAM.id, "epic")))
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

    @Test fun steamLibraryGamesUseTheSingleSteamStoreFilter() {
        val entries = listOf("added", "gog", "steam", "epic", "xbox").map { LibraryEntry(it, it, it, null, null, true) }
        val sources = librarySources(entries, listOf(Store.STEAM.id, "xbox", "epic", "gog"), always = setOf(Store.STEAM.id))
        assertEquals(listOf(Store.STEAM.id, "xbox", "epic", "gog", "added"), sources)
        assertEquals(1, sources.count { it == Store.STEAM.id })
        assertFalse(Library.SOURCE_STEAM in sources)
        assertTrue(inLibrarySource(Store.STEAM.id, entries.first { it.source == Library.SOURCE_STEAM }, emptySet()))
        assertEquals(
            listOf(Store.STEAM.id),
            librarySources(emptyList(), listOf(Store.STEAM.id), always = setOf(Store.STEAM.id)),
        )
    }

    @Test fun theLibrarysOwnTabsSitAfterTheTabTheyFollow() {
        val entries = listOf("added", "gog", "steam", "epic", "xbox").map { LibraryEntry(it, it, it, null, null, true) }
        val order = listOf("xbox", "epic", "gog")
        assertEquals(listOf(Store.STEAM.id, "xbox", "added", "epic", "gog"), librarySources(entries, order, places = listOf("added" to "xbox")))
        assertEquals(listOf("added", Store.STEAM.id, "xbox", "epic", "gog"), librarySources(entries, order, places = listOf("added" to "", "favorites" to "added", "steam" to "favorites")))
        assertEquals(listOf(Store.STEAM.id, "xbox", "epic", "gog", "added"), librarySources(entries, order, places = listOf("favorites" to "epic", "steam" to "favorites", "added" to "nope")))
    }

    @Test fun legacyCustomTabPlacementStillFollowsTheSteamStoreTab() {
        val migrated = migrateSteamLibraryTabPlaces(listOf("added" to Library.SOURCE_STEAM))
        val arranged = placeLibraryTabs(listOf(Store.STEAM.id), migrated)

        assertEquals(listOf("added" to Store.STEAM.id), migrated)
        assertEquals(arranged.indexOf(Store.STEAM.id) + 1, arranged.indexOf("added"))
    }

    @Test fun hidingTheTabALibraryTabFollowsKeepsItInPlace() {
        val entries = listOf("added", "gog", "epic", "xbox").map { LibraryEntry(it, it, it, null, null, true) }
        assertEquals(listOf("xbox", "added", "gog"), librarySources(entries, listOf("xbox", "epic", "gog"), places = listOf("added" to "epic"), offStores = setOf("epic")))
    }

    @Test fun placesRoundTripThroughTheirOrder() {
        val order = listOf("epic", "favorites", LIBRARY_HIDDEN, "gog", "xbox", "added")
        assertEquals(order, placeLibraryTabs(listOf("epic", "gog", "xbox"), libraryPlacesOf(order)))
        assertEquals(listOf("favorites", "epic", "gog", "added", LIBRARY_HIDDEN), placeLibraryTabs(listOf("epic", "gog"), emptyList()))
    }

    @Test fun librarySourcesIgnoreUnknownAndDuplicateOrderEntriesAndAppendNewStores() {
        val entries = listOf("epic", "gog", "battlenet").map { LibraryEntry(it, it, it, null, null, false) }
        assertEquals(listOf("epic", "gog", "battlenet"), librarySources(entries, listOf("missing", "epic", "epic", "amazon")))
    }

    @Test fun aSteamLibraryGameUsesItsSingleStoreTabAndOtherEmptySourcesKeepTheirPlace() {
        val entries = listOf("steam", "xbox").map { LibraryEntry(it, it, it, null, null, true) }
        assertEquals(listOf(Store.STEAM.id, "xbox", "itch", "gamejolt"), librarySources(entries, listOf("xbox", "itch", "gamejolt"), setOf("itch", "gamejolt")))
    }

    @Test fun gameKeysNameEitherAnAppOrAStoreProduct() {
        GameRef.parse("app:440").let { assertEquals(440, it.appId); assertNull(it.store) }
        GameRef.parse("app:-12").let { assertEquals(-12, it.appId) }
        GameRef.parse("app:gog:1207658924").let { assertEquals(Store.GOG, it.store); assertEquals("1207658924", it.storeId); assertNull(it.appId) }
        GameRef.parse("xbox:9NBLGGH4R315").let { assertEquals(Store.XBOX, it.store); assertEquals("9NBLGGH4R315", it.storeId) }
        GameRef.parse("app:nope:1").let { assertNull(it.store); assertNull(it.appId) }
    }

    @Test fun aCollectionKeepsItsInstalledGamesAndListsTheRestAsNotInstalled() {
        val games = listOf(
            Library.SteamGame(440, "Team Fortress 2", null, "Steam library"),
            Library.SteamGame(-5, "Celeste", null, Library.ADDED, source = "itch", storeId = "a/celeste"),
            Library.SteamGame(-6, "Other", null, Library.ADDED, source = "itch", storeId = "a/other"),
        )
        val library = mapOf(Store.ITCH to listOf(owned(Store.ITCH, "a/celeste"), owned(Store.ITCH, "a/other"), owned(Store.ITCH, "b/owned")))
        val all = libraryEntries(games, library, setOf("itch:a/celeste", "itch:a/other"))
        val collection = listOf(owned(Store.ITCH, "a/celeste"), owned(Store.ITCH, "b/owned"), owned(Store.ITCH, "c/new"))
        val out = collectionEntries(all, collection)
        assertEquals(listOf("app:-5", "app:itch:b/owned", "app:itch:c/new"), out.map { it.key })
        assertEquals(listOf(true, false, false), out.map { it.installed })
    }

    @Test fun anEmptyCollectionHidesEveryGame() {
        val all = libraryEntries(listOf(Library.SteamGame(440, "Team Fortress 2", null, "Steam library")), emptyMap(), emptySet())
        assertEquals(emptyList<String>(), collectionEntries(all, emptyList()).map { it.key })
    }
}
