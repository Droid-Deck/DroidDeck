package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

class MultiFingerInput(
    private val slop: Float,
    private val output: Output,
    private val captureThree: Boolean = true,
) {
    interface Output {
        fun cancelLower()
        fun click(action: String)
        fun hold(action: String, down: Boolean)
        fun swipeBegin(fingers: Int)
        fun swipeUpdate(dx: Float, dy: Float)
        fun swipeEnd(cancelled: Boolean)
        fun eventTime(time: Long) {}
    }

    private data class Point(val x: Float, val y: Float)
    private val handler = Handler(Looper.getMainLooper())
    private var config = TouchGestureConfig()
    private val origins = linkedMapOf<Int, Point>()
    private var count = 0
    private var tracking = false
    private var captured = false
    private var draining = false
    private var locked = false
    private var moved = false
    private var nativeSwipe = false
    private var heldAction: String? = null
    private var lastX = 0f
    private var lastY = 0f
    private val holdTimer = Runnable {
        if (!draining && !locked && !moved && count == 3) {
            locked = true
            val action = config.threeFingerHoldAction
            if (TouchGestureConfig.isMouseButtonAction(action) &&
                config.threeFingerHoldMouseBehavior == TouchGestureConfig.MOUSE_BEHAVIOR_CLICK) output.click(action)
            else {
                heldAction = action
                output.hold(action, true)
            }
        }
    }

    fun configure(next: TouchGestureConfig) {
        cancel()
        config = next
    }

    fun cancel() {
        finish(true)
        count = 0
        tracking = false
        origins.clear()
        captured = false
        draining = false
        locked = false
        moved = false
    }

    private fun finish(cancelled: Boolean) {
        handler.removeCallbacks(holdTimer)
        if (nativeSwipe) output.swipeEnd(cancelled)
        nativeSwipe = false
        heldAction?.let { output.hold(it, false) }
        heldAction = null
    }

    fun onTouch(event: MotionEvent, lowerCommitted: Boolean = false): Boolean {
        output.eventTime(event.eventTime)
        val action = event.actionMasked
        if (action == MotionEvent.ACTION_DOWN) {
            cancel()
            tracking = true
            count = 1
            return false
        }
        if (action == MotionEvent.ACTION_CANCEL) {
            val consumed = captured
            cancel()
            return consumed
        }
        if (!tracking) return false
        if (action == MotionEvent.ACTION_POINTER_DOWN) {
            if (draining) {
                if (count >= 3 && !captured) { output.cancelLower(); captured = true }
                return captured
            }
            val next = event.pointerCount
            if (next >= 3) {
                if (locked || lowerCommitted || next > 4) {
                    finish(true)
                    output.cancelLower()
                    captured = true
                    draining = true
                } else {
                    finish(true)
                    count = next
                    origins.clear()
                    for (i in 0 until next) origins[event.getPointerId(i)] = Point(event.getX(i), event.getY(i))
                    if (origins.size != next || origins.values.any { !it.x.isFinite() || !it.y.isFinite() }) {
                        output.cancelLower()
                        captured = true
                        draining = true
                        return true
                    }
                    lastX = origins.values.sumOf { it.x.toDouble() }.toFloat() / count
                    lastY = origins.values.sumOf { it.y.toDouble() }.toFloat() / count
                    moved = false
                    if (captureThree || next == 4) {
                        output.cancelLower()
                        captured = true
                    }
                    if (captureThree && next == 3 && config.threeFingerHoldEnabled)
                        handler.postDelayed(holdTimer, config.threeFingerHoldDelay.coerceAtLeast(0).toLong())
                }
            } else count = next
        } else if (!draining && count >= 3) {
            if (lowerCommitted && !captured) {
                locked = true
                handler.removeCallbacks(holdTimer)
            } else samples(event)
            if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
                if (captured && count == 3 && !locked && !moved && config.threeFingerTapEnabled)
                    output.click(config.threeFingerTapAction)
                finish(false)
                draining = true
            }
        }
        val consumed = captured
        if (action == MotionEvent.ACTION_UP) {
            finish(false)
            count = 0
            tracking = false
            origins.clear()
            captured = false
            draining = false
        }
        return consumed
    }

    private fun samples(event: MotionEvent) {
        if (origins.size != count) return
        val indices = origins.keys.map { event.findPointerIndex(it) }
        if (indices.any { it < 0 } || (event.pointerCount != count && event.actionMasked != MotionEvent.ACTION_POINTER_DOWN)) {
            finish(true)
            output.cancelLower()
            captured = true
            draining = true
            return
        }
        for (h in 0 until event.historySize) sample(event, indices, h)
        sample(event, indices, -1)
    }

    private fun sample(event: MotionEvent, indices: List<Int>, history: Int) {
        output.eventTime(if (history < 0) event.eventTime else event.getHistoricalEventTime(history))
        if (draining) return
        fun x(i: Int) = if (history < 0) event.getX(i) else event.getHistoricalX(i, history)
        fun y(i: Int) = if (history < 0) event.getY(i) else event.getHistoricalY(i, history)
        if (indices.any { !x(it).isFinite() || !y(it).isFinite() }) {
            finish(true)
            output.cancelLower()
            captured = true
            draining = true
            return
        }
        val cx = indices.sumOf { x(it).toDouble() }.toFloat() / count
        val cy = indices.sumOf { y(it).toDouble() }.toFloat() / count
        val ox = origins.values.sumOf { it.x.toDouble() }.toFloat() / count
        val oy = origins.values.sumOf { it.y.toDouble() }.toFloat() / count
        val threshold = config.gestureThreshold.coerceAtLeast(1).toFloat()
        val travel = indices.zip(origins.values).maxOf { (i, p) -> hypot(x(i) - p.x, y(i) - p.y) }
        if (travel > slop) handler.removeCallbacks(holdTimer)
        if (travel > threshold) moved = true
        if (!locked && hypot(cx - ox, cy - oy) > threshold &&
            !(count == 3 && !captureThree && config.threeFingerDragEnabled)) {
            locked = true
            handler.removeCallbacks(holdTimer)
            if (!captured) output.cancelLower()
            captured = true
            val action = swipeAction(cx - ox, cy - oy)
            if (action == TouchGestureConfig.ACTION_NATIVE_SWIPE) {
                nativeSwipe = true
                output.swipeBegin(count)
                output.swipeUpdate(cx - ox, cy - oy)
            } else if (action != TouchGestureConfig.ACTION_NONE) output.click(action)
        } else if (nativeSwipe && (cx != lastX || cy != lastY)) output.swipeUpdate(cx - lastX, cy - lastY)
        lastX = cx
        lastY = cy
    }

    private fun swipeAction(dx: Float, dy: Float): String = if (count == 3) {
        if (abs(dx) > abs(dy)) {
            if (dx < 0) config.threeFingerSwipeLeft else config.threeFingerSwipeRight
        } else if (dy < 0) config.threeFingerSwipeUp else config.threeFingerSwipeDown
    } else {
        if (abs(dx) > abs(dy)) {
            if (dx < 0) config.fourFingerSwipeLeft else config.fourFingerSwipeRight
        } else if (dy < 0) config.fourFingerSwipeUp else config.fourFingerSwipeDown
    }
}
