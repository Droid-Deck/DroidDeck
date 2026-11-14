package com.droiddeck.launcher.input

import android.os.SystemClock
import android.view.View

internal class ThrottledRedraw(private val view: View, private val intervalMs: Long) {
    private var last = 0L
    private val redraw = Runnable {
        last = SystemClock.uptimeMillis()
        view.invalidate()
    }

    fun request() {
        view.removeCallbacks(redraw)
        view.postDelayed(redraw, (last + intervalMs - SystemClock.uptimeMillis()).coerceAtLeast(0L))
    }

    fun cancel() {
        view.removeCallbacks(redraw)
    }
}
