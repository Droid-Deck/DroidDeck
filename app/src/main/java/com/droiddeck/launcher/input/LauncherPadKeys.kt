package com.droiddeck.launcher.input

import android.view.KeyEvent

/** The launcher keys a raw-HID DualSense holds: d-pad or left stick past half travel, and the face, shoulder and menu buttons. */
object LauncherPadKeys {
    private const val STICK = 0.55f

    fun held(state: DualSense.State?): Set<Int> {
        if (state == null) return emptySet()
        val keys = LinkedHashSet<Int>()
        if (state.dpadUp || state.leftY < -STICK) keys += KeyEvent.KEYCODE_DPAD_UP
        if (state.dpadDown || state.leftY > STICK) keys += KeyEvent.KEYCODE_DPAD_DOWN
        if (state.dpadLeft || state.leftX < -STICK) keys += KeyEvent.KEYCODE_DPAD_LEFT
        if (state.dpadRight || state.leftX > STICK) keys += KeyEvent.KEYCODE_DPAD_RIGHT
        if (state.cross) keys += KeyEvent.KEYCODE_BUTTON_A
        if (state.circle) keys += KeyEvent.KEYCODE_BUTTON_B
        if (state.square) keys += KeyEvent.KEYCODE_BUTTON_X
        if (state.triangle) keys += KeyEvent.KEYCODE_BUTTON_Y
        if (state.l1) keys += KeyEvent.KEYCODE_BUTTON_L1
        if (state.r1) keys += KeyEvent.KEYCODE_BUTTON_R1
        if (state.options) keys += KeyEvent.KEYCODE_BUTTON_START
        if (state.create) keys += KeyEvent.KEYCODE_BUTTON_SELECT
        return keys
    }
}
