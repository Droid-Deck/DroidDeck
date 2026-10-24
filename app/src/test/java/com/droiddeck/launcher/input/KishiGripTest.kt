package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class KishiGripTest {
    @Test fun kishiL4AndR4AsTheGamepadSendsThemHoldTheDecksL4AndR4() {
        assertEquals(DeckControls.L4, PadBridge.kishiGrip(0x1532, KeyEvent.KEYCODE_BUTTON_C))  // L4 = BTN_C
        assertEquals(DeckControls.R4, PadBridge.kishiGrip(0x1532, KeyEvent.KEYCODE_BUTTON_Z))  // R4 = BTN_Z
    }

    @Test fun kishiL4AndR4ArePadKeysSoTheyReachTheGripPath() {
        assertTrue(PadBridge.isPadKey(KeyEvent.KEYCODE_BUTTON_C, android.view.InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC))
        assertTrue(PadBridge.isPadKey(KeyEvent.KEYCODE_BUTTON_Z, android.view.InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC))
        assertFalse(PadBridge.isPadKey(KeyEvent.KEYCODE_DPAD_CENTER, android.view.InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC))
    }

    @Test fun otherDevicesAndKeysAreLeftAlone() {
        assertEquals(0, PadBridge.kishiGrip(0x045E, KeyEvent.KEYCODE_F1))  // a keyboard's F1 stays F1
        assertEquals(0, PadBridge.kishiGrip(0x045E, KeyEvent.KEYCODE_BUTTON_C))
        assertEquals(0, PadBridge.kishiGrip(0x1532, KeyEvent.KEYCODE_F1))  // no guessed key codes
        assertEquals(0, PadBridge.kishiGrip(0x1532, KeyEvent.KEYCODE_BUTTON_A))
    }

    @Test fun theDpadCenterFallbackOfAKishiGripIsSwallowedButNotARealSelect() {
        assertTrue(PadBridge.isKishiGripFallback(true, 0x1532, KeyEvent.KEYCODE_DPAD_CENTER, 306))
        assertTrue(PadBridge.isKishiGripFallback(true, 0x1532, KeyEvent.KEYCODE_DPAD_CENTER, 309))
        assertFalse(PadBridge.isKishiGripFallback(true, 0x1532, KeyEvent.KEYCODE_DPAD_CENTER, 304))  // BTN_A
        assertFalse(PadBridge.isKishiGripFallback(true, 0x045E, KeyEvent.KEYCODE_DPAD_CENTER, 306))
        assertFalse(PadBridge.isKishiGripFallback(true, 0x1532, KeyEvent.KEYCODE_BUTTON_C, 306))
        assertFalse(PadBridge.isKishiGripFallback(false, 0x1532, KeyEvent.KEYCODE_DPAD_CENTER, 306))  // not a controller
    }
}
