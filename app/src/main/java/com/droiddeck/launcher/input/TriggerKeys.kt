package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

internal class TriggerKeys(private val dispatch: (KeyEvent) -> Unit) {
    private val handler = Handler(Looper.getMainLooper())
    private val keyed = HashSet<Int>()
    private val held = HashMap<Int, BooleanArray>()
    var synthesizing = false
        private set

    fun noteKey(event: KeyEvent) {
        if (event.keyCode == KeyEvent.KEYCODE_BUTTON_L2 || event.keyCode == KeyEvent.KEYCODE_BUTTON_R2) keyed += event.deviceId
    }

    fun onMotion(event: MotionEvent) {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) return
        val device = event.deviceId
        if (device in keyed) return
        val state = held.getOrPut(device) { BooleanArray(2) }
        update(state, 0, maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)), KeyEvent.KEYCODE_BUTTON_L2, event)
        update(state, 1, maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)), KeyEvent.KEYCODE_BUTTON_R2, event)
    }

    private fun update(state: BooleanArray, i: Int, value: Float, code: Int, event: MotionEvent) {
        if (!state[i] && value >= PRESS) {
            state[i] = true
            val device = event.deviceId
            val source = event.source
            // A pad that also sends trigger keys delivers them close to the axis; waiting lets that key win.
            handler.postDelayed({ if (device !in keyed) press(code, device, source) }, KEY_WAIT_MS)
        } else if (state[i] && value <= RELEASE) state[i] = false
    }

    private fun press(code: Int, device: Int, source: Int) {
        val now = SystemClock.uptimeMillis()
        synthesizing = true
        try {
            dispatch(KeyEvent(now, now, KeyEvent.ACTION_DOWN, code, 0, 0, device, 0, 0, source))
            dispatch(KeyEvent(now, now, KeyEvent.ACTION_UP, code, 0, 0, device, 0, 0, source))
        } finally {
            synthesizing = false
        }
    }

    private companion object {
        const val PRESS = 0.6f
        const val RELEASE = 0.3f
        const val KEY_WAIT_MS = 40L
    }
}
