package com.droiddeck.launcher.input

import android.os.Looper
import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TwoFingerGesturesTest {
    private val calls = mutableListOf<String>()
    private val gestures = TwoFingerGestures(8f, object : TwoFingerGestures.Listener {
        override fun cancelTouch() { calls += "cancel" }
        override fun replay(event: MotionEvent) { calls += "replay ${event.actionMasked}" }
        override fun rightClick(x: Float, y: Float) { calls += "right $x,$y" }
        override fun scrollStart(x: Float, y: Float) { calls += "start $x,$y" }
        override fun scroll(dx: Float, dy: Float) { calls += "scroll $dx,$dy" }
        override fun scrollEnd() { calls += "end" }
    })
    private var time = 1000L
    private var downTime = 0L
    private val a = 1 to (400f to 300f)
    private val b = 2 to (500f to 300f)

    @Test fun tapRightClicksWhereTheFirstFingerLanded() {
        assertTrue(touch(MotionEvent.ACTION_DOWN, 0, a))
        time += 20
        assertTrue(touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b))
        time += 80
        assertTrue(touch(MotionEvent.ACTION_POINTER_UP, 1, a, b))
        assertTrue(touch(MotionEvent.ACTION_UP, 0, a))
        assertEquals(listOf("cancel", "right 400.0,300.0"), calls)
    }

    @Test fun holdRightClicksOnce() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        time += 20
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TwoFingerGestures.HOLD_MS + 10))
        time += 600
        assertTrue(touch(MotionEvent.ACTION_POINTER_UP, 1, a, b))
        assertTrue(touch(MotionEvent.ACTION_UP, 0, a))
        assertEquals(listOf("cancel", "right 400.0,300.0"), calls)
    }

    @Test fun slidingTogetherScrollsLikeAWheel() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        time += 20
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        time += 20
        assertTrue(touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 260f), 2 to (500f to 260f)))
        assertTrue(touch(MotionEvent.ACTION_MOVE, 0, 1 to (400f to 200f), 2 to (500f to 200f)))
        assertTrue(touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (400f to 200f), 2 to (500f to 200f)))
        assertTrue(touch(MotionEvent.ACTION_UP, 0, 1 to (400f to 200f)))
        assertEquals(listOf("cancel", "start 450.0,260.0", "scroll 0.0,60.0", "end"), calls)
    }

    @Test fun pinchPassesThroughForZoom() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        time += 20
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        assertTrue(touch(MotionEvent.ACTION_MOVE, 0, 1 to (350f to 300f), 2 to (550f to 300f)))
        assertEquals(listOf("replay 0", "replay 5", "replay 2"), calls)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TwoFingerGestures.HOLD_MS + 10))
        assertFalse(touch(MotionEvent.ACTION_MOVE, 0, 1 to (340f to 300f), 2 to (560f to 300f)))
        assertFalse(touch(MotionEvent.ACTION_POINTER_UP, 1, 1 to (340f to 300f), 2 to (560f to 300f)))
        assertFalse(touch(MotionEvent.ACTION_UP, 0, 1 to (340f to 300f)))
        assertEquals(3, calls.size)
    }

    @Test fun oneFingerTapIsReplayedOnRelease() {
        assertTrue(touch(MotionEvent.ACTION_DOWN, 0, a))
        assertTrue(calls.isEmpty())
        time += 60
        assertTrue(touch(MotionEvent.ACTION_UP, 0, a))
        assertEquals(listOf("replay 0", "replay 1"), calls)
    }

    @Test fun oneFingerStillIsReplayedAfterTheLandingWindow() {
        assertTrue(touch(MotionEvent.ACTION_DOWN, 0, a))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(TwoFingerGestures.LAND_MS + 10))
        assertEquals(listOf("replay 0"), calls)
        time += 600
        assertFalse(touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b))
        assertFalse(touch(MotionEvent.ACTION_POINTER_UP, 1, a, b))
        assertFalse(touch(MotionEvent.ACTION_UP, 0, a))
        assertEquals(listOf("replay 0"), calls)
    }

    @Test fun oneFingerDragIsReplayedOnceItMoves() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        time += 16
        assertTrue(touch(MotionEvent.ACTION_MOVE, 0, 1 to (402f to 300f)))
        assertTrue(calls.isEmpty())
        time += 16
        assertTrue(touch(MotionEvent.ACTION_MOVE, 0, 1 to (440f to 300f)))
        assertEquals(listOf("replay 0", "replay 2", "replay 2"), calls)
        assertFalse(touch(MotionEvent.ACTION_MOVE, 0, 1 to (480f to 300f)))
        assertFalse(touch(MotionEvent.ACTION_UP, 0, 1 to (480f to 300f)))
    }

    private fun touch(action: Int, index: Int, vararg pointers: Pair<Int, Pair<Float, Float>>): Boolean {
        if (action == MotionEvent.ACTION_DOWN) downTime = time
        val properties = pointers.map { (id, _) ->
            MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER }
        }.toTypedArray()
        val coords = pointers.map { (_, at) ->
            MotionEvent.PointerCoords().apply { x = at.first; y = at.second; pressure = 1f; size = 1f }
        }.toTypedArray()
        val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val event = MotionEvent.obtain(downTime, time, masked, pointers.size, properties, coords, 0, 0, 1f, 1f, 0, 0, 0, 0)
        return gestures.onTouch(event).also { event.recycle() }
    }
}
