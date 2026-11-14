package com.droiddeck.launcher.ui

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameScreenSwapTest {
    private fun wanted(
        requested: Boolean = true,
        activityStarted: Boolean = true,
        sessionEnded: Boolean = false,
        inPip: Boolean = false,
        glassesOutput: Boolean = false,
        bottomScreenFree: Boolean = true,
    ) = GameScreenSwap.wanted(requested, activityStarted, sessionEnded, inPip, glassesOutput, bottomScreenFree)

    @Test fun thePickedSwapPutsTheGameOnTheBottomScreen() {
        assertTrue(wanted())
    }

    @Test fun withoutAPickTheGameStaysOnTop() {
        assertFalse(wanted(requested = false))
    }

    @Test fun theGameComesBackToTheTopWhenTheSessionIsHiddenOrEnds() {
        assertFalse(wanted(activityStarted = false))
        assertFalse(wanted(sessionEnded = true))
        assertFalse(wanted(inPip = true))
    }

    @Test fun glassesAndAHeldBottomScreenWin() {
        assertFalse(wanted(glassesOutput = true))
        assertFalse(wanted(bottomScreenFree = false))
    }
}
