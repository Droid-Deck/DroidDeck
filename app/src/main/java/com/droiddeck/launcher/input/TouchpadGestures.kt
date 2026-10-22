package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs

class TouchpadGestures(
    private val slopPx: Float,
    private val listener: PointerGestures.Listener,
) {
    var x = 0f
        private set
    var y = 0f
        private set
    var bounds: android.graphics.RectF = android.graphics.RectF(0f, 0f, 1f, 1f)
    var sensitivity = 1.15f

    private val handler = Handler(Looper.getMainLooper())
    private var lastX = 0f
    private var lastY = 0f
    private var downAt = 0L
    private var lastTapAt = 0L
    private var travelled = 0f
    private var dragging = false
    private var held = false
    private var secondDownAt = 0L
    private var secondWasTap = false
    private var twoFingers = false
    private var scrollAnchorY = 0f
    private var scrolled = false
    private var anchorId = -1
    private var anchorStartX = 0f
    private var anchorStartY = 0f
    private var moverId = -1
    private var moverStartX = 0f
    private var moverStartY = 0f
    private var moverLastX = 0f
    private var moverLastY = 0f
    private var holdDrag = false
    private var scrollLocked = false
    private var clicked = 0
    private val longPress = Runnable {
        held = true
        dragging = true
        listener.onLongPress()
        listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
    }
    private val clickUp = Runnable { releaseClick() }

    private fun click(button: Int) {
        releaseClick()
        listener.onButton(button, true, x, y)
        clicked = button
        handler.postDelayed(clickUp, CLICK_MS)
    }

    private fun releaseClick() {
        handler.removeCallbacks(clickUp)
        if (clicked != 0) listener.onButton(clicked, false, x, y)
        clicked = 0
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        releaseClick()
        if (dragging) listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
        dragging = false
        held = false
        holdDrag = false
        scrollLocked = false
        anchorId = -1
        moverId = -1
        lastTapAt = 0L
    }

    fun place(px: Float, py: Float) {
        x = px.coerceIn(bounds.left, bounds.right)
        y = py.coerceIn(bounds.top, bounds.bottom)
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                releaseClick()
                lastX = event.x; lastY = event.y
                downAt = event.eventTime
                travelled = 0f; held = false; twoFingers = false; scrolled = false; secondWasTap = false
                anchorId = event.getPointerId(0); moverId = -1; holdDrag = false; scrollLocked = false
                if (event.eventTime - lastTapAt < DOUBLE_TAP_MS) {
                    dragging = true
                    listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
                } else {
                    dragging = false
                    handler.postDelayed(longPress, HOLD_MS)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(longPress)
                val i = event.actionIndex
                if (moverId == -1 && event.getPointerId(i) != anchorId) {
                    moverId = event.getPointerId(i)
                    moverStartX = event.getX(i); moverStartY = event.getY(i)
                    moverLastX = moverStartX; moverLastY = moverStartY
                    event.findPointerIndex(anchorId).takeIf { it >= 0 }?.let {
                        anchorStartX = event.getX(it); anchorStartY = event.getY(it)
                    }
                }
                if (holdDrag) return true
                twoFingers = true
                secondDownAt = event.eventTime
                secondWasTap = true
                scrollAnchorY = (event.getY(0) + event.getY(1)) / 2f
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                val ai = event.findPointerIndex(anchorId)
                val mi = if (moverId == -1) -1 else event.findPointerIndex(moverId)
                if (ai >= 0 && mi >= 0) {
                    val mx = event.getX(mi); val my = event.getY(mi)
                    if (!holdDrag && !scrolled && !scrollLocked) {
                        val anchorMoved = abs(event.getX(ai) - anchorStartX) + abs(event.getY(ai) - anchorStartY)
                        val moverMoved = abs(mx - moverStartX) + abs(my - moverStartY)
                        if (anchorMoved >= slopPx) {
                            scrollLocked = true
                        } else if (moverMoved > slopPx * HOLD_DRAG_MOVER_SLOPS && anchorMoved < slopPx * HOLD_DRAG_ANCHOR_SLOPS) {
                            holdDrag = true
                            secondWasTap = false
                            if (!dragging) {
                                dragging = true
                                listener.onButton(PointerGestures.BTN_LEFT, true, x, y)
                            }
                        }
                    }
                    if (holdDrag) {
                        val dx = mx - moverLastX
                        val dy = my - moverLastY
                        moverLastX = mx; moverLastY = my
                        val speed = abs(dx) + abs(dy)
                        val gain = sensitivity * (1f + minOf(speed / 40f, 1.5f))
                        place(x + dx * gain, y + dy * gain)
                        listener.onMove(x, y)
                        return true
                    }
                } else if (holdDrag) {
                    return true
                }
                if (twoFingers && event.pointerCount >= 2) {
                    val mid = (event.getY(0) + event.getY(1)) / 2f
                    val dy = mid - scrollAnchorY
                    val steps = (dy / SCROLL_STEP_PX).toInt()
                    if (steps != 0) {
                        listener.onWheel(-steps)
                        scrollAnchorY += steps * SCROLL_STEP_PX
                        scrolled = true
                        secondWasTap = false
                    }
                    return true
                }
                val dx = event.x - lastX
                val dy = event.y - lastY
                lastX = event.x; lastY = event.y
                travelled += abs(dx) + abs(dy)
                if (travelled > slopPx) {
                    handler.removeCallbacks(longPress)
                    val speed = (abs(dx) + abs(dy))
                    val gain = sensitivity * (1f + minOf(speed / 40f, 1.5f))
                    place(x + dx * gain, y + dy * gain)
                    listener.onMove(x, y)
                }
                return true
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val gone = event.getPointerId(event.actionIndex)
                if (holdDrag) {
                    if (gone == anchorId) {
                        listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
                        dragging = false; holdDrag = false; twoFingers = false
                        anchorId = moverId; moverId = -1
                        event.findPointerIndex(anchorId).takeIf { it >= 0 }?.let {
                            lastX = event.getX(it); lastY = event.getY(it)
                        }
                        travelled = slopPx + 1f
                    } else if (gone == moverId) {
                        moverId = -1
                    }
                    return true
                }
                if (gone == moverId) moverId = -1
                if (gone == anchorId) {
                    anchorId = event.getPointerId(if (event.actionIndex == 0) 1 else 0)
                }
                event.findPointerIndex(anchorId).takeIf { it >= 0 && it != event.actionIndex }?.let {
                    lastX = event.getX(it); lastY = event.getY(it)
                }
                if (twoFingers && secondWasTap && event.eventTime - secondDownAt < TAP_MS && !scrolled) {
                    click(PointerGestures.BTN_RIGHT)
                    secondWasTap = false
                }
                return true
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                handler.removeCallbacks(longPress)
                val cancelled = event.actionMasked == MotionEvent.ACTION_CANCEL
                when {
                    dragging -> listener.onButton(PointerGestures.BTN_LEFT, false, x, y)
                    twoFingers -> {
                        if (secondWasTap && !scrolled && !cancelled && event.eventTime - downAt < TAP_MS * 2) {
                            click(PointerGestures.BTN_RIGHT)
                        }
                    }
                    !cancelled && travelled <= slopPx && event.eventTime - downAt < TAP_MS -> {
                        click(PointerGestures.BTN_LEFT)
                        lastTapAt = event.eventTime
                    }
                }
                dragging = false; held = false; twoFingers = false
                holdDrag = false; scrollLocked = false; anchorId = -1; moverId = -1
                return true
            }
        }
        return false
    }

    companion object {
        private const val HOLD_MS = 500L
        private const val TAP_MS = 300L
        private const val CLICK_MS = 50L
        private const val DOUBLE_TAP_MS = 300L
        private const val SCROLL_STEP_PX = 36f
        private const val HOLD_DRAG_MOVER_SLOPS = 2f
        private const val HOLD_DRAG_ANCHOR_SLOPS = 0.5f
    }
}
