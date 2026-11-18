package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent

/**
 * A captured pointer reports movement in two forms: a mouse in its position, a touchpad in
 * AXIS_RELATIVE_X/Y. Some builds do not provide the relative axes for a captured touchpad, so
 * its movement falls back to the change between the pointer's successive positions.
 */
object PointerCapture {
    private val lastX = HashMap<Int, Float>()
    private val lastY = HashMap<Int, Float>()

    fun accepts(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    fun isTouchpad(event: MotionEvent): Boolean = event.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    fun reset() {
        lastX.clear()
        lastY.clear()
    }

    /** Remembers the touchpad pointer positions a captured event needs; call for every captured touchpad event. */
    fun track(event: MotionEvent) {
        if (!isTouchpad(event)) return
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_POINTER_DOWN -> record(event)
            MotionEvent.ACTION_MOVE -> if (event.pointerCount > 1) record(event)
            MotionEvent.ACTION_POINTER_UP -> forget(event.getPointerId(event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
            else -> Unit
        }
    }

    fun delta(event: MotionEvent): FloatArray {
        val touchpad = isTouchpad(event)
        var dx = if (touchpad) event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) else event.x
        var dy = if (touchpad) event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) else event.y
        for (i in 0 until event.historySize) {
            dx += if (touchpad) event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i) else event.getHistoricalX(i)
            dy += if (touchpad) event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i) else event.getHistoricalY(i)
        }
        if (touchpad) {
            if (dx == 0f && dy == 0f) {
                val id = event.getPointerId(0)
                val previousX = lastX[id]
                val previousY = lastY[id]
                if (previousX != null && previousY != null) {
                    dx = event.getX(0) - previousX
                    dy = event.getY(0) - previousY
                }
            }
            record(event)
        }
        return floatArrayOf(dx, dy)
    }

    private fun record(event: MotionEvent) {
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            lastX[id] = event.getX(i)
            lastY[id] = event.getY(i)
        }
    }

    private fun forget(id: Int) {
        lastX.remove(id)
        lastY.remove(id)
    }
}
