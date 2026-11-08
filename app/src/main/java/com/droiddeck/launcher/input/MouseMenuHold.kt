package com.droiddeck.launcher.input

import kotlin.math.abs

/**
 * Holding the middle button still opens the session menu. While the session reads the mouse over USB, Android has
 * no cursor, so this is how a mouse reaches the menu and, through it, the system again.
 */
class MouseMenuHold(
    private val schedule: (Runnable, Long) -> Unit,
    private val cancel: (Runnable) -> Unit,
    private val onMenu: () -> Unit,
) {
    private var holding = false
    private var travel = 0
    private val fire = Runnable {
        if (!holding) return@Runnable
        holding = false
        onMenu()
    }

    fun onButton(index: Int, down: Boolean) {
        if (index != MIDDLE) return
        cancel(fire)
        holding = down
        travel = 0
        if (down) schedule(fire, HOLD_MS)
    }

    fun onMove(dx: Int, dy: Int) {
        if (!holding) return
        travel += abs(dx) + abs(dy)
        if (travel > SLOP) reset()
    }

    fun reset() {
        holding = false
        cancel(fire)
    }

    companion object {
        const val MIDDLE = 2
        const val HOLD_MS = 2000L
        const val SLOP = 48
    }
}
