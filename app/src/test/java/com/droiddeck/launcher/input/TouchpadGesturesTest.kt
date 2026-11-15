package com.droiddeck.launcher.input

import android.graphics.RectF
import android.os.Looper
import android.view.MotionEvent
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TouchpadGesturesTest {
    private val events = mutableListOf<String>()
    private lateinit var touchpad: TouchpadGestures
    private var time = 1000L
    private var downTime = 0L

    private val listener = object : PointerGestures.Listener {
        override fun onMove(x: Float, y: Float) { events += "move" }
        override fun onButton(button: Int, pressed: Boolean, x: Float, y: Float) {
            val name = when (button) { PointerGestures.BTN_RIGHT -> "right"; PointerGestures.BTN_MIDDLE -> "middle"; else -> "left" }
            events += "$name ${if (pressed) "down" else "up"}"
        }
        override fun onWheel(steps: Int) { events += "wheel $steps" }
        override fun onLongPress() { events += "long press" }
        override fun onScroll(dx: Float, dy: Float) { events += "scroll" }
        override fun onScrollEnd() { events += "scroll end" }
        override fun onZoom(steps: Int) { events += "zoom $steps" }
    }

    @Before fun setUp() {
        touchpad = TouchpadGestures(SLOP, listener)
        touchpad.bounds = RectF(0f, 0f, 1920f, 1080f)
        touchpad.place(960f, 540f)
    }

    @Test fun tapHoldsTheLeftButtonBeforeReleasingIt() {
        tap()
        assertEquals(listOf("left down"), events)
        advance(TouchTiming.CLICK_MS - 10)
        assertEquals(listOf("left down"), events)
        advance(100)
        assertEquals(listOf("left down", "left up"), events)
    }

    @Test fun threeFingerTapClicksTheMiddleButton() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, 1 to (400f to 300f), 2 to (600f to 310f), 3 to (800f to 320f))
        advance(60)
        touch(MotionEvent.ACTION_POINTER_UP, 2, 1 to (400f to 300f), 2 to (600f to 310f), 3 to (800f to 320f))
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        touch(MotionEvent.ACTION_UP, 0, 1 to (400f to 300f))
        advance(100)
        assertEquals(listOf("middle down", "middle up"), events)
    }

    @Test fun desktopTwoFingerSlideScrollsSmoothlyAndStops() {
        touchpad.desktop = true
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 300f))
        for (step in 1..5) {
            advance(16)
            touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 300f + step * 20f), 2 to (600f to 300f + step * 20f))
        }
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 400f), 2 to (600f to 400f))
        touch(MotionEvent.ACTION_UP, 0, 1 to (400f to 400f))
        advance(100)
        assertTrue(events.contains("scroll"))
        assertEquals("scroll end", events.last())
        assertFalse(events.any { it.startsWith("right") || it.startsWith("wheel") })
    }

    @Test fun desktopPinchZoomsWithoutScrolling() {
        touchpad.desktop = true
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (500f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (500f to 300f), 2 to (600f to 300f))
        for (step in 1..6) {
            advance(16)
            touch(MotionEvent.ACTION_MOVE, 0, 1 to (500f - step * 30f to 300f), 2 to (600f + step * 30f to 300f))
        }
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (320f to 300f), 2 to (780f to 300f))
        touch(MotionEvent.ACTION_UP, 0, 1 to (320f to 300f))
        advance(100)
        assertTrue(events.isNotEmpty() && events.all { it.startsWith("zoom -") })
    }

    @Test fun twoFingerTapHoldsTheRightButtonBeforeReleasingIt() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(60)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_UP, 0, 1 to (400f to 300f))
        assertEquals(listOf("right down"), events)
        advance(TouchTiming.CLICK_MS - 20)
        assertEquals(listOf("right down"), events)
        advance(100)
        assertEquals(listOf("right down", "right up"), events)
    }

    @Test fun slowTwoFingerTapHoldsTheRightButtonBeforeReleasingIt() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(350)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_UP, 0, 1 to (400f to 300f))
        assertEquals(listOf("right down"), events)
        advance(TouchTiming.CLICK_MS - 10)
        assertEquals(listOf("right down"), events)
        advance(100)
        assertEquals(listOf("right down", "right up"), events)
    }

    @Test fun secondTapPressesOnlyAfterTheFirstClickIsReleased() {
        tap()
        advance(20)
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        assertEquals(listOf("left down", "left up", "left down"), events)
        advance(80)
        touch(MotionEvent.ACTION_UP, 0, 1 to (400f to 300f))
        advance(200)
        assertEquals(listOf("left down", "left up", "left down", "left up"), events)
    }

    @Test fun secondFingerTappingAgainReleasesTheFirstRightClickFirst() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(60)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(20)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        assertEquals(listOf("right down", "right up", "right down"), events)
        advance(200)
        assertEquals(listOf("right down", "right up", "right down", "right up"), events)
    }

    @Test fun cancelReleasesAClickThatIsStillHeld() {
        tap()
        touchpad.cancel()
        assertEquals(listOf("left down", "left up"), events)
        advance(200)
        assertEquals(listOf("left down", "left up"), events)
    }

    @Test fun liftingTheSecondFingerAfterAScrollKeepsTheCursorStill() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 600f), 2 to (600f to 610f))
        assertTrue(events.contains("wheel -8"))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 600f), 2 to (600f to 610f))
        advance(10)
        val sx = touchpad.x
        val sy = touchpad.y
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (401f to 601f))
        assertEquals(sx, touchpad.x, 0.01f)
        assertEquals(sy, touchpad.y, 0.01f)
        assertFalse(events.contains("move"))
    }

    @Test fun trackingResumesNormallyAfterTheScroll() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 600f), 2 to (600f to 610f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 600f), 2 to (600f to 610f))
        advance(10)
        val sy = touchpad.y
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (401f to 621f))
        val delta = touchpad.y - sy
        assertTrue(delta in 20f..65f)
        assertTrue(events.contains("move"))
    }

    @Test fun liftingTheAnchorFirstAfterAScrollKeepsTheCursorStill() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 600f), 2 to (600f to 610f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_UP, 0, 1 to (400f to 600f), 2 to (600f to 610f))
        advance(10)
        val sx = touchpad.x
        val sy = touchpad.y
        touch(MotionEvent.ACTION_MOVE, 0, 2 to (601f to 611f))
        assertEquals(sx, touchpad.x, 0.01f)
        assertEquals(sy, touchpad.y, 0.01f)
        assertFalse(events.contains("move"))
    }

    @Test fun twoFingerScrollWithLeadingFingerDoesNotPressLeft() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 303f), 2 to (600f to 310f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 340f), 2 to (600f to 350f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 380f), 2 to (600f to 390f))
        assertFalse(events.contains("left down"))
        assertTrue(events.any { it.startsWith("wheel ") })
    }

    @Test fun holdDragStillWorks() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 300f), 2 to (620f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 300f), 2 to (640f to 300f))
        assertEquals("left down", events.firstOrNull())
        assertTrue(events.contains("move"))
        touch(MotionEvent.ACTION_POINTER_UP, 0, 1 to (400f to 300f), 2 to (640f to 300f))
        assertTrue(events.contains("left up"))
    }

    @Test fun anchorMovedLocksOutHoldDrag() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, 1 to (400f to 300f), 2 to (600f to 300f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 309f), 2 to (600f to 302f))
        advance(10)
        touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 309f), 2 to (640f to 302f))
        assertFalse(events.contains("left down"))
    }

    private fun tap() {
        touch(MotionEvent.ACTION_DOWN, 0, 1 to (400f to 300f))
        advance(80)
        touch(MotionEvent.ACTION_UP, 0, 1 to (400f to 300f))
    }

    private fun advance(ms: Long) {
        time += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun touch(action: Int, index: Int, vararg pointers: Pair<Int, Pair<Float, Float>>) {
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val properties = pointers.map { (id, _) ->
            MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coords = pointers.map { (_, at) ->
            MotionEvent.PointerCoords().apply { x = at.first; y = at.second; pressure = 1f; size = 1f }
        }.toTypedArray()
        val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(downTime, time, masked, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        touchpad.onTouch(event)
        event.recycle()
    }

    private companion object {
        const val SLOP = 8f
    }
}
