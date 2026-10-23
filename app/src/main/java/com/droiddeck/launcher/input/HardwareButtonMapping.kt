package com.droiddeck.launcher.input

import android.content.Context
import android.view.InputDevice
import android.view.KeyEvent
import android.view.MotionEvent
import org.json.JSONArray
import org.json.JSONObject

object HardwareButtonMapping {
    data class Binding(
        val descriptor: String,
        val deviceName: String,
        val scanCode: Int,
        val keyCode: Int,
        val target: String,
        val motionButton: Int = 0,
    ) {
        val label: String get() = if (motionButton > 0) "$deviceName · mouse button $motionButton"
            else "$deviceName · ${KeyEvent.keyCodeToString(keyCode)} · scan $scanCode"
    }

    private const val PREF_KEY = "hardwareButtonMapping"
    private var cachedRaw: String? = null
    private var cachedBindings: List<Binding> = emptyList()
    private val keyCodes = mapOf(
        "a" to KeyEvent.KEYCODE_BUTTON_A, "b" to KeyEvent.KEYCODE_BUTTON_B,
        "x" to KeyEvent.KEYCODE_BUTTON_X, "y" to KeyEvent.KEYCODE_BUTTON_Y,
        "lb" to KeyEvent.KEYCODE_BUTTON_L1, "rb" to KeyEvent.KEYCODE_BUTTON_R1,
        "lt" to KeyEvent.KEYCODE_BUTTON_L2, "rt" to KeyEvent.KEYCODE_BUTTON_R2,
        "l3" to KeyEvent.KEYCODE_BUTTON_THUMBL, "r3" to KeyEvent.KEYCODE_BUTTON_THUMBR,
        "select" to KeyEvent.KEYCODE_BUTTON_SELECT, "start" to KeyEvent.KEYCODE_BUTTON_START,
        "guide" to KeyEvent.KEYCODE_BUTTON_MODE,
        "up" to KeyEvent.KEYCODE_DPAD_UP, "down" to KeyEvent.KEYCODE_DPAD_DOWN,
        "left" to KeyEvent.KEYCODE_DPAD_LEFT, "right" to KeyEvent.KEYCODE_DPAD_RIGHT,
    )
    private val validTargets = keyCodes.keys + "qam"

    fun targetFor(context: Context, event: KeyEvent): String? {
        val device = event.device ?: return null
        if (!eligible(device)) return null
        return read(context).firstOrNull {
            it.descriptor == device.descriptor && it.scanCode == event.scanCode && it.keyCode == event.keyCode
        }?.target
    }

    fun targetFor(context: Context, event: MotionEvent): String? {
        val device = event.device ?: return null
        val button = mouseButton(event)
        if (button <= 0 || !pointerSource(event)) return null
        return read(context).firstOrNull { it.descriptor == device.descriptor && it.motionButton == button }?.target
    }

    @Synchronized
    fun read(context: Context): List<Binding> {
        val raw = ControllerPrefs.prefs(context).getString(PREF_KEY, null) ?: return emptyList()
        if (raw == cachedRaw) return cachedBindings
        val bindings = runCatching {
            val array = JSONArray(raw)
            (0 until array.length()).mapNotNull { index ->
                val item = array.optJSONObject(index) ?: return@mapNotNull null
                val descriptor = item.optString("descriptor")
                val target = item.optString("target")
                val scanCode = item.optInt("scanCode")
                val keyCode = item.optInt("keyCode")
                val motionButton = item.optInt("motionButton")
                if (descriptor.isBlank() || target !in validTargets ||
                    (scanCode <= 0 && keyCode <= 0 && motionButton <= 0)) null
                else Binding(descriptor, item.optString("deviceName"), scanCode, keyCode, target, motionButton)
            }
        }.getOrDefault(emptyList())
        cachedRaw = raw
        cachedBindings = bindings
        return bindings
    }

    fun capture(context: Context, event: KeyEvent) {
        val device = event.device ?: return
        if (!canCapture(event)) return
        val existing = read(context)
        if (existing.any { it.descriptor == device.descriptor && it.scanCode == event.scanCode && it.keyCode == event.keyCode }) return
        save(context, existing + Binding(device.descriptor, device.name, event.scanCode, event.keyCode, "a"))
    }

    fun canCapture(context: Context, event: MotionEvent): Boolean =
        mouseButton(event) != 0 && event.actionMasked in MOUSE_BUTTON_ACTIONS &&
            event.device?.let(::eligible) == true && pointerSource(event)

    fun capture(context: Context, event: MotionEvent) {
        val device = event.device ?: return
        val button = mouseButton(event)
        if (button <= 0 || !canCapture(context, event)) return
        val existing = read(context)
        if (existing.any { it.descriptor == device.descriptor && it.motionButton == button }) return
        save(context, existing + Binding(device.descriptor, device.name, 0, 0, "a", button))
    }

