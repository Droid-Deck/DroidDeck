package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SessionAchievementGameTest {
    private val installed = setOf(10L, 20L)

    @Test fun exitingGameCannotRemainSelectedWithoutItsWindow() {
        assertNull(SessionAchievementGame.activeAppId(10, listOf(769), installed))
    }

    @Test fun steamOverlayKeepsTheRunningGameSelected() {
        assertEquals(10L, SessionAchievementGame.activeAppId(769, listOf(769, 10), installed))
    }

    @Test fun anotherForegroundAppCannotBorrowABackgroundGamesAchievements() {
        assertNull(SessionAchievementGame.activeAppId(30, listOf(30, 10), installed))
    }

    @Test fun ambiguousBackgroundGamesDoNotPickAnArbitraryGame() {
        assertNull(SessionAchievementGame.activeAppId(769, listOf(769, 10, 20), installed))
    }

    @Test fun theForegroundGameWinsWhenSeveralGamesAreRunning() {
        assertEquals(20L, SessionAchievementGame.activeAppId(20, listOf(769, 10, 20), installed))
    }
}
