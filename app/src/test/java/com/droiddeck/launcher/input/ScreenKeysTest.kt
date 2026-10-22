package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.View
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ScreenKeysTest {
    private val context = RuntimeEnvironment.getApplication()

    @Before fun clearKeys() {
        ControllerPrefs.setScreenKeys(context, emptyList())
    }

    @Test fun missesAreForwardedAsTheirOwnTouchStreamWhileAKeyIsHeld() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val keys = ScreenKeys(context) { code, down -> sent += code to down }
        keys.layout(0, 0, 1000, 600)
        keys.add(listOf(KeyEvent.KEYCODE_W))
        val forwarded = mutableListOf<Pair<Int, Int>>()
        keys.onTouchMiss = { forwarded += it.actionMasked to it.getPointerId(0) }

        send(keys, MotionEvent.ACTION_DOWN, listOf(0), listOf(300f), listOf(372f))
        send(keys, MotionEvent.ACTION_POINTER_DOWN or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0, 1), listOf(300f, 900f), listOf(372f, 550f))
        send(keys, MotionEvent.ACTION_MOVE, listOf(0, 1), listOf(310f, 880f), listOf(372f, 530f))
        send(keys, MotionEvent.ACTION_POINTER_UP or (1 shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), listOf(0, 1), listOf(310f, 880f), listOf(372f, 530f))
        send(keys, MotionEvent.ACTION_UP, listOf(0), listOf(310f), listOf(372f))

        assertEquals(
            listOf(
                MotionEvent.ACTION_DOWN to 1,
                MotionEvent.ACTION_MOVE to 1,
                MotionEvent.ACTION_UP to 1,
            ),
            forwarded,
        )
        assertEquals(listOf(KeyEvent.KEYCODE_W to true, KeyEvent.KEYCODE_W to false), sent)
    }

    @Test fun gestureRecognizerKeepsOneSplitTargetOwnerUntilItsStreamEnds() {
        val gate = GestureTouchStreamGate()
        fun accepts(source: GestureTouchStreamGate.Source, action: Int): Boolean {
            val event = MotionEvent.obtain(0L, 1L, action, 1f, 1f, 0)
            return try { gate.accepts(source, event) } finally { event.recycle() }
        }

        assertTrue(accepts(GestureTouchStreamGate.Source.SURFACE, MotionEvent.ACTION_DOWN))
        assertFalse(accepts(GestureTouchStreamGate.Source.SCREEN_KEYS, MotionEvent.ACTION_DOWN))
        assertTrue(accepts(GestureTouchStreamGate.Source.SURFACE, MotionEvent.ACTION_MOVE))
        assertTrue(accepts(GestureTouchStreamGate.Source.SURFACE, MotionEvent.ACTION_UP))
        assertTrue(accepts(GestureTouchStreamGate.Source.SCREEN_KEYS, MotionEvent.ACTION_DOWN))
        assertFalse(accepts(GestureTouchStreamGate.Source.SURFACE, MotionEvent.ACTION_DOWN))
        assertTrue(accepts(GestureTouchStreamGate.Source.SCREEN_KEYS, MotionEvent.ACTION_CANCEL))
        assertTrue(accepts(GestureTouchStreamGate.Source.SURFACE, MotionEvent.ACTION_DOWN))
        assertFalse(accepts(GestureTouchStreamGate.Source.SCREEN_KEYS, MotionEvent.ACTION_DOWN))
        gate.reset()
        assertTrue(accepts(GestureTouchStreamGate.Source.SURFACE, MotionEvent.ACTION_DOWN))
    }

    @Test fun loadingLayoutReleasesLatchedKeysBeforeReplacingEntries() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val keys = ScreenKeys(context) { code, down -> sent += code to down }
        keys.layout(0, 0, 1000, 600)
        keys.add(listOf(KeyEvent.KEYCODE_SHIFT_LEFT))
        keys.setToggle(listOf(KeyEvent.KEYCODE_SHIFT_LEFT), true)
        send(keys, MotionEvent.ACTION_DOWN, listOf(0), listOf(300f), listOf(372f))
        send(keys, MotionEvent.ACTION_UP, listOf(0), listOf(300f), listOf(372f))

        keys.load("")

        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT to true, KeyEvent.KEYCODE_SHIFT_LEFT to false), sent)
    }

    @Test fun hidingTheViewReleasesLatchedKeys() {
        val sent = mutableListOf<Pair<Int, Boolean>>()
        val keys = ScreenKeys(context) { code, down -> sent += code to down }
        keys.layout(0, 0, 1000, 600)
        keys.add(listOf(KeyEvent.KEYCODE_SHIFT_LEFT))
        keys.setToggle(listOf(KeyEvent.KEYCODE_SHIFT_LEFT), true)
        send(keys, MotionEvent.ACTION_DOWN, listOf(0), listOf(300f), listOf(372f))
        send(keys, MotionEvent.ACTION_UP, listOf(0), listOf(300f), listOf(372f))

        keys.visibility = View.GONE

        assertEquals(listOf(KeyEvent.KEYCODE_SHIFT_LEFT to true, KeyEvent.KEYCODE_SHIFT_LEFT to false), sent)
    }

    private fun send(view: View, action: Int, ids: List<Int>, xs: List<Float>, ys: List<Float>) {
        val properties = Array(ids.size) { index ->
            MotionEvent.PointerProperties().apply {
                id = ids[index]
                toolType = MotionEvent.TOOL_TYPE_FINGER
            }
        }
        val coordinates = Array(ids.size) { index ->
            MotionEvent.PointerCoords().apply {
                x = xs[index]
                y = ys[index]
                pressure = 1f
                size = 1f
            }
        }
        val event = MotionEvent.obtain(
            0L, 1L, action, ids.size, properties, coordinates, 0, 0,
            1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0,
        )
        try {
            view.onTouchEvent(event)
        } finally {
            event.recycle()
        }
    }
}
