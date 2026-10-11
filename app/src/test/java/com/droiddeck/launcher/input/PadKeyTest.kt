package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PadKeyTest {
    @Test fun keyboardLettersAndModifiersAreNotPadEvents() {
        for (type in listOf(InputDevice.KEYBOARD_TYPE_ALPHABETIC, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC)) {
            for (key in listOf(KeyEvent.KEYCODE_A, KeyEvent.KEYCODE_K, KeyEvent.KEYCODE_SHIFT_LEFT,
                KeyEvent.KEYCODE_CTRL_LEFT, KeyEvent.KEYCODE_ESCAPE, KeyEvent.KEYCODE_ENTER)) {
                assertFalse(PadBridge.isPadKey(key, type))
            }
        }
    }

    @Test fun keyboardArrowsStayWithKeyboardAndPadArrowsStayWithPad() {
        assertFalse(PadBridge.isPadKey(KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.KEYBOARD_TYPE_ALPHABETIC))
        assertTrue(PadBridge.isPadKey(KeyEvent.KEYCODE_DPAD_LEFT, InputDevice.KEYBOARD_TYPE_NON_ALPHABETIC))
    }

    @Test fun gamepadButtonsRemainPadEventsOnHybridDevices() {
        assertTrue(PadBridge.isPadKey(KeyEvent.KEYCODE_BUTTON_A, InputDevice.KEYBOARD_TYPE_ALPHABETIC))
        assertTrue(PadBridge.isPadKey(KeyEvent.KEYCODE_BUTTON_START, InputDevice.KEYBOARD_TYPE_ALPHABETIC))
    }
}
