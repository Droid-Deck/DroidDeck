package com.droiddeck.launcher.input

import android.content.Context
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import java.util.concurrent.atomic.AtomicInteger

internal class XrGamepadMenu {
    data class Result(val consume: Boolean = false, val request: Boolean = false)
    private data class Keys(var down: Int = 0, var latched: Boolean = false)
    private val devices = HashMap<Int, Keys>()

    fun key(device: Int, code: Int, action: Int, repeat: Int): Result {
        val bit = when (code) {
            KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK -> 1
            KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU -> 2
            KeyEvent.KEYCODE_BUTTON_MODE, KeyEvent.KEYCODE_HOME -> 4
            else -> return Result()
        }
        val keys = devices.getOrPut(device) { Keys() }
        if (action != KeyEvent.ACTION_DOWN && action != KeyEvent.ACTION_UP) return Result(keys.latched || bit == 4)
        val wasDown = keys.down and bit != 0
        if (action == KeyEvent.ACTION_DOWN) keys.down = keys.down or bit
        else keys.down = keys.down and bit.inv()
        val request = action == KeyEvent.ACTION_DOWN && !wasDown && repeat == 0 && !keys.latched &&
            (bit == 4 || keys.down and 3 == 3)
        val consume = keys.latched || request || bit == 4
        if (request) keys.latched = true
        if (keys.down == 0) devices.remove(device)
        return Result(consume, request)
    }

    fun remove(device: Int) { devices.remove(device) }
    fun clear() { devices.clear() }
}

internal class XrGamepadInput(context: Context, private val bridge: () -> PadBridge?) : InputManager.InputDeviceListener {
    private val manager = context.getSystemService(InputManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private val menu = XrGamepadMenu()
    private val sequence = AtomicInteger()
    private var resumed = false
    private var focused = false
    val menuSequence: Int get() = sequence.get()

    @Synchronized fun resume() {
        if (resumed) return
        resumed = true
        manager.registerInputDeviceListener(this, handler)
        syncControllers()
        logDevices()
    }

    @Synchronized fun pause() {
        if (!resumed) return
        resumed = false
        manager.unregisterInputDeviceListener(this)
        release()
    }

    @Synchronized fun focus(active: Boolean) {
        if (focused == active) return
        focused = active
        if (!active) release()
    }

    private fun release() {
        menu.clear()
        bridge()?.releaseAll()
    }

    @Synchronized fun key(event: KeyEvent): Boolean {
        val normalized = JoyCon.normalize(event)
        if (!isController(normalized.device) || !PadBridge.isControllerKeyEvent(normalized)) return false
        if (!resumed || !focused) return true
        val pad = bridge() ?: return true
        val result = menu.key(normalized.deviceId, normalized.keyCode, normalized.action, normalized.repeatCount)
        if (result.request) {
            // The first chord key may already be in the guest; release it before the VR menu pulse.
            for (code in intArrayOf(KeyEvent.KEYCODE_BUTTON_SELECT, KeyEvent.KEYCODE_BACK,
                    KeyEvent.KEYCODE_BUTTON_START, KeyEvent.KEYCODE_MENU)) {
                pad.onKeyEvent(KeyEvent(normalized.downTime, normalized.eventTime, KeyEvent.ACTION_UP,
                    code, 0, normalized.metaState, normalized.deviceId, 0, normalized.flags, normalized.source))
            }
            val value = sequence.incrementAndGet()
            Log.i("DroidDeckXR", "gamepad dashboard request=$value device=${normalized.deviceId}")
        }
        return if (result.consume) true else pad.onKeyEvent(normalized)
    }

    @Synchronized fun motion(event: MotionEvent): Boolean {
        if (!PadBridge.isControllerMotionEvent(event) || !isController(event.device)) return false
        if (!resumed || !focused) return true
        return bridge()?.onMotionEvent(event) ?: true
    }

    @Synchronized override fun onInputDeviceAdded(deviceId: Int) {
        if (!resumed) return
        syncControllers()
        logDevices()
    }
    @Synchronized override fun onInputDeviceRemoved(deviceId: Int) {
        if (!resumed) return
        menu.remove(deviceId)
        bridge()?.onDeviceRemoved(deviceId)
    }
    @Synchronized override fun onInputDeviceChanged(deviceId: Int) {
        if (!resumed) return
        menu.remove(deviceId)
        bridge()?.onDeviceRemoved(deviceId)
        syncControllers()
    }

    private fun syncControllers() {
        val pad = bridge() ?: return
        for (id in manager.inputDeviceIds) {
            if (!isController(manager.getInputDevice(id))) pad.onDeviceRemoved(id)
        }
        pad.syncConnectedControllers(::isController)
    }

    private fun logDevices() {
        for (id in manager.inputDeviceIds) {
            val device = manager.getInputDevice(id) ?: continue
            if (isController(device))
                Log.i("DroidDeckXR", "gamepad available id=$id name=${device.name} sources=${device.sources}")
        }
    }

    fun nativeMotion(device: Int, source: Int, action: Int, downTime: Long, eventTime: Long, axes: FloatArray): Boolean {
        if (axes.size != AXES.size) return false
        val coords = MotionEvent.PointerCoords().apply {
            for (i in AXES.indices) setAxisValue(AXES[i], axes[i])
        }
        val event = MotionEvent.obtain(downTime, eventTime, action, 1,
            arrayOf(MotionEvent.PointerProperties().apply { id = 0 }), arrayOf(coords),
            0, 0, 1f, 1f, device, 0, source, 0)
        return try { motion(event) } finally { event.recycle() }
    }

    companion object {
        internal fun isController(device: InputDevice?): Boolean {
            if (!PadBridge.isFromController(device) || device == null) return false
            if (device.supportsSource(InputDevice.SOURCE_GAMEPAD) || device.motionRanges.isEmpty()) return true
            return AXES.any { axis ->
                device.getMotionRange(axis, InputDevice.SOURCE_JOYSTICK)?.let { it.max > it.min } == true
            }
        }

        private val AXES = intArrayOf(MotionEvent.AXIS_X, MotionEvent.AXIS_Y, MotionEvent.AXIS_Z, MotionEvent.AXIS_RZ,
            MotionEvent.AXIS_RX, MotionEvent.AXIS_RY, MotionEvent.AXIS_HAT_X, MotionEvent.AXIS_HAT_Y,
            MotionEvent.AXIS_LTRIGGER, MotionEvent.AXIS_RTRIGGER, MotionEvent.AXIS_BRAKE, MotionEvent.AXIS_GAS)
    }
}
