package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryHiddenTest {
    private fun steam(id: Int, name: String) = LibraryEntry("app:$id", Library.SOURCE_STEAM, name, Library.SteamGame(id, name, null, "Steam library"), null, true)

    private val tf2 = steam(440, "Team Fortress 2")
    private val cs = steam(10, "Counter-Strike")
    private val hiddenSet = setOf(tf2.favoriteKey)
    private val hidden: (LibraryEntry) -> Boolean = { it.favoriteKey in hiddenSet }

    @Test fun hiddenGamesLeaveAllAndTheirStoreTab() {
        assertFalse(inLibrarySource(LIBRARY_ALL, tf2, emptySet(), hidden))
        assertFalse(inLibrarySource(Library.SOURCE_STEAM, tf2, emptySet(), hidden))
        assertTrue(inLibrarySource(LIBRARY_ALL, cs, emptySet(), hidden))
        assertTrue(inLibrarySource(Library.SOURCE_STEAM, cs, emptySet(), hidden))
    }

    @Test fun hiddenGamesLeaveFavorites() {
        assertFalse(inLibrarySource(LIBRARY_FAVORITES, tf2, setOf(tf2.favoriteKey), hidden))
    }

    @Test fun theHiddenSourceHoldsOnlyHiddenGames() {
        assertTrue(inLibrarySource(LIBRARY_HIDDEN, tf2, emptySet(), hidden))
        assertFalse(inLibrarySource(LIBRARY_HIDDEN, cs, emptySet(), hidden))
    }

    @Test fun theHiddenTabIsLastAndOnlyWhenTurnedOn() {
        val entries = listOf(tf2, cs)
        assertEquals(listOf(Library.SOURCE_STEAM), librarySources(entries, emptyList(), hidden = hidden))
        assertEquals(listOf(Library.SOURCE_STEAM, LIBRARY_HIDDEN), librarySources(entries, emptyList(), hidden = hidden, offTabs = emptySet()))
    }

    @Test fun theHiddenTabSortsLastUnlessMoved() {
        assertEquals(listOf(LIBRARY_FAVORITES, Library.SOURCE_STEAM, "epic", Library.ADDED, LIBRARY_HIDDEN), placeLibraryTabs(listOf("epic"), DEFAULT_LIBRARY_PLACES))
        val moved = placeLibraryTabs(listOf("epic"), listOf(LIBRARY_HIDDEN to "epic"))
        assertEquals(listOf("epic", LIBRARY_HIDDEN), moved.filter { it == "epic" || it == LIBRARY_HIDDEN })
    }

    @Test fun aStoreTabWithOnlyHiddenGamesDisappears() {
        assertEquals(emptyList<String>(), librarySources(listOf(tf2), emptyList(), hidden = hidden))
    }
}
