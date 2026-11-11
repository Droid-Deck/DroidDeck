package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SecondScreenModeTest {
    @Test fun fallbackSelectionSurvivesRecreationAndRequestedDisplaySurvivesDisconnect() {
        var saved = 2
        SecondScreenDisplays.selection(saved, listOf(3), false) { saved = it }
        assertEquals(3, saved)
        assertEquals(3, SecondScreenDisplays.selection(saved, listOf(3), true))
        SecondScreenDisplays.selection(saved, listOf(2), true) { saved = it }
        assertEquals(3, saved)
    }

    @Test fun keyboardFallsBackOnlyForADisconnectedRequestedDisplay() {
        val taken: (Int) -> Boolean = { it == 2 }
        val free: (Int) -> Boolean = { false }
        assertTrue(SecondScreenDisplays.keyboardOnPrimary(true, 2, listOf(3), free))
        assertTrue(SecondScreenDisplays.keyboardOnPrimary(true, 2, listOf(2, 3), taken))
        assertFalse(SecondScreenDisplays.keyboardOnPrimary(true, 2, listOf(2, 3), free))
        assertFalse(SecondScreenDisplays.keyboardOnPrimary(false, 2, listOf(3), taken))
        assertFalse(SecondScreenDisplays.keyboardOnPrimary(true, -1, listOf(3), taken))
    }

    @Test fun keyboardRouteIncludesOccupiedDisplayFallbackOnlyWhenEnabled() {
        val fallback = SecondScreenDisplays.keyboardOnPrimary(true, 2, listOf(2)) { it == 2 }
        assertTrue(fallback)
        assertTrue(SecondScreenDisplays.routeKeyboard(true, true, false, false, fallback))
        assertFalse(SecondScreenDisplays.routeKeyboard(true, false, false, false, fallback))
        assertFalse(SecondScreenDisplays.routeKeyboard(false, true, false, false, fallback))
        assertFalse(SecondScreenDisplays.routeKeyboard(true, true, false, false, false))
        assertTrue(SecondScreenDisplays.routeKeyboard(true, true, true, false, false))
        assertTrue(SecondScreenDisplays.routeKeyboard(true, true, false, true, false))
    }

    @Test fun selectedGameDisplayIsNotReplacedWhenItDisconnects() {
        assertEquals(2, SecondScreenDisplays.selection(2, listOf(3), true))
        assertEquals(2, SecondScreenDisplays.selection(2, emptyList(), true))
        assertEquals(2, SecondScreenDisplays.selection(2, listOf(3, 2), true))
        assertEquals(3, SecondScreenDisplays.selection(3, listOf(3), true))
        assertEquals(3, SecondScreenDisplays.selection(2, listOf(3), false))
        assertEquals(3, SecondScreenDisplays.selection(-1, listOf(3), true))
        assertEquals(-1, SecondScreenDisplays.selection(-1, emptyList(), false))
    }

    @Test fun withNothingRememberedDeckPadsGetControlsAndOthersTheDashboard() {
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening(null, deckPad = true, dashboard = true))
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

    @Test fun customControlsReopenWithEitherControllerType() {
        for (deckPad in listOf(false, true)) {
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("custom-controls", deckPad))
        }
    }

    @Test fun anUnknownOrClosedChoiceFallsBackToTheDefault() {
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("none", deckPad = true, dashboard = true))
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
        assertEquals(SecondScreenMode.DESKTOP, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true, bottomDesktop = true))
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("desktop", deckPad = false))
    }

    @Test fun withNothingRememberedTheDesktopIsNeverPicked() {
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening(null, deckPad = false, dashboard = true, bottomDesktop = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening(null, deckPad = false, bottomDesktop = true))
    }

    @Test fun desktopSessionsOpenTheTrackpadUntilTheUserPicksAnotherPanel() {
        for (deckPad in listOf(false, true)) for (dashboard in listOf(false, true)) {
            assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening(null, deckPad, dashboard, desktopSession = true))
            assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("gone", deckPad, dashboard, desktopSession = true))
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("custom-controls", deckPad, dashboard, desktopSession = true))
            assertEquals(SecondScreenMode.TERMINAL, SecondScreenMode.opening("terminal", deckPad, dashboard, desktopSession = true))
        }
    }

    @Test fun theEditorOpensOnTheChosenBottomScreenUnlessAnotherAppHoldsIt() {
        val free = { _: Int -> false }
        assertEquals(listOf(2, 3), SecondScreenDisplays.editorCandidates(2, listOf(2, 3), free))
        assertEquals(listOf(3, 2), SecondScreenDisplays.editorCandidates(3, listOf(2, 3), free))
        assertEquals(listOf(-1, 2), SecondScreenDisplays.editorCandidates(-1, listOf(2), free))
        assertEquals(listOf(3), SecondScreenDisplays.editorCandidates(2, listOf(2, 3)) { it == 2 })
        assertEquals(emptyList<Int>(), SecondScreenDisplays.editorCandidates(2, listOf(2)) { it == 2 })
    }
}
