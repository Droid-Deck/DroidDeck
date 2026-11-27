package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class BottomIdleHomeTest {
    private val games = listOf(
        Library.SteamGame(620, "Portal 2", null, "", lastPlayed = 100),
        Library.SteamGame(1145360, "Hades", null, "", lastPlayed = 300),
        Library.SteamGame(504230, "Celeste", null, ""),
    )

    @Test
    fun prefersTheGameSelectedInSteam() {
        val (game, highlighted) = IdleHome.pick(games, 504230)!!
        assertEquals(504230, game.appId)
        assertEquals(true, highlighted)
    }

    @Test
    fun fallsBackToTheMostRecentGame() {
        val (game, highlighted) = IdleHome.pick(games, 999)!!
        assertEquals(1145360, game.appId)
        assertEquals(false, highlighted)
        assertEquals(1145360, IdleHome.pick(games, null)!!.first.appId)
    }

    @Test
    fun nothingPlayedAndNothingSelected() {
        assertNull(IdleHome.pick(listOf(Library.SteamGame(1, "A", null, "")), null))
    }

    @Test
    fun readsSteamsOverviewOfTheHighlightedGame() {
        val o = IdleHome.parseOverview("""{"name":"Sonic Mania","minutes":864,"last":1764000000}""")!!
        assertEquals("Sonic Mania", o.name)
        assertEquals(864L, o.minutes)
        assertEquals(1764000000L, o.lastPlayed)
        assertNull(IdleHome.parseOverview(null))
        assertNull(IdleHome.parseOverview("""{"name":""}"""))
        assertNull(IdleHome.parseOverview("nope"))
        assertEquals(true, IdleHome.overviewExpression(584400).contains("GetAppOverviewByAppID?.(584400)"))
    }
}
