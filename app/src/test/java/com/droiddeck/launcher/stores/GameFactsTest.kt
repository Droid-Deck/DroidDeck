package com.droiddeck.launcher.stores

import java.util.Locale
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

/** The name match that finds a store game on Steam and HowLongToBeat, and how its facts read. */
class GameFactsTest {
    private val saved = Locale.getDefault()

    @Before fun english() = Locale.setDefault(Locale.US)
    @After fun restore() = Locale.setDefault(saved)

    @Test fun theSameNameMatchesWhateverItsMarksAndPunctuation() {
        assertEquals(3, TitleMatch.score("The Witcher 3: Wild Hunt", "The Witcher 3: Wild Hunt"))
        assertEquals(3, TitleMatch.score("DOOM Eternal™", "DOOM Eternal"))
        assertEquals(3, TitleMatch.score("Ori & the Will of the Wisps", "Ori and the Will of the Wisps"))
    }

    @Test fun anEditionIsTheSameGame() {
        assertEquals(2, TitleMatch.score("The Witcher 3: Wild Hunt - Complete Edition", "The Witcher 3: Wild Hunt"))
        assertEquals(2, TitleMatch.score("Control", "Control Ultimate Edition"))
        assertEquals(2, TitleMatch.score("Fallout: New Vegas - Ultimate Edition", "Fallout: New Vegas"))
    }

    @Test fun aSubtitleOnEitherSideStillMatchesButLessWell() {
        assertEquals(1, TitleMatch.score("The Witcher 3: Wild Hunt", "The Witcher 3: Wild Hunt — Remastered"))
        assertEquals(1, TitleMatch.score("Alan Wake Remastered (2021)", "Alan Wake Remastered"))
    }

    @Test fun aSequelOrASiblingIsNotTheSameGame() {
        assertEquals(0, TitleMatch.score("Hades", "Hades II"))
        assertEquals(0, TitleMatch.score("Batman: Arkham City", "Batman: Arkham Knight"))
        assertEquals(0, TitleMatch.score("Portal", "Portal 2"))
        assertEquals(0, TitleMatch.score("", "Portal"))
    }

    @Test fun thePickPrefersTheClosestNameThenTheSitesOwnOrder() {
        val results = listOf("Hades II", "The Witcher 3: Wild Hunt — Songs of the Past", "Hades", "Hades")
        assertEquals(2, TitleMatch.pick("Hades", results.withIndex().toList()) { listOf(it.value) }?.index)
        assertNull(TitleMatch.pick("Hades", listOf("Hades II", "Hadesville")) { listOf(it) })
        // An alias counts as a name.
        assertEquals("x", TitleMatch.pick("The Witcher III: Wild Hunt", listOf("x" to "The Witcher 3: Wild Hunt, The Witcher III: Wild Hunt")) { it.second.split(',') }?.first)
    }

    @Test fun theSearchTermDropsMarksAndTheEdition() {
        assertEquals("The Witcher 3 Wild Hunt", TitleMatch.searchTerm("The Witcher® 3: Wild Hunt - Game of the Year Edition"))
        assertEquals("Disco Elysium The Final Cut", TitleMatch.searchTerm("Disco Elysium - The Final Cut"))
        assertEquals("Marvel's Spider-Man Remastered", TitleMatch.searchTerm("Marvel's Spider-Man Remastered"))
        assertEquals("Deluxe Ski Jump", TitleMatch.searchTerm("Deluxe Ski Jump"))
    }

    @Test fun eachStoresDateReadsTheSameAndAPlaceholderIsDropped() {
        assertEquals("May 19, 2015", GameFacts.releaseLabel("2015-05-19T00:00:00+02:00"))
        assertEquals("May 19, 2015", GameFacts.releaseLabel("2015.05.19"))
        assertEquals("Dec 19, 2022", GameFacts.releaseLabel("2022-12-19T16:00:00.000Z"))
        assertNull(GameFacts.releaseLabel("2099-01-01T00:00:00.000Z"))
        assertNull(GameFacts.releaseLabel("null"))
        assertNull(GameFacts.releaseLabel(""))
    }

    @Test fun hoursRoundToTheNearestHalf() {
        assertEquals("51½", GameFacts.hours(186_070))
        assertEquals("52", GameFacts.hours(52 * 3600 + 500))
        assertEquals("51½", GameFacts.hours(51 * 3600 + 1900))
        assertEquals("1", GameFacts.hours(3600))
        assertNull(GameFacts.hours(2_400))
    }
}
