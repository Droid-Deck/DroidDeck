package com.droiddeck.launcher.input

import android.view.MotionEvent
import kotlin.math.hypot

class ThreeFingerTap(private val slopPx: Float) {
    var active = false
        private set

    private val starts = HashMap<Int, Pair<Float, Float>>()
    private var downAt = 0L
    private var threeAt = 0L
    private var valid = false

    fun onTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                starts.clear()
                active = false
                valid = true
                downAt = event.eventTime
                note(event, event.actionIndex)
            }
            MotionEvent.ACTION_POINTER_DOWN -> {
                note(event, event.actionIndex)
                if (event.pointerCount > 3) valid = false
                if (event.pointerCount == 3 && !active) {
                    active = true
                    threeAt = event.eventTime
                    if (threeAt - downAt > LAND_MS) valid = false
                }
            }
            MotionEvent.ACTION_MOVE -> for (i in 0 until event.pointerCount) {
                val start = starts[event.getPointerId(i)] ?: continue
                if (hypot(event.getX(i) - start.first, event.getY(i) - start.second) > slopPx) valid = false
            }
            MotionEvent.ACTION_UP -> {
                val fired = active && valid && event.eventTime - threeAt < TAP_MS
                active = false
                return fired
            }
            MotionEvent.ACTION_CANCEL -> active = false
        }
        return false
    }

    private fun note(event: MotionEvent, index: Int) {
        starts[event.getPointerId(index)] = event.getX(index) to event.getY(index)
    }

    private companion object {
        const val LAND_MS = 250L
        const val TAP_MS = 350L
    }
}
