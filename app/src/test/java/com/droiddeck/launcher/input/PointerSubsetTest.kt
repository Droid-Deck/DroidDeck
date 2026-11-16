package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PointerSubsetTest {
    @Test fun aForwardedPointerKeepsItsHistoryAndTimes() {
        val properties = Array(2) { MotionEvent.PointerProperties().apply { id = it; toolType = if (it == 0) MotionEvent.TOOL_TYPE_FINGER else MotionEvent.TOOL_TYPE_STYLUS } }
        fun coords(step: Int) = Array(2) { MotionEvent.PointerCoords().apply { x = 100f * it + step; y = 50f; pressure = 0.1f * step } }
        val event = MotionEvent.obtain(5, 10, MotionEvent.ACTION_MOVE, 2, properties, coords(1), 0, 0, 1f, 1f, 3, 0,
            InputDevice.SOURCE_TOUCHSCREEN or InputDevice.SOURCE_STYLUS, MotionEvent.FLAG_CANCELED)
        event.addBatch(14, coords(2), 0)
        event.addBatch(18, coords(3), 0)
        val split = event.pointerSubset(1 shl 1)!!
        try {
            assertEquals(1, split.pointerCount)
            assertEquals(MotionEvent.TOOL_TYPE_STYLUS, split.getToolType(0))
            assertEquals(2, split.historySize)
            assertEquals(listOf(10L, 14L), (0 until split.historySize).map { split.getHistoricalEventTime(it) })
            assertEquals(listOf(101f, 102f), (0 until split.historySize).map { split.getHistoricalX(0, it) })
            assertEquals(0.2f, split.getHistoricalPressure(0, 1), 1e-6f)
            assertEquals(18L, split.eventTime)
            assertEquals(103f, split.x, 0f)
            assertEquals(5L, split.downTime)
            assertEquals(MotionEvent.ACTION_MOVE, split.actionMasked)
            assertEquals(event.source, split.source)
            assertEquals(MotionEvent.FLAG_CANCELED, split.flags and MotionEvent.FLAG_CANCELED)
        } finally {
            split.recycle()
            event.recycle()
        }
    }

    @Test fun anEventWithoutHistoryStaysASingleSample() {
        val properties = arrayOf(MotionEvent.PointerProperties().apply { id = 2; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = 7f; y = 8f })
        val event = MotionEvent.obtain(0, 20, MotionEvent.ACTION_DOWN, 1, properties, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        val split = event.pointerSubset(1 shl 2)!!
        try {
            assertEquals(0, split.historySize)
            assertEquals(20L, split.eventTime)
            assertEquals(MotionEvent.ACTION_DOWN, split.actionMasked)
            assertEquals(7f, split.x, 0f)
            assertNull(event.pointerSubset(1 shl 0))
        } finally {
            split.recycle()
            event.recycle()
        }
    }
}
