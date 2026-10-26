package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs

class DirectTouchHold(
    private val slopPx: Float,
    private val listener: PointerGestures.Listener,
    private val cancelTouch: () -> Unit,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var x = 0f
    private var y = 0f
    private var consumed = false
    private var pressed = false
    private var delayMs = 0L
    private val release = Runnable {
        if (pressed) listener.onButton(PointerGestures.BTN_RIGHT, false, x, y)
        pressed = false
    }
    private val hold = Runnable {
        cancelTouch()
        consumed = true
        listener.onLongPress()
        listener.onButton(PointerGestures.BTN_RIGHT, true, x, y)
        pressed = true
        handler.postDelayed(release, 50L)
    }

    fun configure(config: TouchGestureConfig) {
        cancel()
        delayMs = if (config.longPressEnabled) config.longPressDelay.toLong().coerceAtLeast(1L) else 0L
    }

    fun cancel() {
        handler.removeCallbacks(hold)
        handler.removeCallbacks(release)
        release.run()
        consumed = false
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                x = event.x; y = event.y
                if (delayMs > 0L) handler.postDelayed(hold, delayMs)
            }
            MotionEvent.ACTION_MOVE -> if (event.pointerCount > 1 ||
                abs(event.x - x) > slopPx || abs(event.y - y) > slopPx) handler.removeCallbacks(hold)
            MotionEvent.ACTION_POINTER_DOWN, MotionEvent.ACTION_POINTER_UP,
            MotionEvent.ACTION_UP -> handler.removeCallbacks(hold)
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return consumed
    }
}
