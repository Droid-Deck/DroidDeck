package com.droiddeck.launcher.input

import android.view.MotionEvent

class MouseWheelInput(private val send: (vertical120: Int, horizontal120: Int) -> Unit) {
    private var device = -1
    private var horizontal = 0f
    private var vertical = 0f

    fun reset() { horizontal = 0f; vertical = 0f; device = -1 }

    fun onEvent(event: MotionEvent) {
        if (device != event.deviceId) { reset(); device = event.deviceId }
        for (h in 0 until event.historySize)
            sample(event.getHistoricalAxisValue(MotionEvent.AXIS_HSCROLL, 0, h),
                event.getHistoricalAxisValue(MotionEvent.AXIS_VSCROLL, 0, h))
        sample(event.getAxisValue(MotionEvent.AXIS_HSCROLL), event.getAxisValue(MotionEvent.AXIS_VSCROLL))
    }

    private fun sample(x: Float, y: Float) {
        if (!x.isFinite() || !y.isFinite()) return
        horizontal -= x * 120f
        vertical -= y * 120f
        val h = horizontal.toInt()
        val v = vertical.toInt()
        horizontal -= h
        vertical -= v
        if (h != 0 || v != 0) send(v, h)
    }
}
