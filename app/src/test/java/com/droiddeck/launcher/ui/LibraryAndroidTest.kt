package com.droiddeck.launcher.ui

import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.HomeLibrary
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.stores.sortGames
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryAndroidTest {
    private fun app(pkg: String, label: String = pkg, game: Boolean = false, installedAt: Long = 0L, userSerial: Long = 0L) =
        HomeApp.LaunchableApp(label, pkg, "$pkg.Main", null, game = game, installedAt = installedAt, userSerial = userSerial)

    private val steam = LibraryEntry("app:440", Library.SOURCE_STEAM, "Team Fortress 2", Library.SteamGame(440, "Team Fortress 2", null, "Steam library"), null, true)

    @Test fun flaggedGamesAndAddedAppsBecomeEntriesAndRemovedOnesDoNot() {
        val apps = listOf(app("a.game", game = true), app("b.tool"), app("c.added"), app("d.removed", game = true))
        val entries = androidEntries(apps, added = setOf("c.added/c.added.Main"), removed = setOf("d.removed/d.removed.Main"))
        assertEquals(listOf("android:a.game/a.game.Main", "android:c.added/c.added.Main"), entries.map { it.key })
        assertTrue(entries.all { it.source == Library.SOURCE_ANDROID && it.installed && it.game == null && it.item == null })
        assertEquals("a.game", entries[0].app?.packageName)
    }

    @Test fun anAndroidEntryNeverUsesTheDetailRouteKeyOfTheAppsPageOrAPcGame() {
        val entry = androidEntries(listOf(app("a.game", game = true)), emptySet(), emptySet()).single()
        assertFalse(entry.key.startsWith("app:"))
        assertNull(entry.storeKey)
        assertEquals(entry.key, entry.favoriteKey)
    }

    @Test fun aWorkProfileCopyIsItsOwnEntry() {
        val entries = androidEntries(listOf(app("a.game", game = true), app("a.game", game = true, userSerial = 10)), emptySet(), emptySet())
        assertEquals(listOf("android:a.game/a.game.Main", "android:a.game/a.game.Main#10"), entries.map { it.key })
    }

    @Test fun addingAndRemovingOnlyRecordsWhatDiffersFromTheGameFlag() {
        val game = app("a.game", game = true)
        val tool = app("b.tool")
        val none = emptySet<String>()
        assertEquals(setOf(tool.key) to none, HomeLibrary.changed(none, none, tool, inLibrary = true))
        assertEquals(none to none, HomeLibrary.changed(setOf(tool.key), none, tool, inLibrary = false))
        assertEquals(none to setOf(game.key), HomeLibrary.changed(none, none, game, inLibrary = false))
        assertEquals(none to none, HomeLibrary.changed(none, setOf(game.key), game, inLibrary = true))
        assertFalse(HomeLibrary.includes(game, none, setOf(game.key)))
        assertTrue(HomeLibrary.includes(tool, setOf(tool.key), none))
        assertFalse(HomeLibrary.includes(tool, none, none))
    }

    @Test fun androidGetsItsOwnSourceAfterAdded() {
        val added = LibraryEntry("app:-5", Library.ADDED, "Game", Library.SteamGame(-5, "Game", null, Library.ADDED, source = Library.ADDED), null, true)
        val android = androidEntries(listOf(app("a.game", game = true)), emptySet(), emptySet())
        assertEquals(listOf("steam", "added", "android"), librarySources(listOf(steam, added) + android, emptyList()))
        assertEquals(listOf("steam"), librarySources(listOf(steam), emptyList()))
    }

    @Test fun aFavoritedAndroidEntryShowsUnderFavoritesAndItsOwnSource() {
        val entry = androidEntries(listOf(app("a.game", game = true)), emptySet(), emptySet()).single()
        val favorites = setOf(entry.favoriteKey)
        assertEquals(listOf(LIBRARY_FAVORITES, "steam", "android"), librarySources(listOf(steam, entry), emptyList(), favorites = favorites))
        assertTrue(inLibrarySource(LIBRARY_FAVORITES, entry, favorites))
        assertTrue(inLibrarySource(Library.SOURCE_ANDROID, entry, favorites))
        assertFalse(inLibrarySource(Library.SOURCE_STEAM, entry, favorites))
    }

    @Test fun androidEntriesSortByTitleAndInstallDateAlongsidePcGames() {
        val entries = listOf(steam) + androidEntries(
            listOf(app("z.old", "Alto", game = true, installedAt = 1_000), app("y.new", "Zed", game = true, installedAt = 9_000)), emptySet(), emptySet())
        val titles = { sort: String -> sortGames(entries, sort, title = { it.title }, releaseDate = { "" }, installedAt = { it.game?.installedAt ?: it.app?.installedAt ?: 0L }).map { it.title } }
        assertEquals(listOf("Alto", "Team Fortress 2", "Zed"), titles("titleAsc"))
        assertEquals(listOf("Zed", "Alto", "Team Fortress 2"), titles("recentlyInstalled"))
    }
}
