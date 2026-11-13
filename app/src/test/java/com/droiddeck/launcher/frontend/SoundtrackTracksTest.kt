package com.droiddeck.launcher.frontend

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SoundtrackTracksTest {
    private class T(val title: String, val ms: Long)
    private val items = listOf(T("banana", 300), T("Apple", 100), T("cherry", 200))
    private fun sorted(s: SoundtrackTracks.Sort) = SoundtrackTracks.sort(items, s, { it.title }, { it.ms }).map { it.title }

    @Test fun defaultKeepsOrder() = assertEquals(listOf("banana", "Apple", "cherry"), sorted(SoundtrackTracks.Sort.DEFAULT))
    @Test fun titleAscIgnoresCase() = assertEquals(listOf("Apple", "banana", "cherry"), sorted(SoundtrackTracks.Sort.TITLE_ASC))
    @Test fun titleDesc() = assertEquals(listOf("cherry", "banana", "Apple"), sorted(SoundtrackTracks.Sort.TITLE_DESC))
    @Test fun longestFirst() = assertEquals(listOf("banana", "cherry", "Apple"), sorted(SoundtrackTracks.Sort.LONGEST))
    @Test fun shortestFirst() = assertEquals(listOf("Apple", "cherry", "banana"), sorted(SoundtrackTracks.Sort.SHORTEST))

    @Test fun blankQueryMatchesEverything() = assertTrue(SoundtrackTracks.matches("  ", "anything"))
    @Test fun wordsMayHitDifferentFields() = assertTrue(SoundtrackTracks.matches("main theme", "Main Menu", "Theme Composer"))
    @Test fun everyWordMustMatch() = assertFalse(SoundtrackTracks.matches("main boss", "Main Menu", "Composer"))
    @Test fun matchIsCaseInsensitive() = assertTrue(SoundtrackTracks.matches("MENU", "main menu"))

    @Test fun rankOrdersExactPrefixContains() {
        assertEquals(0, SoundtrackTracks.rank("Menu", "menu"))
        assertEquals(1, SoundtrackTracks.rank("men", "Menu Theme"))
        assertEquals(2, SoundtrackTracks.rank("theme", "Menu Theme"))
    }
}