    fun hasMouseBinding(context: Context, event: MotionEvent): Boolean {
        val device = event.device ?: return false
        val button = mouseButton(event)
        return button > 0 && read(context).any {
            it.descriptor == device.descriptor && it.motionButton == button
        }
    }

    fun remap(context: Context, event: MotionEvent): KeyEvent? {
        val device = event.device ?: return null
        val button = mouseButton(event)
        if (button <= 0 || !pointerSource(event)) return null
        val binding = read(context).firstOrNull {
            it.descriptor == device.descriptor && it.motionButton == button
        } ?: return null
        val keyCode = keyCodes[binding.target] ?: return null
        val action = when (event.actionMasked) {
            MotionEvent.ACTION_DOWN, MotionEvent.ACTION_BUTTON_PRESS -> KeyEvent.ACTION_DOWN
            MotionEvent.ACTION_UP, MotionEvent.ACTION_BUTTON_RELEASE -> KeyEvent.ACTION_UP
            else -> return null
        }
        val padId = controllerIdFor(device) ?: return null
        return KeyEvent(event.downTime, event.eventTime, action, keyCode, 0, 0, padId, 0, 0, InputDevice.SOURCE_GAMEPAD)
    }

    fun setTarget(context: Context, binding: Binding, target: String) {
        if (target !in validTargets) return
        save(context, read(context).map { if (it == binding) it.copy(target = target) else it })
    }

    fun remove(context: Context, binding: Binding) {
        save(context, read(context).filterNot { it == binding })
    }

    fun remap(context: Context, event: KeyEvent): KeyEvent {
        val device = event.device ?: return event
        if (!eligible(device)) return event
        val binding = read(context).firstOrNull {
            it.descriptor == device.descriptor && it.scanCode == event.scanCode && it.keyCode == event.keyCode
        } ?: return event
        val code = keyCodes[binding.target] ?: return event
        val padId = controllerIdFor(device) ?: return event
        if (code == event.keyCode && padId == event.deviceId) return event
        return KeyEvent(event.downTime, event.eventTime, event.action, code, event.repeatCount,
            event.metaState, padId, 0, event.flags, InputDevice.SOURCE_GAMEPAD)
    }

    private fun eligible(device: InputDevice): Boolean = PadBridge.isFromController(device) ||
        (device.isExternal && !device.isVirtual && !PadBridge.isSystemVirtualInputName(device.name))

    private fun pointerSource(event: MotionEvent): Boolean =
        event.isFromSource(InputDevice.SOURCE_MOUSE) ||
            event.isFromSource(InputDevice.SOURCE_MOUSE_RELATIVE) ||
            event.isFromSource(InputDevice.SOURCE_TOUCHPAD)

    fun hasConnectedTouchpadBinding(context: Context): Boolean {
        val descriptors = read(context).filter { it.motionButton > 0 }.map { it.descriptor }.toSet()
        return InputDevice.getDeviceIds().any { id ->
            InputDevice.getDevice(id)?.let {
                it.descriptor in descriptors && it.supportsSource(InputDevice.SOURCE_TOUCHPAD)
            } == true
        }
    }

    fun canCapture(event: KeyEvent): Boolean = event.device?.let(::eligible) == true &&
        (event.scanCode > 0 || event.keyCode > 0)

    fun mouseButton(event: MotionEvent): Int = when (event.actionMasked) {
        MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE -> event.actionButton
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP -> MotionEvent.BUTTON_PRIMARY
        else -> 0
    }

    private fun controllerIdFor(device: InputDevice): Int? {
        if (PadBridge.isFromController(device)) return device.id
        val active = PadBridge.activeControllerId()
        if (PadBridge.isFromController(InputDevice.getDevice(active))) return active
        return InputDevice.getDeviceIds().firstOrNull { PadBridge.isFromController(InputDevice.getDevice(it)) }
    }

    @Synchronized
    private fun save(context: Context, bindings: List<Binding>) {
        val array = JSONArray()
        bindings.forEach { binding ->
            array.put(JSONObject().put("descriptor", binding.descriptor).put("deviceName", binding.deviceName)
                .put("scanCode", binding.scanCode).put("keyCode", binding.keyCode).put("target", binding.target)
                .put("motionButton", binding.motionButton))
        }
        val raw = array.toString()
        ControllerPrefs.prefs(context).edit().putString(PREF_KEY, raw).apply()
        cachedRaw = raw
        cachedBindings = bindings
    }

    private val MOUSE_BUTTON_ACTIONS = setOf(
        MotionEvent.ACTION_DOWN, MotionEvent.ACTION_UP,
        MotionEvent.ACTION_BUTTON_PRESS, MotionEvent.ACTION_BUTTON_RELEASE,
    )
}
