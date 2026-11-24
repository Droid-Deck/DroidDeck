package com.droiddeck.launcher.ui

import org.junit.Assert.*
import org.junit.Test

class SessionDrawerPageTest {
    @Test fun navigationStartsWithControllerAndGoesStraightToDisplay() {
        val pages = sessionDrawerPages(hasAgent = false)
        assertEquals(SessionDrawerPage.CONTROLLER, pages.first())
        assertEquals(SessionDrawerPage.DISPLAY, pages.step(SessionDrawerPage.CONTROLLER, 1))
        assertEquals(SessionDrawerPage.VR, pages.step(SessionDrawerPage.CONTROLLER, -1))
        assertEquals(SessionDrawerPage.HUD, pages.step(SessionDrawerPage.DISPLAY, 1))
        assertEquals(SessionDrawerPage.EFFECTS, pages.step(SessionDrawerPage.HUD, 1))
        assertEquals(SessionDrawerPage.GAMES, pages.step(SessionDrawerPage.EFFECTS, 1))
        assertEquals(SessionDrawerPage.SESSION, pages.step(SessionDrawerPage.GAMES, 1))
    }

    @Test fun moreKeepsSteamVrByDefaultAndStandsInForThePagesInsideIt() {
        val pages = sessionDrawerPages(hasAgent = false)
        assertEquals(listOf(SessionDrawerPage.VR), pages.inMore())
        assertEquals(SessionDrawerPage.MORE, pages.strip().last())
        assertFalse(SessionDrawerPage.VR in pages.strip())
        assertEquals(SessionDrawerPage.MORE, pages.tabFor(SessionDrawerPage.VR))
        assertEquals(SessionDrawerPage.GAMES, pages.tabFor(SessionDrawerPage.GAMES))
        val strip = pages.strip()
        assertEquals(SessionDrawerPage.MORE, strip.step(SessionDrawerPage.SESSION, 1))
        assertEquals(SessionDrawerPage.CONTROLLER, strip.step(SessionDrawerPage.MORE, 1))
        assertEquals(SessionDrawerPage.SESSION, strip.step(pages.tabFor(SessionDrawerPage.VR), -1))
    }

    @Test fun anOlderSavedOrderKeepsItsPagesAndGetsAMoreTabAfterSession() {
        val saved = listOf("CONTROLLER", "DISPLAY", "HUD", "EFFECTS", "VR", "GAMES", "AGENT", "SESSION")
        val out = DrawerPages.arrange(saved)
        assertEquals(SessionDrawerPage.MORE, out[out.indexOf(SessionDrawerPage.SESSION) + 1])
        assertTrue(out.indexOf(SessionDrawerPage.VR) < out.indexOf(SessionDrawerPage.EFFECTS) + 2)
        assertTrue(out.inMore().isEmpty())
    }

    @Test fun sessionCanBeKeptInsideMore() {
        val saved = listOf("CONTROLLER", "DISPLAY", "HUD", "EFFECTS", "GAMES", "AGENT", "MORE", "SESSION", "VR")
        val out = DrawerPages.arrange(saved)
        assertEquals(listOf(SessionDrawerPage.SESSION, SessionDrawerPage.VR), out.inMore())
        assertFalse(SessionDrawerPage.SESSION in out.strip())
    }

    @Test fun moreAndSessionCannotBeHidden() {
        assertFalse(DrawerPages.hideable(SessionDrawerPage.MORE))
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

    @Test fun arrangeKeepsSavedOrderAndSlotsNewPagesAfterTheirNeighbour() {
        val saved = listOf("SESSION", "HUD", "CONTROLLER")
        val out = DrawerPages.arrange(saved)
        assertEquals(SessionDrawerPage.entries.toSet(), out.toSet())
        assertEquals(listOf(SessionDrawerPage.SESSION, SessionDrawerPage.HUD), out.filter { it in setOf(SessionDrawerPage.SESSION, SessionDrawerPage.HUD) })
        assertTrue(out.indexOf(SessionDrawerPage.HUD) < out.indexOf(SessionDrawerPage.CONTROLLER))
    }

    @Test fun sessionPageCannotBeHidden() {
        assertFalse(DrawerPages.hideable(SessionDrawerPage.SESSION))
        assertTrue(DrawerPages.hideable(SessionDrawerPage.HUD))
    }
}
