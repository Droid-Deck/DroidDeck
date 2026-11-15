package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Test

class SecondScreenModeTest {
    @Test fun withNothingRememberedTheControllerPicksThePanel() {
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening(null, deckPad = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening(null, deckPad = false))
    }

    @Test fun thePanelTheUserPickedLastOpensAgain() {
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("keyboard-trackpad", deckPad = true))
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("deck-controls", deckPad = true))
        assertEquals(SecondScreenMode.TERMINAL, SecondScreenMode.opening("terminal", deckPad = true))
    }

    @Test fun deckControlsNeedTheDeckPad() {
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("deck-controls", deckPad = false))
    }

    @Test fun anUnknownOrClosedChoiceFallsBackToTheDefault() {
        assertEquals(SecondScreenMode.DECK_CONTROLS, SecondScreenMode.opening("none", deckPad = true))
        assertEquals(SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.opening("gone", deckPad = false))
    }
}
