package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock

class DesktopAutoKeyboard(
    private val keyboard: KeyboardHost,
    private val canShow: () -> Boolean,
) {
    private val handler = Handler(Looper.getMainLooper())
    private var focused = false
    private var tappedAt: Long? = null
    private val checkFocus = Runnable { showIfRequested() }

    var enabled = false
        set(value) {
            if (field == value) return
            field = value
            cancel()
        }

    fun tapped() {
        if (!enabled || !canShow()) return
        tappedAt = SystemClock.uptimeMillis()
        handler.removeCallbacks(checkFocus)
        // Linux can report focus before Android receives the end of the tap.
        handler.postDelayed(checkFocus, 180)
    }

    fun focusChanged(value: Boolean) {
        focused = value
        if (!value) {
            if (canShow() && keyboard.shown) keyboard.hide()
        } else {
            showIfRequested()
        }
    }

    fun cancel() {
        tappedAt = null
        handler.removeCallbacks(checkFocus)
    }

    private fun showIfRequested() {
        val tap = tappedAt ?: return
        if (SystemClock.uptimeMillis() - tap >= 1500) {
            cancel()
            return
        }
        if (!enabled || !focused || !canShow()) return
        cancel()
        keyboard.show()
    }
}
