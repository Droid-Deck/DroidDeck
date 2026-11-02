package com.droiddeck.launcher.input

import android.view.KeyEvent

object GuestKeys {
    /** Sends [event] as evdev presses, with Shift around characters typed by a keyboard that has no Shift key; false when it has no evdev code. */
    fun send(event: KeyEvent, sendKey: (code: Int, down: Boolean) -> Unit): Boolean {
        val down = event.action == KeyEvent.ACTION_DOWN
        if (!down && event.action != KeyEvent.ACTION_UP) return false
        var evdev = EvdevKeys.fromKeyCode(event.keyCode)
        val ch = event.unicodeChar
        val plain = if (ch > 0) EvdevKeys.unshiftedChar(ch) else 0
        if (evdev <= 0 && ch > 0) {
            val code = EvdevKeys.fromKeyCode(EvdevKeys.keycodeForChar(if (plain != 0) plain else ch))
            if (code > 0) evdev = code
        }
        val shiftEvdev = if (evdev > 0 && plain != 0 && event.deviceId <= 0) KEY_LEFTSHIFT else 0
        if (evdev <= 0 && event.scanCode > 0) evdev = event.scanCode
        if (evdev <= 0) return false
        if (down) {
            if (shiftEvdev != 0) sendKey(shiftEvdev, true)
            sendKey(evdev, true)
        } else {
            sendKey(evdev, false)
            if (shiftEvdev != 0) sendKey(shiftEvdev, false)
        }
        return true
    }

    private const val KEY_LEFTSHIFT = 42
}
