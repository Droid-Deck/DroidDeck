package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque

class GestureActionExecutor(private val output: Output) {
    interface Output {
        fun key(code: Int, down: Boolean)
        fun button(code: Int, down: Boolean)
        fun keyboard()
    }

    private val handler = Handler(Looper.getMainLooper())
    private val queue = ArrayDeque<String>()
    private var clicking: String? = null
    private val held = linkedSetOf<String>()
    private val keys = mutableMapOf<Int, Int>()
    private val buttons = mutableMapOf<Int, Int>()
    private val release = Runnable {
        clicking?.let { set(it, false) }
        clicking = null
        next()
    }

    fun click(action: String) {
        queue.addLast(action)
        if (clicking == null) next()
    }

    private fun next() {
        if (queue.isEmpty()) return
        val action = queue.removeFirst()
        clicking = action
        set(action, true)
        handler.postDelayed(release, TouchTiming.CLICK_MS)
    }

    fun hold(action: String, down: Boolean) {
        if (if (down) held.add(action) else held.remove(action)) set(action, down)
    }

    private fun set(action: String, down: Boolean) {
        if (action == TouchGestureConfig.ACTION_SHOW_KEYBOARD) {
            if (down) output.keyboard()
            return
        }
        val button = when (action) {
            TouchGestureConfig.ACTION_LEFT_CLICK -> PointerGestures.BTN_LEFT
            TouchGestureConfig.ACTION_RIGHT_CLICK -> PointerGestures.BTN_RIGHT
            TouchGestureConfig.ACTION_MIDDLE_CLICK -> PointerGestures.BTN_MIDDLE
            else -> null
        }
        if (button != null) update(buttons, button, down, output::button)
        val codes = GestureKeys.actionCodes(action)
        for (code in if (down) codes else codes.reversedArray()) update(keys, code, down, output::key)
    }

    private fun update(state: MutableMap<Int, Int>, code: Int, down: Boolean, send: (Int, Boolean) -> Unit) {
        val before = state[code] ?: 0
        val after = if (down) before + 1 else (before - 1).coerceAtLeast(0)
        if (after == 0) state.remove(code) else state[code] = after
        if ((before == 0) != (after == 0)) send(code, down)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        queue.clear()
        clicking?.let { set(it, false) }
        clicking = null
        held.toList().asReversed().forEach { hold(it, false) }
    }
}
