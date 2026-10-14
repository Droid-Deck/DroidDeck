package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.ViewConfiguration
import kotlin.math.abs
import kotlin.math.roundToInt

class PointerGestures(
    private val slopPx: Float,
    private val listener: Listener,
) {
    interface Listener {
        fun onMove(x: Float, y: Float)
        fun onButton(button: Int, pressed: Boolean, x: Float, y: Float)
        fun onWheel(steps: Int)
        fun onLongPress()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var downAt = 0L
    private var moved = false
    private var held = false
    private var dragging = false
    private var scrolling = false
    private var scrollAnchorY = 0f
    private val longPress = Runnable {
        held = true
        listener.onLongPress()
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downX = event.x; downY = event.y; downAt = event.eventTime
                moved = false; held = false; dragging = false; scrolling = false
                listener.onMove(event.x, event.y)
                handler.postDelayed(longPress, HOLD_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                if (dragging) { listener.onButton(BTN_LEFT, false, event.x, event.y); dragging = false }
                scrolling = true
                scrollAnchorY = event.getY(0)
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (scrolling) {
                    val dy = event.getY(0) - scrollAnchorY
                    val steps = (dy / SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        listener.onWheel(-steps)
                        scrollAnchorY += steps * SCROLL_STEP_PX
                    }
                    return true
                }
                if (!moved && (abs(event.x - downX) > slopPx || abs(event.y - downY) > slopPx)) {
                    moved = true
                    handler.removeCallbacks(longPress)
                    if (held) {
                        dragging = true
                        listener.onButton(BTN_LEFT, true, downX, downY)
                    }
                }
                if (moved) listener.onMove(event.x, event.y)
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when {
                    scrolling -> {}
                    dragging -> listener.onButton(BTN_LEFT, false, event.x, event.y)
                    held && !moved && !cancelled -> {
                        listener.onButton(BTN_RIGHT, true, downX, downY)
                        listener.onButton(BTN_RIGHT, false, downX, downY)
                    }
                    !moved && !cancelled && event.eventTime - downAt < TAP_MS -> {
                        listener.onButton(BTN_LEFT, true, downX, downY)
                        listener.onButton(BTN_LEFT, false, downX, downY)
                    }
                }
                scrolling = false; dragging = false; held = false
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> return true
        }
        return false
    }

    companion object {
        const val BTN_LEFT = 0x110
        const val BTN_RIGHT = 0x111
        const val BTN_MIDDLE = 0x112
        private const val HOLD_MS = 450L
        private const val TAP_MS = 350L
        private const val SCROLL_STEP_PX = 40f

        fun slop(context: android.content.Context) = ViewConfiguration.get(context).scaledTouchSlop.toFloat()
    }
}
