package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.MotionEvent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import kotlin.math.abs
import kotlin.math.hypot

class AndroidTouchpadInput(
    private val output: Output,
    private val slop: Float = 8f,
    private val horizontalScrollFactor: Float = 1f,
    private val verticalScrollFactor: Float = 1f,
) {
    interface Output {
        fun move(x: Float, y: Float, relative: Boolean)
        fun button(code: Int, down: Boolean)
        fun scroll(dx: Float, dy: Float)
        fun scrollEnd(cancelled: Boolean)
        fun contact(fingers: Int) {}
        fun eventTime(time: Long) {}
        fun pinchBegin(fingers: Int)
        fun pinchUpdate(dx: Float, dy: Float, scale: Float)
        fun pinchEnd(cancelled: Boolean)
        fun key(code: Int, down: Boolean) {}
        fun keyboard() {}
        fun swipeBegin(fingers: Int) {}
        fun swipeUpdate(dx: Float, dy: Float) {}
        fun swipeEnd(cancelled: Boolean) {}
    }

    private enum class Gesture { NONE, SCROLL, PINCH }
    private var gesture = Gesture.NONE
    private var device = -1
    private var accepting = false
    private var blocked = false
    private var scale = 1f
    private var rawPair = false
    private var centerX = 0f
    private var centerY = 0f
    private var initialSpan = 0f
    private val handler = Handler(Looper.getMainLooper())
    private var legacyScrolling = false
    private val legacyEnd = Runnable {
        output.eventTime(SystemClock.uptimeMillis())
        finish(false)
    }
    private val buttons = mutableSetOf<Int>()
    private val actions = GestureActionExecutor(object : GestureActionExecutor.Output {
        override fun key(code: Int, down: Boolean) = output.key(code, down)
        override fun button(code: Int, down: Boolean) = this@AndroidTouchpadInput.button(code, down)
        override fun keyboard() = output.keyboard()
    })
    private val multi = MultiFingerInput(slop, object : MultiFingerInput.Output {
        override fun eventTime(time: Long) = output.eventTime(time)
        override fun cancelLower() {
            finish(true)
            buttons.toList().forEach { button(it, false) }
            rawPair = false
        }
        override fun click(action: String) = actions.click(action)
        override fun hold(action: String, down: Boolean) = actions.hold(action, down)
        override fun swipeBegin(fingers: Int) = output.swipeBegin(fingers)
        override fun swipeUpdate(dx: Float, dy: Float) = output.swipeUpdate(dx, dy)
        override fun swipeEnd(cancelled: Boolean) = output.swipeEnd(cancelled)
    })

    fun configure(config: TouchGestureConfig) {
        cancel()
        multi.configure(config)
    }

    fun cancel(time: Long = SystemClock.uptimeMillis()) {
        output.eventTime(time)
        multi.cancel()
        actions.cancel()
        finish(true)
        buttons.toList().forEach { button(it, false) }
        accepting = false
        blocked = true
        rawPair = false
    }

    fun deviceRemoved(id: Int) {
        if (device == id) { cancel(); device = -1 }
    }

    private fun button(code: Int, down: Boolean) {
        if (if (down) buttons.add(code) else buttons.remove(code)) output.button(code, down)
    }

    private fun finish(cancelled: Boolean) {
        handler.removeCallbacks(legacyEnd)
        legacyScrolling = false
        when (gesture) {
            Gesture.SCROLL -> output.scrollEnd(cancelled)
            Gesture.PINCH -> output.pinchEnd(cancelled)
            Gesture.NONE -> {}
        }
        gesture = Gesture.NONE
        scale = 1f
    }

    private fun begin(next: Gesture) {
        if (gesture == next) return
        finish(true)
        buttons.toList().forEach { button(it, false) }
        gesture = next
        if (next == Gesture.PINCH) output.pinchBegin(2)
    }

    fun onEvent(event: MotionEvent, classification: Int = AndroidPointerSource.classification(event)): Boolean {
        if (AndroidPointerSource.kind(event, classification) != AndroidPointerSource.Kind.TOUCHPAD) return false
        val action = event.actionMasked
        output.eventTime(if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime)
        if (device != event.deviceId) {
            cancel(event.eventTime)
            device = event.deviceId
            blocked = false
        }
        if (action == MotionEvent.ACTION_DOWN) {
            cancel(event.eventTime)
            accepting = true
            blocked = false
            output.contact(if (classification == 3 || classification == 5) 2 else event.pointerCount)
        }
        if (action == MotionEvent.ACTION_CANCEL || action == MotionEvent.ACTION_HOVER_EXIT) {
            cancel(event.eventTime)
            return true
        }
        output.eventTime(if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime)
        if (action == MotionEvent.ACTION_SCROLL) {
            blocked = false
            begin(Gesture.SCROLL)
            legacyScrolling = true
            for (h in 0 until event.historySize) legacyScrollSample(event, h)
            legacyScrollSample(event, -1)
            handler.removeCallbacks(legacyEnd)
            handler.postDelayed(legacyEnd, 120)
            return true
        }
        if (legacyScrolling) finish(true)
        if (multi.onTouch(event, gesture != Gesture.NONE || buttons.isNotEmpty())) {
            if (action == MotionEvent.ACTION_UP) accepting = false
            return true
        }
        if (classification == 4 || event.pointerCount > 2) { cancel(event.eventTime); return true }
        if (blocked) {
            if (action == MotionEvent.ACTION_HOVER_ENTER || action == MotionEvent.ACTION_HOVER_MOVE ||
                (classification == 0 && event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE))) blocked = false
            else return true
        }
        val classified = when (classification) {
            3 -> Gesture.SCROLL
            5 -> Gesture.PINCH
            else -> Gesture.NONE
        }
        if (classified != Gesture.NONE) {
            if (!accepting) return true
            output.eventTime(if (event.historySize > 0) event.getHistoricalEventTime(0) else event.eventTime)
            begin(classified)
            for (h in 0 until event.historySize) classifiedSample(event, h)
            classifiedSample(event, -1)
        } else if (gesture != Gesture.NONE && !rawPair) {
            if (action != MotionEvent.ACTION_UP) return true
        } else if (event.pointerCount == 2 && accepting) {
            if (action == MotionEvent.ACTION_POINTER_DOWN) {
                rawPair = true
                centerX = (event.getX(0) + event.getX(1)) / 2
                centerY = (event.getY(0) + event.getY(1)) / 2
                initialSpan = hypot(event.getX(0) - event.getX(1), event.getY(0) - event.getY(1))
            } else if (rawPair && action == MotionEvent.ACTION_MOVE) {
                for (h in 0 until event.historySize) rawSample(event, h)
                rawSample(event, -1)
            }
        } else if (gesture == Gesture.NONE && !rawPair) {
            val relative = event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)
            when (action) {
                MotionEvent.ACTION_HOVER_MOVE, MotionEvent.ACTION_HOVER_ENTER,
                MotionEvent.ACTION_MOVE -> {
                    if (relative) {
                        for (h in 0 until event.historySize) {
                            output.eventTime(event.getHistoricalEventTime(h))
                            output.move(event.getHistoricalX(0, h), event.getHistoricalY(0, h), true)
                        }
                    }
                    if (action != MotionEvent.ACTION_MOVE || accepting || relative) {
                        output.eventTime(event.eventTime)
                        output.move(event.x, event.y, relative)
                    }
                }
                MotionEvent.ACTION_DOWN -> {
                    if (event.buttonState != 0) output.move(event.x, event.y, relative)
                    syncButtons(event.buttonState)
                }
                MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE ->
                    code(event.actionButton)?.let { button(it, action == MotionEvent.ACTION_BUTTON_PRESS) }
            }
        }
        if (action == MotionEvent.ACTION_POINTER_UP || action == MotionEvent.ACTION_UP) {
            output.eventTime(event.eventTime)
            finish(false)
            if (action == MotionEvent.ACTION_UP) {
                buttons.toList().forEach { button(it, false) }
                accepting = false
                rawPair = false
            } else {
                accepting = false
                blocked = true
            }
        }
        return true
    }

    private fun axis(event: MotionEvent, axis: Int, history: Int): Float =
        if (history < 0) event.getAxisValue(axis) else event.getHistoricalAxisValue(axis, 0, history)

    private fun classifiedSample(event: MotionEvent, history: Int) {
        output.eventTime(if (history < 0) event.eventTime else event.getHistoricalEventTime(history))
        if (gesture == Gesture.SCROLL) {
            val sx = axis(event, MotionEvent.AXIS_GESTURE_SCROLL_X_DISTANCE, history)
            val sy = axis(event, MotionEvent.AXIS_GESTURE_SCROLL_Y_DISTANCE, history)
            if (sx.isFinite() && sy.isFinite() && (sx != 0f || sy != 0f)) output.scroll(sx, sy)
        } else {
            val factor = axis(event, MotionEvent.AXIS_GESTURE_PINCH_SCALE_FACTOR, history)
            val next = scale * factor
            if (factor > 0f && next.isFinite() && next > 0f) scale = next
            // Android gesture offsets are touchpad-size fractions, not pixel translation.
            if (factor > 0f && next.isFinite() && next > 0f) output.pinchUpdate(0f, 0f, scale)
        }
    }

    private fun rawSample(event: MotionEvent, history: Int) {
        output.eventTime(if (history < 0) event.eventTime else event.getHistoricalEventTime(history))
        fun x(i: Int) = if (history < 0) event.getX(i) else event.getHistoricalX(i, history)
        fun y(i: Int) = if (history < 0) event.getY(i) else event.getHistoricalY(i, history)
        val cx = (x(0) + x(1)) / 2
        val cy = (y(0) + y(1)) / 2
        val span = hypot(x(0) - x(1), y(0) - y(1))
        if (gesture == Gesture.NONE) {
            when {
                initialSpan > 0 && abs(span - initialSpan) > slop * 3 -> begin(Gesture.PINCH)
                abs(cx - centerX) + abs(cy - centerY) > slop -> begin(Gesture.SCROLL)
                else -> return
            }
        }
        if (gesture == Gesture.PINCH) output.pinchUpdate(cx - centerX, cy - centerY, span / initialSpan)
        else output.scroll(centerX - cx, centerY - cy)
        centerX = cx
        centerY = cy
    }

    private fun legacyScrollSample(event: MotionEvent, history: Int) {
        output.eventTime(if (history < 0) event.eventTime else event.getHistoricalEventTime(history))
        val dx = -axis(event, MotionEvent.AXIS_HSCROLL, history) * horizontalScrollFactor
        val dy = -axis(event, MotionEvent.AXIS_VSCROLL, history) * verticalScrollFactor
        if (dx.isFinite() && dy.isFinite() && (dx != 0f || dy != 0f)) output.scroll(dx, dy)
    }

    private fun syncButtons(state: Int) {
        for (mask in listOf(MotionEvent.BUTTON_PRIMARY, MotionEvent.BUTTON_SECONDARY, MotionEvent.BUTTON_TERTIARY))
            code(mask)?.let { button(it, state and mask != 0) }
    }

    private fun code(mask: Int): Int? = when (mask) {
        MotionEvent.BUTTON_PRIMARY -> PointerGestures.BTN_LEFT
        MotionEvent.BUTTON_SECONDARY -> PointerGestures.BTN_RIGHT
        MotionEvent.BUTTON_TERTIARY -> PointerGestures.BTN_MIDDLE
        else -> null
    }
}
