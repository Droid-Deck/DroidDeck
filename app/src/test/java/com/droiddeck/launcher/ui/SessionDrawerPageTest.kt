package com.droiddeck.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class SessionDrawerPageTest {
    @Test fun navigationStartsWithControllerAndGoesStraightToDisplay() {
        val pages = sessionDrawerPages(hasAgent = false)
        assertEquals(SessionDrawerPage.CONTROLLER, pages.first())
        assertEquals(SessionDrawerPage.DISPLAY, pages.step(SessionDrawerPage.CONTROLLER, 1))
        assertEquals(SessionDrawerPage.SESSION, pages.step(SessionDrawerPage.CONTROLLER, -1))
        assertEquals(SessionDrawerPage.CONTROLLER, pages.step(SessionDrawerPage.SESSION, 1))
        assertEquals(SessionDrawerPage.HUD, pages.step(SessionDrawerPage.DISPLAY, 1))
        assertEquals(SessionDrawerPage.EFFECTS, pages.step(SessionDrawerPage.HUD, 1))
        assertEquals(SessionDrawerPage.VR, pages.step(SessionDrawerPage.EFFECTS, 1))
        assertEquals(SessionDrawerPage.GAMES, pages.step(SessionDrawerPage.VR, 1))
        assertEquals(SessionDrawerPage.SESSION, pages.step(SessionDrawerPage.GAMES, 1))
    }

    @Test fun glassesAddTheirPageAfterController() {
        val pages = sessionDrawerPages(hasGlasses = true, hasAgent = false)
        assertEquals(SessionDrawerPage.GLASSES, pages.step(SessionDrawerPage.CONTROLLER, 1))
        assertEquals(sessionDrawerPages(hasAgent = false), pages.filter { it != SessionDrawerPage.GLASSES })
    }

    @Test fun theAgentPageComesBeforeSessionOnlyWhenTheTabIsShown() {
        val with = sessionDrawerPages(hasAgent = true)
        assertEquals(SessionDrawerPage.AGENT, with.step(SessionDrawerPage.GAMES, 1))
        assertEquals(SessionDrawerPage.SESSION, with.step(SessionDrawerPage.AGENT, 1))
        assertFalse(SessionDrawerPage.AGENT in sessionDrawerPages(hasAgent = false))
    }
}
