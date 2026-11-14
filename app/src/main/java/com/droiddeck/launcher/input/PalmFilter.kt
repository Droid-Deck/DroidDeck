package com.droiddeck.launcher.input

import android.os.Build
import android.view.InputDevice
import android.view.MotionEvent

internal class PalmFilter(private val gate: PalmGate, private val deliver: (MotionEvent) -> Boolean) {
    private var passed = 0
    private var rejected = 0
    private var pens = 0
    private var logged = false
    private var downTime = 0L
    private var deviceId = 0
    private var source = 0
    private val xs = FloatArray(MAX_IDS)
    private val ys = FloatArray(MAX_IDS)
    private val tools = IntArray(MAX_IDS)

    init { gate.register(::cancelFingers) }

    fun dispatch(event: MotionEvent): Boolean {
        var all = 0
        var penBits = 0
        var palmPens = 0
        for (i in 0 until event.pointerCount) {
            val bit = 1 shl event.getPointerId(i)
            all = all or bit
            if (PenStream.isPen(event, i)) {
                penBits = penBits or bit
                if (armsGate(event, i)) palmPens = palmPens or bit
            }
        }
        if (penBits == 0 && !event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return deliver(event)
        val action = event.actionMasked
        val time = event.eventTime
        val bit = 1 shl event.getPointerId(event.actionIndex)
        val down = action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN
        if (action == MotionEvent.ACTION_DOWN) reset()
        if (down) gate.expire(time)
        if (penBits == 0 && pens == 0 && rejected == 0 && !(down && gate.armed(time))) {
            if (down) passed = passed or bit
            return output(event, action, bit, all, all)
        }
        if (palmPens != 0) {
            val lifting = action == MotionEvent.ACTION_UP || action == MotionEvent.ACTION_CANCEL ||
                (action == MotionEvent.ACTION_POINTER_UP && palmPens == bit)
            if (lifting) gate.leave(time) else gate.onPen(time, PalmGate.Pen.CONTACT, event.deviceId)
        }
        if (down) when {
            bit and penBits != 0 -> {
                pens = pens or bit
                passed = passed or bit
            }
            rejected == 0 && !gate.armed(time) -> passed = passed or bit
            else -> {
                rejected = rejected or bit
                if (!logged) gate.log("palm: finger ignored")
                logged = true
            }
        }
        return output(event, action, bit, passed and all, all)
    }

    fun reset() {
        passed = 0
        rejected = 0
        pens = 0
        logged = false
    }

    private fun cancelFingers(time: Long): Int {
        if (passed == 0 || pens != 0) return 0
        val ids = (0 until MAX_IDS).filter { passed and (1 shl it) != 0 }
        val properties = Array(ids.size) { MotionEvent.PointerProperties().apply { id = ids[it]; toolType = tools[ids[it]] } }
        val coords = Array(ids.size) { MotionEvent.PointerCoords().apply { x = xs[ids[it]]; y = ys[ids[it]] } }
        val cancel = MotionEvent.obtain(downTime, time, MotionEvent.ACTION_CANCEL, ids.size, properties, coords,
            0, 0, 1f, 1f, deviceId, 0, source, MotionEvent.FLAG_CANCELED)
        rejected = rejected or passed
        passed = 0
        try { deliver(cancel) } finally { cancel.recycle() }
        return ids.size
    }

    private fun output(event: MotionEvent, action: Int, bit: Int, out: Int, all: Int): Boolean {
        when (action) {
            MotionEvent.ACTION_POINTER_UP -> {
                passed = passed and bit.inv()
                rejected = rejected and bit.inv()
                pens = pens and bit.inv()
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> reset()
        }
        if (out == 0) return true
        for (i in 0 until event.pointerCount) {
            val id = event.getPointerId(i)
            xs[id] = event.getX(i)
            ys[id] = event.getY(i)
            tools[id] = event.getToolType(i)
        }
        if ((action == MotionEvent.ACTION_DOWN || action == MotionEvent.ACTION_POINTER_DOWN) && out == bit) {
            downTime = if (action == MotionEvent.ACTION_DOWN) event.downTime else event.eventTime
            deviceId = event.deviceId
            source = event.source
        }
        if (out == all && event.downTime == downTime) return deliver(event)
        val split = event.pointerSubset(out, downTime) ?: return true
        try { return deliver(split) } finally { split.recycle() }
    }

    companion object {
        private const val MAX_IDS = 32

        fun armsGate(event: MotionEvent, index: Int): Boolean {
            if (!PenStream.isPen(event, index)) return false
            if (event.isFromSource(InputDevice.SOURCE_TOUCHSCREEN)) return true
            val external = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) InputDevice.getDevice(event.deviceId)?.isExternal else null
            return if (event.isFromSource(InputDevice.SOURCE_MOUSE)) external == false else external != true
        }
    }
}
