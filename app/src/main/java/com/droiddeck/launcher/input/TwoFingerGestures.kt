package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import kotlin.math.abs
import kotlin.math.hypot

class TwoFingerGestures(private val slopPx: Float, private val listener: Listener) {
    interface Listener {
        fun cancelTouch()
        fun replay(event: MotionEvent)
        fun rightClick(x: Float, y: Float)
        fun scrollStart(x: Float, y: Float)
        fun scroll(dx: Float, dy: Float)
        fun scrollEnd()
    }

    private enum class State { IDLE, PENDING, CANDIDATE, TAPPED, HELD, SCROLLING, PASSING }

    private val handler = Handler(Looper.getMainLooper())
    private var state = State.IDLE
    private var x = 0f
    private var y = 0f
    private var twoAt = 0L
    private var startSpan = 0f
    private var lastX = 0f
    private var lastY = 0f
    private val starts = HashMap<Int, Pair<Float, Float>>()
    private val held = ArrayList<MotionEvent>()
    private val hold = Runnable {
        if (state != State.CANDIDATE) return@Runnable
        state = State.HELD
        drop()
        listener.cancelTouch()
        listener.rightClick(x, y)
    }
    private val release = Runnable {
        if (state == State.PENDING) pass(State.IDLE)
    }

    fun cancel() {
        if (state == State.SCROLLING) listener.scrollEnd()
        handler.removeCallbacks(hold)
        handler.removeCallbacks(release)
        drop()
        state = State.IDLE
    }

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                cancel()
                starts.clear()
                x = event.x
                y = event.y
                note(event, event.actionIndex)
                state = State.PENDING
                keep(event)
                handler.postDelayed(release, LAND_MS)
                return true
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                note(event, event.actionIndex)
                when (state) {
                    State.PENDING -> {
                        handler.removeCallbacks(release)
                        keep(event)
                        if (event.pointerCount != 2) {
                            pass(State.PASSING)
                            return true
                        }
                        state = State.CANDIDATE
                        twoAt = event.eventTime
                        startSpan = span(event)
                        lastX = midX(event)
                        lastY = midY(event)
                        handler.postDelayed(hold, HOLD_MS)
                        return true
                    }
                    State.CANDIDATE -> {
                        keep(event)
                        pass(State.PASSING)
                        return true
                    }
                    State.SCROLLING, State.HELD, State.TAPPED -> return true
                    else -> Unit
                }
            }
            MotionEvent.ACTION_MOVE -> when (state) {
                State.PENDING -> {
                    keep(event)
                    if (moved(event)) pass(State.IDLE)
                    return true
                }
                State.CANDIDATE -> {
                    if (event.pointerCount == 2 && moved(event)) {
                        handler.removeCallbacks(hold)
                        if (abs(span(event) - startSpan) > slopPx * 2) {
                            keep(event)
                            pass(State.PASSING)
                            return true
                        }
                        state = State.SCROLLING
                        drop()
                        listener.cancelTouch()
                        lastX = midX(event)
                        lastY = midY(event)
                        listener.scrollStart(lastX, lastY)
                        return true
                    }
                    keep(event)
                    return true
                }
                State.SCROLLING -> {
                    if (event.pointerCount >= 2) {
                        val mx = midX(event)
                        val my = midY(event)
                        listener.scroll(lastX - mx, lastY - my)
                        lastX = mx
                        lastY = my
                    }
                    return true
                }
                State.TAPPED, State.HELD -> return true
                else -> Unit
            }
            MotionEvent.ACTION_POINTER_UP -> when (state) {
                State.CANDIDATE -> {
                    handler.removeCallbacks(hold)
                    keep(event)
                    if (event.eventTime - twoAt < TAP_MS) {
                        state = State.TAPPED
                        drop()
                        return true
                    }
                    pass(State.PASSING)
                    return true
                }
                State.SCROLLING, State.HELD, State.TAPPED -> return true
                else -> Unit
            }
            MotionEvent.ACTION_UP -> {
                val was = state
                if (was == State.PENDING) {
                    keep(event)
                    pass(State.IDLE)
                    return true
                }
                cancel()
                when (was) {
                    State.TAPPED -> {
                        listener.cancelTouch()
                        listener.rightClick(x, y)
                        return true
                    }
                    State.SCROLLING, State.HELD -> return true
                    else -> Unit
                }
            }
            MotionEvent.ACTION_CANCEL -> cancel()
        }
        return false
    }

    private fun keep(event: MotionEvent) {
        held += MotionEvent.obtain(event)
    }

    private fun drop() {
        held.forEach { it.recycle() }
        held.clear()
    }

    private fun pass(next: State) {
        handler.removeCallbacks(release)
        handler.removeCallbacks(hold)
        state = next
        val events = held.toList()
        held.clear()
        events.forEach {
            listener.replay(it)
            it.recycle()
        }
    }

    private fun note(event: MotionEvent, index: Int) {
        starts[event.getPointerId(index)] = event.getX(index) to event.getY(index)
    }

    private fun moved(event: MotionEvent): Boolean = (0 until event.pointerCount).any { i ->
        val start = starts[event.getPointerId(i)] ?: return@any false
        hypot(event.getX(i) - start.first, event.getY(i) - start.second) > slopPx
    }

    private fun span(event: MotionEvent) = hypot(event.getX(1) - event.getX(0), event.getY(1) - event.getY(0))
    private fun midX(event: MotionEvent) = (event.getX(0) + event.getX(1)) / 2f
    private fun midY(event: MotionEvent) = (event.getY(0) + event.getY(1)) / 2f

    companion object {
        const val LAND_MS = 250L
        const val TAP_MS = 350L
        const val HOLD_MS = 500L
    }
}
