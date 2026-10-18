package com.droiddeck.launcher.input

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
class ScrollTouchGesturesTest {
    private val events = mutableListOf<String>()
    private var scrolledX = 0f
    private var scrolledY = 0f
    private lateinit var gestures: ScrollTouchGestures
    private var time = 1000L
    private var downTime = 0L

    private val listener = object : ScrollTouchGestures.Listener {
        override fun onMove(x: Float, y: Float) { events += "move ${x.toInt()},${y.toInt()}" }
        override fun onButton(button: Int, pressed: Boolean) {
            val name = if (button == PointerGestures.BTN_RIGHT) "right" else "left"
            events += "$name ${if (pressed) "down" else "up"}"
        }
        override fun onScroll(dx: Float, dy: Float) {
            scrolledX += dx
            scrolledY += dy
        }
        override fun onLongPress() { events += "long press" }
    }

    @Before fun setUp() {
        gestures = ScrollTouchGestures(SLOP, MIN_FLING, listener)
    }

    @Test fun tapClicksWhereTheFingerIs() {
        touch(MotionEvent.ACTION_DOWN, 400f, 300f)
        advance(80)
        touch(MotionEvent.ACTION_UP, 400f, 300f)
        advance(100)
        assertEquals(listOf("move 400,300", "left down", "left up"), events)
    }

    @Test fun draggingScrollsWithTheFingerAndNeverClicks() {
        touch(MotionEvent.ACTION_DOWN, 400f, 800f)
        advance(200)
        touch(MotionEvent.ACTION_MOVE, 403f, 600f)
        advance(200)
        touch(MotionEvent.ACTION_MOVE, 405f, 300f)
        advance(200)
        touch(MotionEvent.ACTION_UP, 405f, 300f)
        advance(100)
        assertEquals(500f, scrolledY, 0.01f)
        assertEquals(0f, scrolledX, 0.01f)
        assertEquals(listOf("move 400,800"), events)
    }

    @Test fun sidewaysDragScrollsSideways() {
        touch(MotionEvent.ACTION_DOWN, 900f, 400f)
        advance(200)
        touch(MotionEvent.ACTION_MOVE, 600f, 410f)
        advance(200)
        touch(MotionEvent.ACTION_UP, 600f, 410f)
        assertEquals(300f, scrolledX, 0.01f)
        assertEquals(0f, scrolledY, 0.01f)
    }

    @Test fun quickSwipeKeepsScrollingAfterLiftingAndATapStopsIt() {
        touch(MotionEvent.ACTION_DOWN, 400f, 900f)
        advance(16)
        touch(MotionEvent.ACTION_MOVE, 400f, 800f)
        advance(16)
        touch(MotionEvent.ACTION_MOVE, 400f, 700f)
        advance(16)
        touch(MotionEvent.ACTION_UP, 400f, 600f)
        val lifted = scrolledY
        repeat(3) { shadowOf(Looper.getMainLooper()).runOneTask() }
        assertTrue(scrolledY > lifted)
        touch(MotionEvent.ACTION_DOWN, 400f, 300f)
        val stopped = scrolledY
        advance(80)
        touch(MotionEvent.ACTION_UP, 400f, 300f)
        advance(500)
        assertEquals(stopped, scrolledY, 0.01f)
        assertFalse(events.contains("left down"))
    }

    @Test fun longPressRightClicks() {
        touch(MotionEvent.ACTION_DOWN, 400f, 300f)
        advance(600)
        touch(MotionEvent.ACTION_UP, 402f, 301f)
        advance(100)
        assertEquals(listOf("move 400,300", "long press", "right down", "right up"), events)
    }

    @Test fun longPressThenMoveDragsWithTheLeftButton() {
        touch(MotionEvent.ACTION_DOWN, 400f, 300f)
        advance(600)
        touch(MotionEvent.ACTION_MOVE, 500f, 300f)
        advance(50)
        touch(MotionEvent.ACTION_UP, 500f, 300f)
        assertEquals(listOf("move 400,300", "long press", "left down", "move 500,300", "left up"), events)
        assertEquals(0f, scrolledY, 0.01f)
    }

    @Test fun cancelReleasesAHeldDrag() {
        touch(MotionEvent.ACTION_DOWN, 400f, 300f)
        advance(600)
        touch(MotionEvent.ACTION_MOVE, 500f, 300f)
        gestures.cancel()
        assertEquals("left up", events.last())
        events.clear()
        touch(MotionEvent.ACTION_UP, 500f, 300f)
        advance(100)
        assertEquals(emptyList<String>(), events)
    }

    @Test fun aLiftAfterACancelDoesNotClick() {
        touch(MotionEvent.ACTION_DOWN, 400f, 300f)
        advance(50)
        gestures.cancel()
        touch(MotionEvent.ACTION_UP, 400f, 300f)
        advance(100)
        assertEquals(listOf("move 400,300"), events)
    }

    @Test fun aSecondFingerDoesNotJumpTheScroll() {
        touch(MotionEvent.ACTION_DOWN, 400f, 900f)
        advance(100)
        touch(MotionEvent.ACTION_MOVE, 400f, 700f)
        advance(100)
        pointers(MotionEvent.ACTION_POINTER_DOWN, 1, 0 to (400f to 700f), 1 to (900f to 200f))
        advance(100)
        pointers(MotionEvent.ACTION_POINTER_UP, 0, 0 to (400f to 700f), 1 to (900f to 200f))
        advance(100)
        pointers(MotionEvent.ACTION_UP, 0, 1 to (900f to 150f))
        advance(500)
        assertEquals(200f, scrolledY, 0.01f)
        assertFalse(events.contains("left down"))
    }

    @Test fun wheelUnitsLandOnWholePixelsOfWebKitsLineStep() {
        for (view in listOf(720, 1280)) {
            val step = kotlin.math.floor(Math.pow(view.toDouble(), 2.0 / 3.0)).toFloat()
            for (pixels in -300..300) {
                val scrolled = ScrollTouchGestures.wheelUnits(pixels, view) / 120f * step
                assertEquals(pixels, scrolled.toInt())
            }
        }
        assertEquals(0, ScrollTouchGestures.wheelUnits(0, 720))
        assertEquals(2, ScrollTouchGestures.wheelUnits(1, 720))
        assertEquals(-2, ScrollTouchGestures.wheelUnits(-1, 720))
    }

    private fun advance(ms: Long) {
        time += ms
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms))
    }

    private fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val event = MotionEvent.obtain(downTime, time, action, x, y, 0)
        gestures.onTouch(event)
        event.recycle()
    }

    private fun pointers(action: Int, index: Int, vararg at: Pair<Int, Pair<Float, Float>>) {
        val properties = at.map { (id, _) ->
            MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coords = at.map { (_, xy) ->
            MotionEvent.PointerCoords().apply { x = xy.first; y = xy.second; pressure = 1f; size = 1f }
        }.toTypedArray()
        val event = MotionEvent.obtain(downTime, time, action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), at.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        gestures.onTouch(event)
        event.recycle()
    }

    private companion object {
        const val SLOP = 8f
        const val MIN_FLING = 50f
    }
}
