package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs

class DirectTouchHold(
    private val slopPx: Float,
    private val listener: PointerGestures.Listener,
    private val touch: Touch,
) {
    interface Touch {
        fun down(pointerId: Int, x: Float, y: Float)
        fun move(pointerId: Int, x: Float, y: Float)
        fun up(pointerId: Int, x: Float, y: Float)
        fun cancel()
    }

    private val handler = Handler(Looper.getMainLooper())
    private var downX = 0f
    private var downY = 0f
    private var downId = 0
    private var delayMs = 0L
    private var holdBehavior = TouchGestureConfig.MOUSE_BEHAVIOR_CLICK
    private var forwarded = false
    private var held = false
    private var pressing = false
    private var upX = 0f
    private var upY = 0f
    private var liftId = 0
    private var liftPending = false
    private val lift = Runnable {
        liftPending = false
        if (forwarded) {
            touch.up(liftId, upX, upY)
            forwarded = false
        }
    }
    private val release = Runnable {
        if (pressing) listener.onButton(PointerGestures.BTN_RIGHT, false, downX, downY)
        pressing = false
    }
    private val hold = Runnable {
        held = true
        listener.onLongPress()
        listener.onButton(PointerGestures.BTN_RIGHT, true, downX, downY)
        pressing = true
        if (holdBehavior != TouchGestureConfig.MOUSE_BEHAVIOR_HOLD) handler.postDelayed(release, 50L)
    }

    fun configure(config: TouchGestureConfig) {
        cancel()
        delayMs = config.longPressDelay.toLong().coerceAtLeast(1L)
        holdBehavior = config.directHoldBehavior
    }

    fun cancel() {
        handler.removeCallbacks(hold)
        handler.removeCallbacks(release)
        handler.removeCallbacks(lift)
        liftPending = false
        release.run()
        if (forwarded) touch.cancel()
        forwarded = false
        held = false
    }

    fun onTouch(event: MotionEvent): Boolean {
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            if (liftPending) {
                handler.removeCallbacks(lift)
                lift.run()
            }
            cancel()
            downX = event.x
            downY = event.y
            downId = event.getPointerId(0)
            if (holdBehavior == TouchGestureConfig.MOUSE_BEHAVIOR_NONE) forwardDown()
            else handler.postDelayed(hold, delayMs)
            return true
        }
        if (held) {
            when (event.actionMasked) {
                MotionEvent.ACTION_UP -> { release.run(); held = false }
                MotionEvent.ACTION_CANCEL -> cancel()
            }
            return true
        }
        when (event.actionMasked) {
            MotionEvent.ACTION_POINTER_DOWN -> {
                handler.removeCallbacks(hold)
                if (!forwarded) forwardDown()
                val index = event.actionIndex
                touch.down(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_MOVE -> {
                if (!forwarded && (event.pointerCount > 1 ||
                        abs(event.x - downX) > slopPx || abs(event.y - downY) > slopPx)) {
                    handler.removeCallbacks(hold)
                    forwardDown()
                }
                if (forwarded) for (index in 0 until event.pointerCount)
                    touch.move(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_POINTER_UP -> {
                val index = event.actionIndex
                touch.up(event.getPointerId(index), event.getX(index), event.getY(index))
            }
            MotionEvent.ACTION_UP -> {
                handler.removeCallbacks(hold)
                if (!forwarded) forwardDown()
                upX = event.x
                upY = event.y
                liftId = event.getPointerId(event.actionIndex)
                liftPending = true
                handler.postDelayed(lift, LIFT_MS)
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return true
    }

    private fun forwardDown() {
        touch.down(downId, downX, downY)
        forwarded = true
    }

    private companion object {
        const val LIFT_MS = 50L
    }
}
