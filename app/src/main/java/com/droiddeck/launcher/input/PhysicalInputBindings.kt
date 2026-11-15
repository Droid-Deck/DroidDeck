package com.droiddeck.launcher.input

import android.view.InputDevice
import android.view.KeyEvent

data class PhysicalInputKey(val device: String, val name: String, val keyCode: Int, val scanCode: Int) {
    val label: String get() = "$name · ${KeyEvent.keyCodeToString(keyCode).removePrefix("KEYCODE_")} ($scanCode)"

    companion object {
        fun identity(device: InputDevice) = "${device.vendorId}:${device.productId}:${device.descriptor.ifBlank { device.name }}"

        fun capture(event: KeyEvent): PhysicalInputKey? {
            val device = event.device ?: return null
            if (device.isVirtual || PadBridge.isSystemVirtualInputName(device.name)) return null
            when (event.keyCode) {
                KeyEvent.KEYCODE_VOLUME_UP, KeyEvent.KEYCODE_VOLUME_DOWN, KeyEvent.KEYCODE_VOLUME_MUTE,
                KeyEvent.KEYCODE_HOME, KeyEvent.KEYCODE_POWER, KeyEvent.KEYCODE_SLEEP,
                KeyEvent.KEYCODE_WAKEUP, KeyEvent.KEYCODE_APP_SWITCH -> return null
            }
            if (event.keyCode == KeyEvent.KEYCODE_UNKNOWN && event.scanCode <= 0) return null
            return PhysicalInputKey(identity(device), device.name, event.keyCode, event.scanCode)
        }
    }
}

data class PhysicalInputBinding(val key: PhysicalInputKey, val action: InputAction)

class PhysicalInputBindings(private val router: InputRouter) {
    private class Entry(val binding: PhysicalInputBinding, val channel: InputRouter.Channel) {
        var heldDevice = -1
        var suppressed = false
    }
    private var entries = emptyList<Entry>()
    private val released = ArrayList<Entry>()
    private val devices = HashMap<Int, String>()

    fun configure(bindings: List<PhysicalInputBinding>) {
        if (entries.map { it.binding } == bindings) return
        for (entry in entries) {
            entry.channel.close()
            if (entry.heldDevice != -1) released.add(entry)
        }
        entries = bindings.map { Entry(it, router.channel(InputRole.PHYSICAL, InputControl.A, it.action)) }
    }

    fun onKey(event: KeyEvent, enabled: Boolean): Boolean {
        if (event.action != KeyEvent.ACTION_DOWN && event.action != KeyEvent.ACTION_UP) return false
        for (i in released.indices.reversed()) {
            val old = released[i]
            if (old.heldDevice == event.deviceId && old.binding.key.keyCode == event.keyCode && old.binding.key.scanCode == event.scanCode) {
                if (event.action == KeyEvent.ACTION_UP) released.removeAt(i)
                return true
            }
        }
        for (entry in entries) {
            val key = entry.binding.key
            if (key.keyCode != event.keyCode || key.scanCode != event.scanCode) continue
            if (entry.heldDevice != -1 && entry.heldDevice == event.deviceId) {
                if (event.action == KeyEvent.ACTION_UP) {
                    entry.channel.update(0f); entry.heldDevice = -1; entry.suppressed = false
                }
                return true
            }
            if (!enabled || event.action != KeyEvent.ACTION_DOWN || entry.binding.action is InputAction.Native) continue
            val identity = devices[event.deviceId] ?: event.device?.let { PhysicalInputKey.identity(it) }?.also { devices[event.deviceId] = it } ?: continue
            if (identity != key.device) continue
            entry.heldDevice = event.deviceId
            entry.suppressed = event.repeatCount != 0
            if (!entry.suppressed) entry.channel.update(1f)
            return true
        }
        return false
    }

    fun release() {
        for (entry in entries) {
            entry.channel.release()
            if (entry.heldDevice != -1) entry.suppressed = true
        }
    }

    fun disconnected(id: Int) {
        devices.remove(id)
        released.removeAll { it.heldDevice == id }
        for (entry in entries) if (entry.heldDevice == id) { entry.channel.release(); entry.heldDevice = -1 }
    }

    fun close() { entries.forEach { it.channel.close() }; entries = emptyList(); released.clear(); devices.clear() }
}
