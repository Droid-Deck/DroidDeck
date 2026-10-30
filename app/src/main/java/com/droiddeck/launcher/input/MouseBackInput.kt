package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyCharacterMap
import android.view.KeyEvent

/**
 * Some firmware turns a mouse's right button into Back. Most report it from the mouse itself; REDMAGIC's
 * cast mode injects it from no device and without the virtual-hard-key flag that the navigation bar and
 * back gesture set, so that form only counts while a mouse is connected.
 */
class MouseBackInput(private val sendRight: (Boolean) -> Unit, private val mouseAttached: () -> Boolean) {
    private val held = mutableSetOf<Int>()

    fun onKeyEvent(event: KeyEvent, toGuest: Boolean): Boolean {
        if (event.keyCode != KeyEvent.KEYCODE_BACK) return false
        val device = event.deviceId
        if (device in held) {
            if (event.action == KeyEvent.ACTION_UP) {
                held.remove(device)
                sendRight(false)
            }
            return true
        }
        if (event.action != KeyEvent.ACTION_DOWN || event.repeatCount > 0 || !toGuest) return false
        if (!isFromMouse(event) && !(isInjectedBack(event) && mouseAttached())) return false
        held.add(device)
        sendRight(true)
        return true
    }

    companion object {
        fun isFromMouse(event: KeyEvent): Boolean =
            event.isFromSource(InputDevice.SOURCE_MOUSE) || event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE)

        fun isInjectedBack(event: KeyEvent): Boolean =
            event.deviceId == KeyCharacterMap.VIRTUAL_KEYBOARD && (event.flags and KeyEvent.FLAG_VIRTUAL_HARD_KEY) == 0
    }
}
