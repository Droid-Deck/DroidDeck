package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.MotionEvent
import android.view.VelocityTracker
import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.pow

class ScrollTouchGestures(
    private val slopPx: Float,
    private val minFlingPx: Float,
    private val listener: Listener,
) {
    interface Listener {
        fun onMove(x: Float, y: Float)
        fun onButton(button: Int, pressed: Boolean)
        fun onScroll(dx: Float, dy: Float)
        fun onLongPress()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val choreographer = Choreographer.getInstance()
    private var tracker: VelocityTracker? = null
    private var pointerId = -1
    private var startX = 0f
    private var startY = 0f
    private var lastX = 0f
    private var lastY = 0f
    private var movedAt = 0L
    private var scrolling = false
    private var vertical = true
    private var held = false
    private var dragging = false
    private var stoppedFling = false
    private var clicked = 0
    private var flingX = 0f
    private var flingY = 0f
    private var flingAt = 0L
    private var flinging = false

    private val longPress = Runnable {
        held = true
        listener.onLongPress()
    }
    private val clickUp = Runnable { releaseClick() }
    private val flingFrame = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!flinging) return
            val dt = if (flingAt == 0L) 1f / 60f else ((frameTimeNanos - flingAt) / 1e9f).coerceIn(0f, 0.05f)
            flingAt = frameTimeNanos
            val decay = exp(-dt / FLING_TIME_S)
            listener.onScroll(flingX * dt, flingY * dt)
            flingX *= decay
            flingY *= decay
            if (hypot(flingX, flingY) < FLING_STOP_PX) flinging = false else choreographer.postFrameCallback(this)
        }
    }

    fun cancel() {
        handler.removeCallbacks(longPress)
        stopFling()
        releaseClick()
        if (dragging) listener.onButton(PointerGestures.BTN_LEFT, false)
        dragging = false
        held = false
        scrolling = false
        pointerId = -1
        tracker?.recycle()
        tracker = null
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                stoppedFling = flinging
                cancel()
                pointerId = event.getPointerId(0)
                startX = event.x
                startY = event.y
                lastX = startX
                lastY = startY
                tracker = VelocityTracker.obtain().also { it.addMovement(event) }
                listener.onMove(startX, startY)
                handler.postDelayed(longPress, TouchTiming.HOLD_MS)
            }
            MotionEvent.ACTION_MOVE -> {
                val index = event.findPointerIndex(pointerId).takeIf { it >= 0 } ?: return true
                tracker?.addMovement(event)
                for (h in 0 until event.historySize)
                    sample(event.getHistoricalX(index, h), event.getHistoricalY(index, h), event.getHistoricalEventTime(h))
                sample(event.getX(index), event.getY(index), event.eventTime)
            }
            MotionEvent.ACTION_POINTER_UP -> {
                if (event.getPointerId(event.actionIndex) == pointerId) cancel()
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(longPress)
                if (pointerId == -1 || event.getPointerId(0) != pointerId) return true
                tracker?.addMovement(event)
                follow(event.x, event.y, event.eventTime)
                when {
                    scrolling -> tracker?.takeIf { event.eventTime - movedAt <= FLING_STILL_MS }?.let { t ->
                        t.computeCurrentVelocity(1000)
                        val vx = if (vertical) 0f else -t.getXVelocity(pointerId)
                        val vy = if (vertical) -t.getYVelocity(pointerId) else 0f
                        if (hypot(vx, vy) >= minFlingPx) startFling(vx, vy)
                    }
                    dragging -> listener.onButton(PointerGestures.BTN_LEFT, false)
                    held -> click(PointerGestures.BTN_RIGHT)
                    !stoppedFling -> click(PointerGestures.BTN_LEFT)
                }
                dragging = false
                held = false
                scrolling = false
                pointerId = -1
                tracker?.recycle()
                tracker = null
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun sample(x: Float, y: Float, t: Long) {
        if (!scrolling && !dragging && hypot(x - startX, y - startY) > slopPx) {
            handler.removeCallbacks(longPress)
            if (held) {
                dragging = true
                listener.onButton(PointerGestures.BTN_LEFT, true)
            } else {
                scrolling = true
                vertical = abs(y - startY) >= abs(x - startX)
                lastX = startX
                lastY = startY
            }
        }
        if (dragging) listener.onMove(x, y)
        follow(x, y, t)
    }

    private fun follow(x: Float, y: Float, t: Long) {
        if (!scrolling || (x == lastX && y == lastY)) return
        movedAt = t
        if (vertical) listener.onScroll(0f, lastY - y) else listener.onScroll(lastX - x, 0f)
        lastX = x
        lastY = y
    }

    private fun click(button: Int) {
        releaseClick()
        listener.onButton(button, true)
        clicked = button
        handler.postDelayed(clickUp, TouchTiming.CLICK_MS)
    }

    private fun releaseClick() {
        handler.removeCallbacks(clickUp)
        if (clicked != 0) listener.onButton(clicked, false)
        clicked = 0
    }

    private fun startFling(vx: Float, vy: Float) {
        flingX = vx
        flingY = vy
        flingAt = 0L
        flinging = true
        choreographer.postFrameCallback(flingFrame)
    }

    private fun stopFling() {
        flinging = false
        choreographer.removeFrameCallback(flingFrame)
    }

    companion object {
        fun wheelUnits(pixels: Int, viewSize: Int): Int {
            if (pixels == 0) return 0
            val step = floor(viewSize.toDouble().pow(2.0 / 3.0)).coerceAtLeast(1.0)
            val units = (abs(pixels) * 120.0 / step).toInt() + 1
            return if (pixels < 0) -units else units
        }

        private const val FLING_TIME_S = 0.45f
        private const val FLING_STOP_PX = 40f
        private const val FLING_STILL_MS = 60L
    }
}
