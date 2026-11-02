package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class SecondScreenModeTest {
    @Test fun withNothingRememberedTheDashboardOpens() {
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening(null, deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening(null, deckPad = false, dashboard = true))
    }

    @Test fun withNothingRememberedAndNoDashboardTheControllerPicksThePanel() {
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening(null, deckPad = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening(null, deckPad = false))
    }

    @Test fun thePanelTheUserPickedLastOpensAgain() {
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("keyboard-trackpad", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("deck-controls", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.TERMINAL, SecondScreenMode.opening("terminal", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("keyboard-trackpad", deckPad = true))
    }

    @Test fun deckControlsNeedTheDeckPad() {
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("deck-controls", deckPad = false))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("deck-controls", deckPad = false, dashboard = true))
    }

    @Test fun anUnknownOrClosedChoiceFallsBackToTheDefault() {
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening("none", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening("gone", deckPad = false, dashboard = true))
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("none", deckPad = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("gone", deckPad = false))
    }

    @Test fun theDashboardOpensAgainOnlyWhileItHasPages() {
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening("dashboard", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("dashboard", deckPad = true, dashboard = false))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("dashboard", deckPad = false))
    }

    @Test fun theDesktopOpensAgainOnlyWhileItCanRun() {
        assertEquals(SecondScreenMode.DESKTOP, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true, desktop = true))
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("desktop", deckPad = false))
    }

    @Test fun withNothingRememberedTheDesktopIsNeverPicked() {
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening(null, deckPad = false, dashboard = true, desktop = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening(null, deckPad = false, desktop = true))
    }
}
