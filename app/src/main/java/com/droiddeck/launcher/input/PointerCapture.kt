package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent

/**
 * A captured pointer reports movement in two forms: a mouse in its position, a touchpad in
 * AXIS_RELATIVE_X/Y, since a touchpad's position is the finger on its surface, not on the screen.
 */
object PointerCapture {
    fun accepts(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    fun isTouchpad(event: MotionEvent): Boolean = event.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    fun delta(event: MotionEvent): FloatArray {
        val touchpad = isTouchpad(event)
        var dx = if (touchpad) event.getAxisValue(MotionEvent.AXIS_RELATIVE_X) else event.x
        var dy = if (touchpad) event.getAxisValue(MotionEvent.AXIS_RELATIVE_Y) else event.y
        for (i in 0 until event.historySize) {
            dx += if (touchpad) event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_X, i) else event.getHistoricalX(i)
            dy += if (touchpad) event.getHistoricalAxisValue(MotionEvent.AXIS_RELATIVE_Y, i) else event.getHistoricalY(i)
        }
        return floatArrayOf(dx, dy)
    }
}
