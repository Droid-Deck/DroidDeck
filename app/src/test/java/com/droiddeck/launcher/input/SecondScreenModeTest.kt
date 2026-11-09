package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class SecondScreenModeTest {
    @Test fun withNothingRememberedBothControllerTypesUseTheProfile() {
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening(null, deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening(null, deckPad = false, dashboard = true))
    }

    @Test fun profileControlsDoNotRequireDashboardOrDeckController() {
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening(null, deckPad = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening(null, deckPad = false))
    }

    @Test fun thePanelTheUserPickedLastOpensAgain() {
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("keyboard-trackpad", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("deck-controls", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.TERMINAL, SecondScreenMode.opening("terminal", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("keyboard-trackpad", deckPad = true))
    }

    @Test fun legacyDeckModeResolvesToProfileControlsForEitherController() {
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("deck-controls", deckPad = false))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("deck-controls", deckPad = false, dashboard = true))
    }

    @Test fun customControlsReopenWithEitherControllerType() {
        for (deckPad in listOf(false, true)) {
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("custom-controls", deckPad))
        }
    }

    @Test fun anUnknownOrClosedChoiceFallsBackToTheDefault() {
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("none", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("gone", deckPad = false, dashboard = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("none", deckPad = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("gone", deckPad = false))
    }

    @Test fun theDashboardOpensAgainOnlyWhileItHasPages() {
        assertEquals(SecondScreenMode.DASHBOARD, SecondScreenMode.opening("dashboard", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("dashboard", deckPad = true, dashboard = false))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("dashboard", deckPad = false))
    }

    @Test fun theDesktopOpensAgainOnlyWhileItCanRun() {
        assertEquals(SecondScreenMode.DESKTOP, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true, bottomDesktop = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("desktop", deckPad = true, dashboard = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("desktop", deckPad = false))
    }

    @Test fun withNothingRememberedTheDesktopIsNeverPicked() {
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening(null, deckPad = false, dashboard = true, bottomDesktop = true))
        assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening(null, deckPad = false, bottomDesktop = true))
    }

    @Test fun desktopSessionsOpenTheTrackpadUntilTheUserPicksAnotherPanel() {
        for (deckPad in listOf(false, true)) for (dashboard in listOf(false, true)) {
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening(null, deckPad, dashboard, desktopSession = true))
            assertEquals(SecondScreenMode.CUSTOM_CONTROLS, SecondScreenMode.opening("gone", deckPad, dashboard, desktopSession = true))
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
