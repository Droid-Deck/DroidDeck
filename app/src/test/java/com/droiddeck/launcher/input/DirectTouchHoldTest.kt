package com.droiddeck.launcher.input

import android.os.Looper
import android.view.MotionEvent
import java.time.Duration
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DirectTouchHoldTest {
    private val events = mutableListOf<String>()
    private val hold = DirectTouchHold(8f, object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) {}
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            assertEquals(PointerGestures.BTN_RIGHT, button)
            events += "$pressed at $x,$y"
        }
        override fun onWheel(steps: Int) {}
        override fun onLongPress() {}
    }) { events += "cancel touch" }.also { it.configure(TouchGestureConfig(longPressEnabled = true, longPressDelay = 500)) }

    @Test fun holdReleasesRawTouchBeforeClickAndConsumesTheLift() {
        assertFalse(touch(MotionEvent.ACTION_DOWN))
        advance(500)
        assertEquals(listOf("cancel touch", "true at 40.0,30.0"), events)
        assertTrue(touch(MotionEvent.ACTION_UP))
        advance(50)
        assertEquals("false at 40.0,30.0", events.last())
        assertFalse(touch(MotionEvent.ACTION_DOWN))
        hold.cancel()
    }

    @Test fun stillHoldKeepsTheTouchWhenLongPressIsOff() {
        hold.configure(TouchGestureConfig())
        assertFalse(touch(MotionEvent.ACTION_DOWN))
        advance(2000)
        assertFalse(touch(MotionEvent.ACTION_MOVE))
        assertFalse(touch(MotionEvent.ACTION_UP))
        assertTrue(events.isEmpty())
    }

    @Test fun longPressFollowsTheConfiguredDelay() {
        hold.configure(TouchGestureConfig(longPressEnabled = true, longPressDelay = 800))
        touch(MotionEvent.ACTION_DOWN)
        advance(700)
        assertTrue(events.isEmpty())
        advance(100)
        assertEquals(listOf("cancel touch", "true at 40.0,30.0"), events)
        hold.cancel()
    }

    @Test fun tapDragSecondFingerAndCancellationDoNotRightClick() {
        for (action in listOf(MotionEvent.ACTION_UP, MotionEvent.ACTION_MOVE,
                MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_CANCEL)) {
            touch(MotionEvent.ACTION_DOWN)
            assertFalse(touch(action, 60f))
            advance(600)
            assertTrue(events.isEmpty())
            hold.cancel()
        }
    }

    @Test fun modeChangeCancelsPendingHoldAndReleasesAnActiveClick() {
        touch(MotionEvent.ACTION_DOWN)
        hold.cancel()
        advance(600)
        assertTrue(events.isEmpty())
        touch(MotionEvent.ACTION_DOWN)
        advance(500)
        hold.cancel()
        advance(100)
        assertEquals(listOf("cancel touch", "true at 40.0,30.0", "false at 40.0,30.0"), events)
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(action: Int, x: Float = 40f): Boolean {
        val event = MotionEvent.obtain(0, 0, action, x, 30f, 0)
        return hold.onTouch(event).also { event.recycle() }
    }
}
