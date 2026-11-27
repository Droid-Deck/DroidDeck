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
        assertTrue(SecondScreenDisplays.keyboardOnPrimary(true, 2, listOf(3), taken = free))
        assertTrue(SecondScreenDisplays.keyboardOnPrimary(true, 2, listOf(2, 3), taken = taken))
        assertFalse(SecondScreenDisplays.keyboardOnPrimary(true, 2, listOf(2, 3), taken = free))
        assertFalse(SecondScreenDisplays.keyboardOnPrimary(false, 2, listOf(3), taken = taken))
        assertFalse(SecondScreenDisplays.keyboardOnPrimary(true, -1, listOf(3), taken = taken))
    }

    @Test fun primaryFallbackRequiresVisibleActivity() {
        for (available in listOf(emptyList(), listOf(2))) {
            assertTrue(SecondScreenDisplays.keyboardOnPrimary(true, 2, available, true) { true })
            assertFalse(SecondScreenDisplays.keyboardOnPrimary(true, 2, available, false) { true })
        }
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

    @Test fun withNothingRememberedTheDroidDeckMenuOpens() {
        for (deckPad in listOf(false, true)) for (dashboard in listOf(false, true)) for (desktop in listOf(false, true)) {
            assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening(null, deckPad, dashboard, desktop))
        }
    }

    @Test fun thePanelTheUserPickedLastOpensAgain() {
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("keyboard-trackpad", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("deck-controls", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.TERMINAL, SecondScreenMode.opening("terminal", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("keyboard-trackpad", deckPad = true))
    }

    @Test fun theMenuIsAModeAndOpensAgainWhenItWasPickedLast() {
        for (deckPad in listOf(false, true)) {
            assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("menu", deckPad, dashboard = true, bottomDesktop = true))
        }
        assertEquals("menu", SecondScreenMode.MENU.id)
        assertEquals(false, SecondScreenMode.MENU.controls)
        assertEquals(null, SecondScreenMode.MENU.bindings)
    }

    @Test fun deckControlsNeedTheDeckPad() {
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("deck-controls", deckPad = false))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("deck-controls", deckPad = false, dashboard = true))
    }

    @Test fun customControlsReopenWithEitherControllerType() {
        for (deckPad in listOf(false, true)) {
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("custom-controls", deckPad))
        }
    }

    @Test fun anUnknownOrClosedChoiceFallsBackToTheMenu() {
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("none", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("gone", deckPad = false, dashboard = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("none", deckPad = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("gone", deckPad = false))
    }

    @Test fun theDashboardOpensAgainOnlyWhileItHasPages() {
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening("dashboard", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("dashboard", deckPad = true, dashboard = false))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("dashboard", deckPad = false))
    }

    @Test fun steamQuickAccessOpensAgainOnlyInASteamSession() {
        assertEquals(SecondScreenMode.STEAM_QAM, SecondScreenMode.opening("steam-qam", deckPad = true, dashboard = true, steam = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("steam-qam", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("steam-qam", deckPad = true, steam = true))
    }

    @Test fun theDesktopOpensAgainOnlyWhileItCanRun() {
        assertEquals(SecondScreenMode.DESKTOP, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true, bottomDesktop = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.MENU, SecondScreenMode.opening("desktop", deckPad = false))
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
