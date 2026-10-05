package com.droiddeck.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class SessionDrawerPageTest {
    @Test fun singleScreenNavigationStartsWithControllerAndSkipsSecondScreen() {
        val pages = sessionDrawerPages(false)
        assertEquals(SessionDrawerPage.CONTROLLER, pages.first())
        assertFalse(SessionDrawerPage.SECOND_SCREEN in pages)
        assertEquals(SessionDrawerPage.DISPLAY, pages.step(SessionDrawerPage.CONTROLLER, 1))
        assertEquals(SessionDrawerPage.SESSION, pages.step(SessionDrawerPage.CONTROLLER, -1))
        assertEquals(SessionDrawerPage.CONTROLLER, pages.step(SessionDrawerPage.SESSION, 1))
    }

    @Test fun connectedSecondaryDisplayAddsItsPageAfterControllerAndPreservesOtherPages() {
        val connected = sessionDrawerPages(true)
        assertEquals(SessionDrawerPage.SECOND_SCREEN, connected.step(SessionDrawerPage.CONTROLLER, 1))
        assertEquals(SessionDrawerPage.DISPLAY, connected.step(SessionDrawerPage.SECOND_SCREEN, 1))
        assertEquals(sessionDrawerPages(false), connected.filter { it != SessionDrawerPage.SECOND_SCREEN })
        for (pages in listOf(connected, sessionDrawerPages(false))) {
            assertEquals(SessionDrawerPage.EFFECTS, pages.step(SessionDrawerPage.DISPLAY, 1))
            assertEquals(SessionDrawerPage.GAMES, pages.step(SessionDrawerPage.EFFECTS, 1))
            assertEquals(SessionDrawerPage.SESSION, pages.step(SessionDrawerPage.GAMES, 1))
        }
    }
}
