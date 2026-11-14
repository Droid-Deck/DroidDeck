package com.droiddeck.launcher.input

import android.view.KeyEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

class SystemKeyCaptureTest {
    @Test fun systemCombinationsAreCaptured() {
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_META_LEFT, 0))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_META_RIGHT, 0))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_TAB, KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_ESCAPE, KeyEvent.META_ALT_ON))
        assertTrue(SystemKeyCapture.captures(KeyEvent.KEYCODE_D, KeyEvent.META_META_ON))
    }

    @Test fun onlyTheEscapeScanCodeTurnsBackIntoEscape() {
        assertTrue(GuestKeys.isKeyboardEscape(KeyEvent.KEYCODE_BACK, 1))
        assertFalse(GuestKeys.isKeyboardEscape(KeyEvent.KEYCODE_BACK, 158))
        assertFalse(GuestKeys.isKeyboardEscape(KeyEvent.KEYCODE_ESCAPE, 1))
    }

    @Test fun ordinaryKeysStayWithTheNormalPath() {
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_TAB, 0))
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_A, 0))
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_F, KeyEvent.META_ALT_ON))
        assertFalse(SystemKeyCapture.captures(KeyEvent.KEYCODE_C, KeyEvent.META_CTRL_ON))
    }
}

@RunWith(RobolectricTestRunner::class)
class SystemKeyCaptureHeldKeysTest {
    private val sent = mutableListOf<Int>()

    private fun key(action: Int, repeat: Int = 0) =
        KeyEvent(0L, 0L, action, KeyEvent.KEYCODE_META_LEFT, repeat)

    private fun attached(): Any = Any().also {
        SystemKeyCapture.isKeyboard = { true }
        SystemKeyCapture.attach(it, { true }) { event -> sent += event.action }
    }

    @Test fun keyHeldThroughDetachIsReleasedOnceAndItsPhysicalReleaseIsConsumed() {
        val owner = attached()
        assertTrue(SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_DOWN)))
        SystemKeyCapture.detach(owner)
        assertEquals(listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP), sent)
        assertTrue(SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1)))
        assertTrue(SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_UP)))
        assertEquals(2, sent.size)
        assertFalse(SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_UP)))
    }

    @Test fun aKeyAlreadyHeldWhenCaptureStartsStaysWithAndroid() {
        val owner = attached()
        assertFalse(SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1)))
        assertFalse(SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_UP)))
        SystemKeyCapture.detach(owner)
        assertEquals(0, sent.size)
    }

    @Test fun newPressAfterDetachIsNotSwallowed() {
        val owner = attached()
        SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_DOWN))
        SystemKeyCapture.detach(owner)
        assertFalse(SystemKeyCapture.onKeyEvent(key(KeyEvent.ACTION_DOWN)))
    }
}
