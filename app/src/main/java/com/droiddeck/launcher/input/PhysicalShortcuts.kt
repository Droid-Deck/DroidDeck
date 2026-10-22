package com.droiddeck.launcher.input

import android.content.Context
import android.util.Log
import android.view.InputDevice
import android.view.KeyEvent
import androidx.annotation.StringRes
import com.droiddeck.launcher.R
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject

object PhysicalShortcuts {
    internal const val KEY = "physicalShortcuts"
    private const val TAG = "PhysicalShortcuts"

    enum class Action(@StringRes val label: Int) {
        STEAM(R.string.drawer_steam_menu),
        QAM(R.string.drawer_open_qam),
        DROIDDECK(R.string.shortcuts_dd_menu),
    }

    data class Button(
        val device: String,
        val name: String,
        val keyCode: Int,
        val scanCode: Int,
    ) {
        fun matches(event: KeyEvent): Boolean =
            event.device?.let { device == identity(it) } == true &&
                keyCode == event.keyCode && scanCode == event.scanCode

        fun sameInput(other: Button): Boolean =
            device == other.device && keyCode == other.keyCode && scanCode == other.scanCode

        val label: String
            get() = KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_").removePrefix("BUTTON_")

    }

    data class Binding(val button: Button, val action: Action)

    private fun identity(device: InputDevice): String =
        "${device.vendorId}:${device.productId}:${device.descriptor.ifBlank { device.name }}"

    fun isVolumeKey(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE -> true
        else -> false
    }

    fun isDrawerNavigationKey(event: KeyEvent): Boolean = when (event.keyCode) {
        KeyEvent.KEYCODE_DPAD_UP, KeyEvent.KEYCODE_DPAD_DOWN,
        KeyEvent.KEYCODE_DPAD_LEFT, KeyEvent.KEYCODE_DPAD_RIGHT -> true
        else -> false
    }

    fun button(event: KeyEvent): Button? {
        val device = event.device ?: return null
        if (device.isVirtual || isVolumeKey(event)) return null
        when (event.keyCode) {
            KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
            KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_APP_SWITCH -> return null
        }
        if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode <= 0) return null
        return Button(identity(device), device.name, event.keyCode, event.scanCode)
    }

    fun read(context: Context): List<Binding> {
        val raw = ControllerPrefs.prefs(context).getString(KEY, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            buildList {
                for (i in 0 until array.length()) {
                    try {
                        val entry = array.getJSONObject(i)
                        val action = Action.entries.firstOrNull { it.name == entry.getString("action") }
                        val code = entry.getInt("keyCode")
                        val scan = entry.getInt("scanCode")
                        val device = entry.getString("device")
                        if (action == null || device.isBlank() || code < 0 || scan < 0) {
                            Log.w(TAG, "Ignoring invalid shortcut at index $i")
                            continue
                        }
                        val binding = Binding(Button(device, entry.getString("name"), code, scan), action)
                        if (none { it.button.sameInput(binding.button) }) add(binding)
                    } catch (e: JSONException) {
                        Log.w(TAG, "Ignoring malformed shortcut at index $i", e)
                    }
                }
            }
        } catch (e: JSONException) {
            Log.w(TAG, "Could not read physical shortcuts; reset shortcuts to restore defaults", e)
            emptyList()
        }
    }

    fun assign(context: Context, button: Button, action: Action) {
        val bindings = read(context).filterNot {
            it.button.sameInput(button) || (it.button.device == button.device && it.action == action)
        } + Binding(button, action)
        write(context, bindings)
    }

    fun remove(context: Context, binding: Binding) {
        write(context, read(context).filterNot { it == binding })
    }

    private fun write(context: Context, bindings: List<Binding>) {
        val array = JSONArray()
        bindings.forEach {
            array.put(JSONObject().put("device", it.button.device).put("name", it.button.name)
                .put("keyCode", it.button.keyCode).put("scanCode", it.button.scanCode).put("action", it.action.name))
        }
        ControllerPrefs.prefs(context).edit().putString(KEY, array.toString()).apply()
    }

}

class PhysicalShortcutRouter {
    var bindings: List<PhysicalShortcuts.Binding> = emptyList()
    private data class Press(val device: Int, val keyCode: Int, val scanCode: Int)
    private val held = mutableSetOf<Press>()

    fun onKey(event: KeyEvent, enabled: Boolean, steam: Boolean, perform: (PhysicalShortcuts.Action) -> Unit): Boolean {
        val press = Press(event.deviceId, event.keyCode, event.scanCode)
        if (event.action == KeyEvent.ACTION_UP) return held.remove(press)
        if (event.action != KeyEvent.ACTION_DOWN) return false
        if (press in held) return true
        if (!enabled || bindings.isEmpty()) return false
        val button = PhysicalShortcuts.button(event) ?: return false
        val binding = bindings.firstOrNull { it.button.sameInput(button) } ?: return false
        if (!steam && binding.action != PhysicalShortcuts.Action.DROIDDECK) return false
        held.add(press)
        if (event.repeatCount == 0) perform(binding.action)
        return true
    }

    fun onDeviceRemoved(deviceId: Int) {
        held.removeAll { it.device == deviceId }
    }

    fun clear() {
        held.clear()
    }
}
