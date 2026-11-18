package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent

/**
 * A captured pointer reports movement in two forms: a mouse in its position, a touchpad in
 * AXIS_RELATIVE_X/Y. Some builds do not provide the relative axes for a captured touchpad, so
 * its movement falls back to the change between the pointer's successive positions.
 */
object PointerCapture {
    private val lastX = HashMap<Long, Float>()
    private val lastY = HashMap<Long, Float>()

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
            MotionEvent.ACTION_POINTER_UP -> forget(key(event, event.actionIndex))
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> forgetDevice(event.deviceId)
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
                val k = key(event, 0)
                val previousX = lastX[k]
                val previousY = lastY[k]
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
            val k = key(event, i)
            lastX[k] = event.getX(i)
            lastY[k] = event.getY(i)
        }
    }

    /** A pointer id is only unique within its device. */
    private fun key(event: MotionEvent, index: Int): Long =
        (event.deviceId.toLong() shl 32) or (event.getPointerId(index).toLong() and 0xffffffffL)

    private fun forget(k: Long) {
        lastX.remove(k)
        lastY.remove(k)
    }

    private fun forgetDevice(deviceId: Int) {
        val x = lastX.keys.iterator()
        while (x.hasNext()) if ((x.next() shr 32).toInt() == deviceId) x.remove()
        val y = lastY.keys.iterator()
        while (y.hasNext()) if ((y.next() shr 32).toInt() == deviceId) y.remove()
    }
}
