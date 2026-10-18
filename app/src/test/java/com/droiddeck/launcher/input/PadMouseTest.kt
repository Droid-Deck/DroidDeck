package com.droiddeck.launcher.input

import android.view.KeyEvent
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PadMouseTest {
    private val events = mutableListOf<String>()
    private var scrolledX = 0f
    private var scrolledY = 0f
    private var now = 1000L
    private lateinit var pad: PadMouse

    private val listener = object : PadMouse.Listener {
        override fun active() = true
        override fun onScrollStart() { events += "scroll start" }
        override fun onScroll(dx: Float, dy: Float) {
            scrolledX += dx
            scrolledY += dy
        }
        override fun onKey(evdev: Int, pressed: Boolean) { events += "key $evdev ${if (pressed) "down" else "up"}" }
        override fun onKeyboard() { events += "keyboard" }
        override fun onNavigate() {}
        override fun scrollSpeed() = 2000f
    }

    @Before fun setUp() {
        pad = PadMouse(listener) { now }
    }

    @Test fun aOpensTheSelection() {
        key(KeyEvent.KEYCODE_BUTTON_A, true)
        key(KeyEvent.KEYCODE_BUTTON_A, false)
        key(KeyEvent.KEYCODE_DPAD_DOWN, true)
        key(KeyEvent.KEYCODE_DPAD_DOWN, false)
        key(KeyEvent.KEYCODE_BUTTON_A, true)
        key(KeyEvent.KEYCODE_BUTTON_A, false)
        assertEquals(listOf("key 28 down", "key 28 up", "key 108 down", "key 108 up", "key 28 down", "key 28 up"), events)
    }

    @Test fun rightStickScrollsByHowFarItIsPushedThenSettlesTheSelection() {
        motion(rightY = 0.5f)
        pad.step(0.1f)
        assertEquals(33.9f, scrolledY, 0.1f)
        motion(rightY = 1f, rightX = -1f)
        pad.step(0.1f)
        assertEquals(175.3f, scrolledY, 0.1f)
        assertEquals(-141.4f, scrolledX, 0.1f)
        motion()
        pad.step(0.1f)
        pad.step(0.1f)
        assertEquals(listOf("scroll start", "key 183 down", "key 183 up"), events)
    }

    @Test fun eachControllerKeepsItsOwnSticksAndButtons() {
        motion(rightY = 1f, device = 1)
        motion(device = 2)
        pad.step(0.1f)
        assertEquals(200f, scrolledY, 0.01f)
        key(KeyEvent.KEYCODE_BUTTON_A, true, device = 1)
        key(KeyEvent.KEYCODE_BUTTON_A, true, device = 2)
        key(KeyEvent.KEYCODE_BUTTON_A, false, device = 2)
        assertEquals(listOf("scroll start", "key 28 down"), events)
        key(KeyEvent.KEYCODE_BUTTON_A, false, device = 1)
        assertEquals(listOf("scroll start", "key 28 down", "key 28 up"), events)
    }

    @Test fun resetLetsGoOfAHeldModifierAndTheNextPressWorks() {
        key(KeyEvent.KEYCODE_BUTTON_L1, true)
        pad.reset()
        key(KeyEvent.KEYCODE_BUTTON_L1, false)
        key(KeyEvent.KEYCODE_BUTTON_Y, true)
        pad.reset()
        key(KeyEvent.KEYCODE_BUTTON_Y, true)
        assertEquals(listOf("key 29 down", "key 104 down", "key 104 up", "key 29 up", "keyboard", "keyboard"), events)
    }

    @Test fun leftStickMovesFromHalfway() {
        motion(leftX = 0.45f)
        pad.step(0.016f)
        motion(leftX = 0.55f)
        pad.step(0.016f)
        assertEquals(listOf("key 106 down", "key 106 up"), events)
    }

    @Test fun otherButtons() {
        for (code in listOf(KeyEvent.KEYCODE_BUTTON_X, KeyEvent.KEYCODE_BUTTON_B, KeyEvent.KEYCODE_BUTTON_START,
            KeyEvent.KEYCODE_BUTTON_L1, KeyEvent.KEYCODE_BUTTON_R1)) {
            key(code, true)
            key(code, false)
        }
        assertEquals(listOf("key 127 down", "key 127 up", "key 1 down", "key 158 down", "key 158 up", "key 1 up", "key 28 down", "key 28 up",
            "key 29 down", "key 104 down", "key 104 up", "key 29 up", "key 29 down", "key 109 down", "key 109 up", "key 29 up"), events)
    }

    @Test fun yOpensTheKeyboardOnce() {
        key(KeyEvent.KEYCODE_BUTTON_Y, true)
        pad.onKey(KeyEvent(0, 0, KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_Y, 1))
        key(KeyEvent.KEYCODE_BUTTON_Y, false)
        assertEquals(listOf("keyboard"), events)
    }

    @Test fun guideAndSelectStayWithTheGamepad() {
        assertFalse(pad.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_MODE)))
        assertFalse(pad.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_SELECT)))
        assertTrue(pad.onKey(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BUTTON_R2)))
        assertEquals(emptyList<String>(), events)
    }

    @Test fun dpadKeysAndHatShareOnePress() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT, true)
        motion(hatX = 1f)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, false)
        motion()
        motion(hatY = -1f)
        motion()
        assertEquals(listOf("key 106 down", "key 106 up", "key 103 down", "key 103 up"), events)
    }

    @Test fun heldDpadRepeatsAtTheMenuPace() {
        key(KeyEvent.KEYCODE_DPAD_RIGHT, true)
        pad.step(0.016f)
        now += 299
        pad.step(0.016f)
        now += 1
        pad.step(0.016f)
        now += 120
        pad.step(0.016f)
        key(KeyEvent.KEYCODE_DPAD_RIGHT, false)
        pad.step(0.016f)
        now += 1000
        pad.step(0.016f)
        assertEquals(List(3) { listOf("key 106 down", "key 106 up") }.flatten(), events)
    }

    @Test fun leftStickStepsThroughItemsAndRepeats() {
        motion(leftY = 1f)
        pad.step(0.016f)
        pad.step(0.016f)
        now += 400
        pad.step(0.016f)
        now += 100
        pad.step(0.016f)
        now += 50
        pad.step(0.016f)
        motion()
        pad.step(0.016f)
        motion(leftX = -0.3f)
        pad.step(0.016f)
        assertEquals(listOf("key 108 down", "key 108 up", "key 108 down", "key 108 up", "key 108 down", "key 108 up"), events)
    }

    @Test fun touchPadDrivesTheSameMapping() {
        val state = PadState()
        state.press(PadState.A, true)
        pad.onTouchPad(state)
        state.press(PadState.A, false)
        state.down = true
        pad.onTouchPad(state)
        state.down = false
        state.rightTrigger = 1f
        pad.onTouchPad(state)
        pad.step(0.1f)
        assertEquals(listOf("key 28 down", "key 28 up", "key 108 down", "key 108 up", "scroll start"), events)
        assertEquals(200f, scrolledY, 0.01f)
    }

    @Test fun resetReleasesEverythingHeld() {
        key(KeyEvent.KEYCODE_BUTTON_X, true)
        key(KeyEvent.KEYCODE_DPAD_DOWN, true)
        pad.reset()
        key(KeyEvent.KEYCODE_DPAD_DOWN, false)
        assertEquals(listOf("key 127 down", "key 108 down", "key 108 up", "key 127 up"), events)
    }

    @Test fun stickCurveIsGentleNearTheMiddle() {
        val (half, _) = PadMouse.curve(0.575f, 0f)
        assertEquals(0.25f, half, 0.01f)
        assertEquals(0f to 0f, PadMouse.curve(0.1f, 0.1f))
    }

    @Test fun triggersScroll() {
        motion(rightTrigger = 1f)
        pad.step(0.1f)
        assertEquals(200f, scrolledY, 0.01f)
        motion(leftTrigger = 1f)
        pad.step(0.1f)
        assertEquals(0f, scrolledY, 0.01f)
        motion()
        pad.step(0.1f)
        assertEquals(listOf("scroll start", "key 183 down", "key 183 up"), events)
    }

    private fun key(code: Int, down: Boolean, device: Int = 0) {
        val action = if (down) KeyEvent.ACTION_DOWN else KeyEvent.ACTION_UP
        assertTrue(pad.onKey(KeyEvent(0, 0, action, code, 0, 0, device, 0)))
    }

    private fun motion(leftX: Float = 0f, leftY: Float = 0f, rightX: Float = 0f, rightY: Float = 0f, hatX: Float = 0f, hatY: Float = 0f,
                       leftTrigger: Float = 0f, rightTrigger: Float = 0f, device: Int = 0) {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 0; toolType = MotionEvent.TOOL_TYPE_UNKNOWN })
        val coords = arrayOf(MotionEvent.PointerCoords().apply {
            setAxisValue(MotionEvent.AXIS_X, leftX)
            setAxisValue(MotionEvent.AXIS_Y, leftY)
            setAxisValue(MotionEvent.AXIS_Z, rightX)
            setAxisValue(MotionEvent.AXIS_RZ, rightY)
            setAxisValue(MotionEvent.AXIS_HAT_X, hatX)
            setAxisValue(MotionEvent.AXIS_HAT_Y, hatY)
            setAxisValue(MotionEvent.AXIS_LTRIGGER, leftTrigger)
            setAxisValue(MotionEvent.AXIS_RTRIGGER, rightTrigger)
        })
        val event = MotionEvent.obtain(0, 0, MotionEvent.ACTION_MOVE, 1, properties, coords, 0, 0, 1f, 1f, device, 0,
            android.view.InputDevice.SOURCE_JOYSTICK, 0)
        pad.onMotion(event)
        event.recycle()
    }
}
