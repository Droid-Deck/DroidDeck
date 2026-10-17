package com.droiddeck.launcher.input

import android.view.MotionEvent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ThreeFingerTapTest {
    private val tap = ThreeFingerTap(8f)
    private var time = 1000L
    private var downTime = 0L
    private val a = 1 to (400f to 300f)
    private val b = 2 to (500f to 300f)
    private val c = 3 to (600f to 300f)

    @Test fun quickThreeFingerTapFires() {
        assertEquals(listOf(false, false, false, false, false, true), threeFingerTap())
    }

    @Test fun activeOnceTheThirdFingerLandsUntilTheLastLifts() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        assertFalse(tap.active)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        assertTrue(tap.active)
        touch(MotionEvent.ACTION_POINTER_UP, 2, a, b, c)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        assertTrue(tap.active)
        touch(MotionEvent.ACTION_UP, 0, a)
        assertFalse(tap.active)
    }

    @Test fun twoFingerTapDoesNotFire() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        assertFalse(touch(MotionEvent.ACTION_UP, 0, a))
    }

    @Test fun heldTooLongDoesNotFire() {
        assertFalse(threeFingerTap(hold = 600).last())
    }

    @Test fun swipeDoesNotFire() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        touch(MotionEvent.ACTION_MOVE, 0, a, b, 3 to (600f to 400f))
        touch(MotionEvent.ACTION_POINTER_UP, 2, a, b, c)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        assertFalse(touch(MotionEvent.ACTION_UP, 0, a))
    }

    @Test fun fingersLandingFarApartDoNotFire() {
        touch(MotionEvent.ACTION_DOWN, 0, a)
        time += 500
        touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        touch(MotionEvent.ACTION_POINTER_UP, 2, a, b, c)
        touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        assertFalse(touch(MotionEvent.ACTION_UP, 0, a))
    }

    private fun threeFingerTap(hold: Long = 100): List<Boolean> {
        val out = mutableListOf<Boolean>()
        out += touch(MotionEvent.ACTION_DOWN, 0, a)
        time += 20
        out += touch(MotionEvent.ACTION_POINTER_DOWN, 1, a, b)
        time += 20
        out += touch(MotionEvent.ACTION_POINTER_DOWN, 2, a, b, c)
        time += hold
        out += touch(MotionEvent.ACTION_POINTER_UP, 2, a, b, c)
        out += touch(MotionEvent.ACTION_POINTER_UP, 1, a, b)
        out += touch(MotionEvent.ACTION_UP, 0, a)
        return out
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
        return tap.onTouch(event).also { event.recycle() }
    }
}
