package com.droiddeck.launcher.input

import android.view.KeyEvent
import android.os.Handler
import android.os.Looper

class BackQamInput(private val setPressed: (Boolean) -> Unit, private val onTap: (Boolean) -> Unit,
    private val doubleTapMs: () -> Long = { 500 }, private val onCancel: () -> Unit = {}) {
    private class Press(val hold: Runnable, var held: Boolean = false, var used: Boolean = false)
    private val handler = Handler(Looper.getMainLooper())
    private val presses = mutableMapOf<Int, Press>()
    private val cancelled = mutableSetOf<Int>()
    private var tapPending = false
    private val singleTap = Runnable { tapPending = false; onTap(false) }

    private fun cancelTap() {
        handler.removeCallbacks(singleTap)
        tapPending = false
    }

    fun onChordInput() {
        if (presses.isEmpty()) return
        val alreadyHeld = presses.values.any { it.held }
        presses.values.forEach {
            handler.removeCallbacks(it.hold)
            it.held = true
            it.used = true
        }
        cancelTap()
        if (!alreadyHeld) setPressed(true)
    }

    fun onKeyEvent(event: KeyEvent, enabled: Boolean): Boolean {
        if (!enabled) cancel()
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val device = event.deviceId
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return enabled || device in presses || device in cancelled
                cancelled.remove(device)
                if (!enabled || event.isCanceled) return false
                if (device in presses) return true
                handler.removeCallbacks(singleTap)
                val hold = Runnable {
                    val press = presses[device] ?: return@Runnable
                    val alreadyHeld = presses.values.any { it.held }
                    press.held = true
                    presses.values.forEach { it.used = true }
                    cancelTap()
                    if (!alreadyHeld) setPressed(true)
                }
                presses[device] = Press(hold, used = presses.values.any { it.held })
                handler.postDelayed(hold, 700)
                true
            }
            KeyEvent.ACTION_UP -> {
                val press = presses.remove(device)
                val wasCancelled = cancelled.remove(device)
                if (press != null) {
                    handler.removeCallbacks(press.hold)
                    if (press.held && presses.values.none { it.held }) setPressed(false)
                    if (!press.used && !event.isCanceled) {
                        if (tapPending) { cancelTap(); onTap(true) }
                        else { tapPending = true; handler.postDelayed(singleTap, doubleTapMs()) }
                    } else if (event.isCanceled) cancelTap()
                }
                press != null || wasCancelled || enabled
            }
            else -> device in presses || device in cancelled
        }
    }

    fun cancel() {
        cancelTap()
        val wasHeld = presses.values.any { it.held }
        presses.values.forEach { handler.removeCallbacks(it.hold) }
        cancelled.addAll(presses.keys)
        presses.clear()
        onCancel()
        if (wasHeld) setPressed(false)
    }

    fun onDeviceRemoved(device: Int) {
        cancelled.remove(device)
        val press = presses.remove(device) ?: return
        handler.removeCallbacks(press.hold)
        cancelTap()
        if (press.held && presses.values.none { it.held }) {
            onCancel()
            setPressed(false)
        }
    }
}
