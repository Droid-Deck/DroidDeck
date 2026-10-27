package com.droiddeck.launcher.input

import android.view.KeyEvent

class HomeGuideInput(private val setPressed: (Boolean) -> Unit, private val onCancel: () -> Unit = {}) {
    private val presses = mutableSetOf<Int>()
    private val cancelled = mutableSetOf<Int>()

    fun onKeyEvent(event: KeyEvent, enabled: Boolean): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_HOME) return false
        if (!enabled) cancel()
        val device = event.deviceId
        return when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                if (event.repeatCount > 0) return device in presses || device in cancelled
                cancelled.remove(device)
                if (!enabled || event.isCanceled) return false
                if (presses.add(device) && presses.size == 1) setPressed(true)
                true
            }
            KeyEvent.ACTION_UP -> {
                val owned = presses.remove(device)
                val wasCancelled = cancelled.remove(device)
                if (owned && presses.isEmpty()) setPressed(false)
                owned || wasCancelled
            }
            else -> device in presses || device in cancelled
        }
    }

    fun cancel() {
        val held = presses.isNotEmpty()
        cancelled.addAll(presses)
        presses.clear()
        if (held) { onCancel(); setPressed(false) }
    }

    fun onDeviceRemoved(device: Int) {
        cancelled.remove(device)
        if (presses.remove(device) && presses.isEmpty()) { onCancel(); setPressed(false) }
    }
}
