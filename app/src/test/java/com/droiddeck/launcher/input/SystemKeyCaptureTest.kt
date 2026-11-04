package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SystemKeyCaptureTest {
    @Test fun systemCombinationsAreCaptured() {
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_META_LEFT, 0))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_META_RIGHT, 0))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_ALT_ON))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_D, KeyEvent.META_META_ON))
    }

    @Test fun ordinaryKeysStayWithTheNormalPath() {
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_TAB, 0))
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_A, 0))
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_F, KeyEvent.META_ALT_ON))
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
    }
}
