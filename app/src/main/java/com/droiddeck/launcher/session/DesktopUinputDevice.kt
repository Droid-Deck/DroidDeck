package com.droiddeck.launcher.session

import com.droiddeck.launcher.input.HeldInput
import com.droiddeck.launcher.input.InputSpace

class DesktopUinputDevice(
    private val absX: IntRange?,
    private val absY: IntRange?,
    private val sink: Sink,
) {
    interface Sink {
        fun key(code: Int, down: Boolean)
        fun button(code: Int, down: Boolean)
        fun motion(dx: Int, dy: Int)
        fun position(x: Int, y: Int)
        fun wheel(vertical120: Int, horizontal120: Int)
    }

    private val heldKeys = HeldInput(sink::key)
    private val heldButtons = HeldInput(sink::button)
    private var dx = 0
    private var dy = 0
    private var x = -1
    private var y = -1
    private var moved = false
    private var wheel = 0
    private var hwheel = 0
    private var wheelHiRes = 0
    private var hwheelHiRes = 0
    private var sawWheelHiRes = false
    private var sawHwheelHiRes = false

    fun event(type: Int, code: Int, value: Int) {
        when (type) {
            EV_SYN -> if (code == SYN_REPORT) report()
            EV_KEY -> if (value != KEY_REPEAT) key(code, value != 0)
            EV_REL -> relative(code, value)
            EV_ABS -> absolute(code, value)
        }
    }

    fun release() {
        flushMotion()
        heldKeys.release(SOURCE)
        heldButtons.release(SOURCE)
    }

    private fun key(code: Int, down: Boolean) {
        val button = when {
            code in BTN_LEFT..BTN_TASK -> code
            code == BTN_TOUCH && absX != null -> BTN_LEFT
            else -> null
        }
        if (button != null) {
            flushMotion()
            heldButtons.set(SOURCE, button, down)
            return
        }
        if (code !in KEY_ESC until BTN_MISC && code !in KEY_OK..KEY_MAX) return
        flushMotion()
        heldKeys.set(SOURCE, code, down)
    }

    private fun relative(code: Int, value: Int) {
        when (code) {
            REL_X -> dx += value
            REL_Y -> dy += value
            REL_WHEEL -> wheel += value
            REL_HWHEEL -> hwheel += value
            REL_WHEEL_HI_RES -> { wheelHiRes += value; sawWheelHiRes = true }
            REL_HWHEEL_HI_RES -> { hwheelHiRes += value; sawHwheelHiRes = true }
        }
    }

    private fun absolute(code: Int, value: Int) {
        when (code) {
            ABS_X -> absX?.let { x = scale(value, it, InputSpace.WIDTH); moved = true }
            ABS_Y -> absY?.let { y = scale(value, it, InputSpace.HEIGHT); moved = true }
        }
    }

    private fun report() {
        flushMotion()
        val vertical = if (sawWheelHiRes) -wheelHiRes else -wheel * WHEEL_UNITS
        val horizontal = if (sawHwheelHiRes) hwheelHiRes else hwheel * WHEEL_UNITS
        wheel = 0
        hwheel = 0
        wheelHiRes = 0
        hwheelHiRes = 0
        if (vertical != 0 || horizontal != 0) sink.wheel(vertical, horizontal)
    }

    private fun flushMotion() {
        if (dx != 0 || dy != 0) sink.motion(dx, dy)
        dx = 0
        dy = 0
        if (moved && x >= 0 && y >= 0) sink.position(x, y)
        moved = false
    }

    companion object {
        const val EV_SYN = 0x00
        const val EV_KEY = 0x01
        const val EV_REL = 0x02
        const val EV_ABS = 0x03
        const val SYN_REPORT = 0
        const val KEY_REPEAT = 2
        const val KEY_ESC = 1
        const val BTN_MISC = 0x100
        const val BTN_LEFT = 0x110
        const val BTN_TASK = 0x117
        const val BTN_TOUCH = 0x14a
        const val KEY_OK = 0x160
        const val KEY_MAX = 0x2ff
        const val REL_X = 0x00
        const val REL_Y = 0x01
        const val REL_HWHEEL = 0x06
        const val REL_WHEEL = 0x08
        const val REL_WHEEL_HI_RES = 0x0b
        const val REL_HWHEEL_HI_RES = 0x0c
        const val ABS_X = 0x00
        const val ABS_Y = 0x01
        const val WHEEL_UNITS = 120
        private const val SOURCE = 1

        private fun scale(value: Int, range: IntRange, size: Int): Int =
            ((value.toLong() - range.first) * (size - 1) / (range.last.toLong() - range.first))
                .toInt().coerceIn(0, size - 1)
    }
}
