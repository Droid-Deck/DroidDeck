package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class GuestKeysTest {
    @Test fun hardwareEscapeBypassesBackMenuAndReachesGuestWithMatchingRelease() {
        val taps = mutableListOf<Boolean>()
        val menu = BackQamInput({}, taps::add)
        val guest = mutableListOf<Pair<Int, Boolean>>()
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            val event = KeyEvent(10, 20, action, KeyEvent.KEYCODE_BACK, 0,
                KeyEvent.META_SHIFT_ON, 43, 1, 0, InputDevice.SOURCE_KEYBOARD)
            val normalized = GuestKeys.normalizeKeyboardEscape(event)
            assertEquals(KeyEvent.KEYCODE_ESCAPE, normalized.keyCode)
            assertEquals(event.action, normalized.action)
            assertEquals(event.metaState, normalized.metaState)
            assertEquals(event.deviceId, normalized.deviceId)
            assertEquals(event.scanCode, normalized.scanCode)
            assertEquals(event.source, normalized.source)
            assertFalse(menu.onKeyEvent(normalized, true))
            assertTrue(GuestKeys.send(normalized) { code, down -> guest += code to down })
        }
        assertEquals(listOf(1 to true, 1 to false), guest)
        assertTrue(taps.isEmpty())
    }

    @Test fun navigationBackAndOtherPhysicalBackKeysAreUnchanged() {
        for (scan in listOf(0, 158)) {
            val event = KeyEvent(10, 20, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK,
                0, 0, 43, scan, 0, InputDevice.SOURCE_KEYBOARD)
            assertSame(event, GuestKeys.normalizeKeyboardEscape(event))
        }
    }

    @Test fun normalizedEscapePreservesRepeatAndCancellation() {
        val event = KeyEvent(10, 20, KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK,
            3, 0, 43, 1, KeyEvent.FLAG_CANCELED, InputDevice.SOURCE_KEYBOARD)
        val normalized = GuestKeys.normalizeKeyboardEscape(event)
        assertEquals(event.repeatCount, normalized.repeatCount)
        assertEquals(event.flags, normalized.flags)
        assertEquals(event.downTime, normalized.downTime)
        assertEquals(event.eventTime, normalized.eventTime)
    }
}
