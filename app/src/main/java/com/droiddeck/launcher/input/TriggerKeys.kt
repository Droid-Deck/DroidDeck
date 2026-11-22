package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent

internal class TriggerKeys(private val dispatch: (KeyEvent) -> Unit) {
    private class Pull {
        val held = BooleanArray(2)
        val sent = BooleanArray(2)
    }

    private val handler = Handler(Looper.getMainLooper())
    private val keyed = HashSet<Int>()
    private val pulls = HashMap<Int, Pull>()
    var synthesizing = false
        private set

    /** True when this real trigger key belongs to a pull that already went out as a synthetic press. */
    fun nativeKey(event: KeyEvent): Boolean {
        val i = index(event.keyCode) ?: return false
        keyed += event.deviceId
        val pull = pulls[event.deviceId] ?: return false
        if (!pull.sent[i]) return false
        if (event.action == KeyEvent.ACTION_UP) pull.sent[i] = false
        return true
    }

    fun onMotion(event: MotionEvent) {
        if (event.source and InputDevice.SOURCE_JOYSTICK != InputDevice.SOURCE_JOYSTICK || event.action != MotionEvent.ACTION_MOVE) return
        val device = event.deviceId
        if (device in keyed) return
        val pull = pulls.getOrPut(device) { Pull() }
        update(pull, 0, maxOf(event.getAxisValue(MotionEvent.AXIS_LTRIGGER), event.getAxisValue(MotionEvent.AXIS_BRAKE)), event)
        update(pull, 1, maxOf(event.getAxisValue(MotionEvent.AXIS_RTRIGGER), event.getAxisValue(MotionEvent.AXIS_GAS)), event)
    }

    fun cancel() {
        handler.removeCallbacksAndMessages(null)
        pulls.clear()
    }

    private fun update(pull: Pull, i: Int, value: Float, event: MotionEvent) {
        if (!pull.held[i] && value >= PRESS) {
            pull.held[i] = true
            pull.sent[i] = false
            val device = event.deviceId
            val source = event.source
            // A pad that also sends trigger keys delivers them close to the axis; waiting lets that key win.
            handler.postDelayed({
                if (device !in keyed && pulls[device] === pull) {
                    pull.sent[i] = true
                    press(CODES[i], device, source)
                }
            }, KEY_WAIT_MS)
        } else if (pull.held[i] && value <= RELEASE) pull.held[i] = false
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

    private fun index(code: Int): Int? = CODES.indexOf(code).takeIf { it >= 0 }

    private companion object {
        val CODES = intArrayOf(KeyEvent.KEYCODE_BUTTON_L2, KeyEvent.KEYCODE_BUTTON_R2)
        const val PRESS = 0.6f
        const val RELEASE = 0.3f
        const val KEY_WAIT_MS = 40L
    }
}
