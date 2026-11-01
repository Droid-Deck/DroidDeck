package com.droiddeck.launcher.input

import android.os.Looper
import android.view.InputDevice
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
    private val touches = mutableListOf<String>()
    private val buttons = mutableListOf<String>()
    private val hold = DirectTouchHold(8f, object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) {}
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            assertEquals(PointerGestures.BTN_RIGHT, button)
            buttons += "$pressed at $x,$y"
        }
        override fun onWheel(steps: Int) {}
        override fun onLongPress() {}
    }, object : DirectTouchHold.Touch {
        override fun down(pointerId: Int, x: Float, y: Float) { touches += "down $pointerId at $x,$y" }
        override fun move(pointerId: Int, x: Float, y: Float) { touches += "move $pointerId at $x,$y" }
        override fun up(pointerId: Int, x: Float, y: Float) { touches += "up $pointerId at $x,$y" }
        override fun cancel() { touches += "cancel" }
    }).also { it.configure(TouchGestureConfig(longPressEnabled = true, longPressDelay = 500)) }

    @Test fun holdClicksRightWithoutTouchingTheGame() {
        touch(MotionEvent.ACTION_DOWN)
        advance(400)
        assertTrue(buttons.isEmpty())
        advance(100)
        assertEquals(listOf("true at 40.0,30.0"), buttons)
        advance(50)
        assertEquals(listOf("true at 40.0,30.0", "false at 40.0,30.0"), buttons)
        touch(MotionEvent.ACTION_MOVE, 44f)
        touch(MotionEvent.ACTION_UP)
        assertTrue(touches.isEmpty())
    }

    @Test fun tapForwardsTheTouchWhenItLifts() {
        touch(MotionEvent.ACTION_DOWN)
        advance(100)
        touch(MotionEvent.ACTION_UP, 50f)
        assertEquals(listOf("down 0 at 40.0,30.0"), touches)
        advance(50)
        assertEquals(listOf("down 0 at 40.0,30.0", "up 0 at 50.0,30.0"), touches)
        assertTrue(buttons.isEmpty())
    }

    @Test fun noneBehaviorForwardsTheTouchAtOnce() {
        hold.configure(TouchGestureConfig(directHoldBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_NONE))
        touch(MotionEvent.ACTION_DOWN)
        assertEquals(listOf("down 0 at 40.0,30.0"), touches)
        advance(600)
        assertTrue(buttons.isEmpty())
        touch(MotionEvent.ACTION_MOVE, 60f)
        assertEquals("move 0 at 60.0,30.0", touches.last())
        touch(MotionEvent.ACTION_UP, 60f)
        advance(50)
        assertEquals("up 0 at 60.0,30.0", touches.last())
    }

    @Test fun dragForwardsTheTouchOnceItMoves() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, 60f)
        assertEquals(listOf("down 0 at 40.0,30.0", "move 0 at 60.0,30.0"), touches)
        advance(600)
        assertTrue(buttons.isEmpty())
        touch(MotionEvent.ACTION_UP, 60f)
        advance(50)
        assertEquals("up 0 at 60.0,30.0", touches.last())
    }

    @Test fun dragKeepsForwardingWithinTheSlop() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, 44f)
        assertTrue(touches.isEmpty())
        touch(MotionEvent.ACTION_UP, 44f)
        advance(50)
        assertEquals(listOf("down 0 at 40.0,30.0", "up 0 at 44.0,30.0"), touches)
    }

    @Test fun stillHoldClicksRightWithTheDefaultConfig() {
        hold.configure(TouchGestureConfig())
        touch(MotionEvent.ACTION_DOWN)
        advance(2000)
        touch(MotionEvent.ACTION_MOVE)
        assertEquals(listOf("true at 40.0,30.0", "false at 40.0,30.0"), buttons)
        touch(MotionEvent.ACTION_UP)
        assertTrue(touches.isEmpty())
    }

    @Test fun longPressFollowsTheConfiguredDelay() {
        hold.configure(TouchGestureConfig(longPressEnabled = true, longPressDelay = 800))
        touch(MotionEvent.ACTION_DOWN)
        advance(700)
        assertTrue(buttons.isEmpty())
        advance(100)
        assertEquals(listOf("true at 40.0,30.0"), buttons)
        hold.cancel()
    }

    @Test fun holdBehaviorKeepsTheButtonUntilTheLift() {
        hold.configure(TouchGestureConfig(longPressDelay = 500,
            directHoldBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_HOLD))
        touch(MotionEvent.ACTION_DOWN)
        advance(500)
        assertEquals(listOf("true at 40.0,30.0"), buttons)
        advance(1000)
        assertEquals(listOf("true at 40.0,30.0"), buttons)
        touch(MotionEvent.ACTION_UP)
        assertEquals(listOf("true at 40.0,30.0", "false at 40.0,30.0"), buttons)
        assertTrue(touches.isEmpty())
    }

    @Test fun secondFingerForwardsTheTouchAndDropsTheHold() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_POINTER_DOWN, 60f)
        assertEquals(listOf("down 0 at 40.0,30.0", "down 0 at 60.0,30.0"), touches)
        advance(600)
        assertTrue(buttons.isEmpty())
        touch(MotionEvent.ACTION_UP, 60f)
    }

    @Test fun modeChangeCancelsPendingHoldAndReleasesAnActiveClick() {
        touch(MotionEvent.ACTION_DOWN)
        hold.cancel()
        advance(600)
        assertTrue(buttons.isEmpty())
        assertTrue(touches.isEmpty())
        touch(MotionEvent.ACTION_DOWN)
        advance(500)
        hold.cancel()
        assertEquals(listOf("true at 40.0,30.0", "false at 40.0,30.0"), buttons)
    }

    @Test fun cancelForwardsACancelWhenTheTouchIsLive() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_MOVE, 60f)
        hold.cancel()
        assertEquals(listOf("down 0 at 40.0,30.0", "move 0 at 60.0,30.0", "cancel"), touches)
    }

    @Test fun cancelDropsAPendingLift() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP, 60f)
        hold.cancel()
        advance(100)
        assertEquals(listOf("down 0 at 40.0,30.0", "cancel"), touches)
    }

    @Test fun aQuickSecondTapLiftsTheFirstInsteadOfCancellingIt() {
        touch(MotionEvent.ACTION_DOWN)
        touch(MotionEvent.ACTION_UP, 50f)
        advance(20)
        touch(MotionEvent.ACTION_DOWN, 60f)
        touch(MotionEvent.ACTION_UP, 60f)
        advance(50)
        assertEquals(
            listOf("down 0 at 40.0,30.0", "up 0 at 50.0,30.0", "down 0 at 60.0,30.0", "up 0 at 60.0,30.0"),
            touches,
        )
    }

    @Test fun theSecondFingerLiftsWithItsOwnId() {
        multi(MotionEvent.ACTION_DOWN, 0, 0 to 40f)
        multi(MotionEvent.ACTION_POINTER_DOWN, 1, 0 to 40f, 1 to 60f)
        multi(MotionEvent.ACTION_POINTER_UP, 0, 0 to 40f, 1 to 60f)
        multi(MotionEvent.ACTION_UP, 0, 1 to 60f)
        advance(100)
        assertEquals(
            listOf("down 0 at 40.0,30.0", "down 1 at 60.0,30.0", "up 0 at 40.0,30.0", "up 1 at 60.0,30.0"),
            touches,
        )
    }

    private fun advance(ms: Long) = shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    private fun touch(action: Int, x: Float = 40f): Boolean {
        val event = MotionEvent.obtain(0, 0, action, x, 30f, 0)
        return hold.onTouch(event).also { event.recycle() }
    }
    private fun multi(action: Int, index: Int, vararg points: Pair<Int, Float>): Boolean {
        val properties = Array(points.size) {
            MotionEvent.PointerProperties().apply {
                id = points[it].first
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coords = Array(points.size) {
            MotionEvent.PointerCoords().apply {
                x = points[it].second
                y = 30f
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(0, 0, action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT),
            points.size, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        return hold.onTouch(event).also { event.recycle() }
    }
}
