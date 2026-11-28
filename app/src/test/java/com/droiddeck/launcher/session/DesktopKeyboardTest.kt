package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class DesktopKeyboardTest {
    private fun decide(
        pref: String,
        focused: Boolean = true,
        tapped: Boolean = true,
        hardware: Boolean = false,
        pcOpen: Boolean = false,
    ) = DesktopKeyboard.decide(pref, focused, tapped, hardware, pcOpen)

    @Test fun androidOpensTheAndroidKeyboard() {
        assertEquals(DesktopKeyboard.Action.OPEN_ANDROID, decide(DesktopKeyboard.ANDROID))
    }

    @Test fun pcOpensThePcKeyboard() {
        assertEquals(DesktopKeyboard.Action.OPEN_PC, decide(DesktopKeyboard.PC))
    }

    @Test fun noneOpensNothing() {
        assertEquals(DesktopKeyboard.Action.NOTHING, decide(DesktopKeyboard.NONE))
    }

    @Test fun programmaticFocusDoesNotOpenAKeyboard() {
        assertEquals(DesktopKeyboard.Action.NOTHING, decide(DesktopKeyboard.PC, tapped = false))
    }

    @Test fun aReportedHardwareKeyboardSuppressesBothKeyboards() {
        assertEquals(DesktopKeyboard.Action.NOTHING, decide(DesktopKeyboard.PC, hardware = true))
        assertEquals(DesktopKeyboard.Action.NOTHING, decide(DesktopKeyboard.ANDROID, hardware = true))
    }

    @Test fun anOpenPcKeyboardIsNotOpenedAgain() {
        assertEquals(DesktopKeyboard.Action.NOTHING, decide(DesktopKeyboard.PC, pcOpen = true))
    }

    @Test fun unknownValuesFallBackToAndroid() {
        assertEquals(DesktopKeyboard.ANDROID, DesktopKeyboard.normalize("bogus"))
        assertEquals(DesktopKeyboard.Action.OPEN_ANDROID, decide("bogus"))
    }
}
