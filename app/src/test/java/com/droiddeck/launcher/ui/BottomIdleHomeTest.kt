package com.droiddeck.launcher.ui

import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

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
}
