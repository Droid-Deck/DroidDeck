package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], manifest = Config.NONE)
class MouseBackInputTest {
    private val presses = mutableListOf<Boolean>()
    private var mouse = true
    private val input = MouseBackInput(presses::add) { mouse }

    private fun key(action: Int, source: Int = InputDevice.SOURCE_MOUSE, device: Int = 7, repeat: Int = 0,
                    code: Int = KeyEvent.KEYCODE_BACK, flags: Int = 0) =
        KeyEvent(0L, 0L, action, code, repeat, 0, device, 0, flags, source)

    /** What REDMAGIC's cast mode sends for a right click, as logged on an NX789J. */
    private fun injected(action: Int, repeat: Int = 0) = key(action, source = InputDevice.SOURCE_KEYBOARD,
        device = KeyCharacterMap.VIRTUAL_KEYBOARD, repeat = repeat, flags = KeyEvent.FLAG_FROM_SYSTEM)

    /** What the navigation bar and back gesture send, as logged on the same phone. */
    private fun navigationBar(action: Int) = key(action, source = InputDevice.SOURCE_KEYBOARD,
        device = KeyCharacterMap.VIRTUAL_KEYBOARD, flags = KeyEvent.FLAG_FROM_SYSTEM or KeyEvent.FLAG_VIRTUAL_HARD_KEY)

    @Test fun mouseBackBecomesTheRightButton() {
        assertTrue(input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = true))
        assertTrue(input.onKeyEvent(key(KeyEvent.ACTION_UP), toGuest = true))
        assertEquals(listOf(true, false), presses)
    }

    @Test fun holdingTheButtonSwallowsRepeatsWithoutPressingAgain() {
        input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = true)
        repeat(3) { assertTrue(input.onKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = it + 1), toGuest = true)) }
        input.onKeyEvent(key(KeyEvent.ACTION_UP), toGuest = true)
        assertEquals(listOf(true, false), presses)
    }

    @Test fun relativeMouseBackAlsoBecomesTheRightButton() {
        assertTrue(input.onKeyEvent(key(KeyEvent.ACTION_DOWN, source = InputDevice.SOURCE_MOUSE_RELATIVE), toGuest = true))
        assertEquals(listOf(true), presses)
    }

    @Test fun injectedBackBecomesTheRightButtonWhileAMouseIsConnected() {
        assertTrue(input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true))
        assertTrue(input.onKeyEvent(injected(KeyEvent.ACTION_DOWN, repeat = 1), toGuest = true))
        assertTrue(input.onKeyEvent(injected(KeyEvent.ACTION_UP), toGuest = true))
        assertEquals(listOf(true, false), presses)
    }

    @Test fun injectedBackStaysBackWithoutAMouse() {
        mouse = false
        assertFalse(input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true))
        assertFalse(input.onKeyEvent(injected(KeyEvent.ACTION_UP), toGuest = true))
        assertTrue(presses.isEmpty())
    }

    @Test fun navigationBarBackStaysBackEvenWithAMouse() {
        for (action in listOf(KeyEvent.ACTION_DOWN, KeyEvent.ACTION_UP)) {
            assertFalse(input.onKeyEvent(navigationBar(action), toGuest = true))
        }
        assertTrue(presses.isEmpty())
    }

    @Test fun navigationBarBackWhileAnInjectedBackIsHeldStaysBack() {
        input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true)
        assertFalse(input.onKeyEvent(navigationBar(KeyEvent.ACTION_DOWN), toGuest = true))
        assertFalse(input.onKeyEvent(navigationBar(KeyEvent.ACTION_UP), toGuest = true))
        assertEquals(listOf(true), presses)
        assertTrue(input.onKeyEvent(injected(KeyEvent.ACTION_UP), toGuest = true))
        assertEquals(listOf(true, false), presses)
    }

    @Test fun overlappingPressesHoldTheButtonUntilTheLastRelease() {
        input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = true)
        input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true)
        input.onKeyEvent(key(KeyEvent.ACTION_UP), toGuest = true)
        assertEquals(listOf(true), presses)
        input.onKeyEvent(injected(KeyEvent.ACTION_UP), toGuest = true)
        assertEquals(listOf(true, false), presses)
    }

    @Test fun anUnrelatedDeviceDisconnectingKeepsTheButtonHeld() {
        input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = true)
        input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true)
        input.onDeviceRemoved(9)
        assertEquals(listOf(true), presses)
    }

    @Test fun theMouseDisconnectingReleasesItsPress() {
        input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = true)
        input.onDeviceRemoved(7)
        assertEquals(listOf(true, false), presses)
    }

    @Test fun theLastMouseDisconnectingReleasesAnInjectedPress() {
        input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true)
        mouse = false
        input.onDeviceRemoved(7)
        assertEquals(listOf(true, false), presses)
    }

    @Test fun cancelReleasesAHeldPressOnce() {
        input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true)
        input.cancel()
        assertEquals(listOf(true, false), presses)
        input.onKeyEvent(injected(KeyEvent.ACTION_UP), toGuest = true)
        input.cancel()
        assertEquals(listOf(true, false), presses)
    }

    @Test fun controllerBackStaysBack() {
        val source = InputDevice.SOURCE_GAMEPAD or InputDevice.SOURCE_KEYBOARD
        assertFalse(input.onKeyEvent(key(KeyEvent.ACTION_DOWN, source = source), toGuest = true))
        assertFalse(input.onKeyEvent(key(KeyEvent.ACTION_UP, source = source), toGuest = true))
        assertTrue(presses.isEmpty())
    }

    @Test fun otherMouseKeysAreLeftAlone() {
        assertFalse(input.onKeyEvent(key(KeyEvent.ACTION_DOWN, code = KeyEvent.KEYCODE_FORWARD), toGuest = true))
        assertTrue(presses.isEmpty())
    }

    @Test fun mouseBackStaysBackWhileAMenuIsOpen() {
        assertFalse(input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = false))
        assertFalse(input.onKeyEvent(key(KeyEvent.ACTION_DOWN, repeat = 1), toGuest = false))
        assertFalse(input.onKeyEvent(key(KeyEvent.ACTION_UP), toGuest = false))
        assertTrue(presses.isEmpty())
    }

    @Test fun aMenuOpeningMidPressStillReleasesTheButton() {
        input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = true)
        assertTrue(input.onKeyEvent(key(KeyEvent.ACTION_UP), toGuest = false))
        assertEquals(listOf(true, false), presses)
    }

    @Test fun theMouseDisconnectingMidPressStillReleasesTheButton() {
        input.onKeyEvent(injected(KeyEvent.ACTION_DOWN), toGuest = true)
        mouse = false
        assertTrue(input.onKeyEvent(injected(KeyEvent.ACTION_UP), toGuest = true))
        assertEquals(listOf(true, false), presses)
    }

    @Test fun aMenuClosingMidPressLeavesTheReleaseToTheMenu() {
        input.onKeyEvent(key(KeyEvent.ACTION_DOWN), toGuest = false)
        assertFalse(input.onKeyEvent(key(KeyEvent.ACTION_UP), toGuest = true))
        assertTrue(presses.isEmpty())
    }
}
