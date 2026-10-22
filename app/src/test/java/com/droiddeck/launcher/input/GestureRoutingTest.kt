package com.droiddeck.launcher.input

import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.InputDevice
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import java.time.Duration
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class GestureRoutingTest {
    @Test fun controlFingerDoesNotCountTowardGestureWhenControlIsTouchedFirst() = checkOrder(controlFirst = true)
    @Test fun controlFingerDoesNotCountTowardGestureWhenScreenIsTouchedFirst() = checkOrder(controlFirst = false)

    private fun checkOrder(controlFirst: Boolean) {
        val context = RuntimeEnvironment.getApplication()
        val buttons = mutableListOf<Pair<Int, Boolean>>()
        val gestures = TouchscreenGestures(ViewConfiguration.get(context).scaledTouchSlop.toFloat(), object : TouchscreenGestures.Output {
            override fun moveTo(x: Float, y: Float) {}
            override fun moveBy(dx: Float, dy: Float, relative: Boolean) {}
            override fun button(code: Int, down: Boolean) { buttons += code to down }
            override fun key(code: Int, down: Boolean) {}
            override fun wheel(steps: Int) {}
            override fun showKeyboard() {}
        })
        val root = FrameLayout(context).apply { isMotionEventSplittingEnabled = true }
        root.addView(View(context).apply { setOnTouchListener { _, e -> gestures.onTouch(e) } }, FrameLayout.LayoutParams(1000, 600))
        root.addView(View(context).apply { setOnTouchListener { _, _ -> true } }, FrameLayout.LayoutParams(200, 600, Gravity.RIGHT))
        root.measure(View.MeasureSpec.makeMeasureSpec(1000, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        root.layout(0, 0, 1000, 600)
        val firstX = if (controlFirst) 900f else 400f
        val secondX = if (controlFirst) 400f else 900f
        val downTime = SystemClock.uptimeMillis()
        fun send(action: Int, index: Int, vararg points: Pair<Int, Float>) {
            val properties = points.map { (id, _) -> MotionEvent.PointerProperties().apply { this.id = id; toolType = MotionEvent.TOOL_TYPE_FINGER } }.toTypedArray()
            val coords = points.map { (_, px) -> MotionEvent.PointerCoords().apply { x = px; y = 300f; pressure = 1f; size = 1f } }.toTypedArray()
            MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT), points.size,
                properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0).also {
                root.dispatchTouchEvent(it); it.recycle()
            }
        }
        send(MotionEvent.ACTION_DOWN, 0, 3 to firstX)
        send(MotionEvent.ACTION_POINTER_DOWN, 1, 3 to firstX, 8 to secondX)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(30))
        send(MotionEvent.ACTION_POINTER_UP, 1, 3 to firstX, 8 to secondX)
        send(MotionEvent.ACTION_UP, 0, 3 to firstX)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(100))
        assertEquals(listOf(272 to true, 272 to false), buttons)
    }
}
