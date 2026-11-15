package com.droiddeck.launcher.input

import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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

data class PhysicalInputBinding(val key: PhysicalInputKey, val action: InputAction,
    val part: ElementSource? = null, val repeat: Boolean = false)

class PhysicalInputBindings(private val router: InputRouter) {
    private class Entry(val binding: PhysicalInputBinding, val channel: InputRouter.Channel,
        val hold: InputRouter.Channel?, val double: InputRouter.Channel?, val repeat: Boolean) {
        var heldDevice = -1
        var suppressed = false
        var pressed = false
        var pressedAt = 0L
        var pendingSingle = false
        var holding = false
        var awaitingDouble = false
        var doubleFiring = false
        var startHeldRun: Runnable? = null
        var startHoldRun: Runnable? = null
        var singleTapRun: Runnable? = null
        var repeatRun: Runnable? = null
    }
    private var entries = emptyList<Entry>()
    private val released = ArrayList<Entry>()
    private val devices = HashMap<Int, String>()
    private val handler = Handler(Looper.getMainLooper())

    private fun auxChannel(action: InputAction) = if (action is InputAction.Disabled || action is InputAction.Native) null
        else router.channel(InputRole.PHYSICAL, InputControl.A, action)

    fun configure(bindings: List<PhysicalInputBinding>) {
        if (entries.map { it.binding } == bindings) return
        for (entry in entries) {
            clearTimers(entry)
            entry.channel.close(); entry.hold?.close(); entry.double?.close()
            if (entry.heldDevice != -1) released.add(entry)
        }
        entries = bindings.filter { it.part == null }.map { binding ->
            val hold = bindings.firstOrNull { it.key == binding.key && it.part == ElementSource.HOLD }
            val double = bindings.firstOrNull { it.key == binding.key && it.part == ElementSource.DOUBLE }
            Entry(binding, router.channel(InputRole.PHYSICAL, InputControl.A, binding.action),
                auxChannel(hold?.action ?: InputAction.Disabled), auxChannel(double?.action ?: InputAction.Disabled),
                binding.repeat)
        }
    }

    private fun clearTimers(entry: Entry) {
        entry.awaitingDouble = false
        entry.pendingSingle = false
        entry.holding = false
        entry.doubleFiring = false
        entry.startHeldRun?.let { handler.removeCallbacks(it) }
        entry.startHoldRun?.let { handler.removeCallbacks(it) }
        entry.singleTapRun?.let { handler.removeCallbacks(it) }
        entry.repeatRun?.let { handler.removeCallbacks(it) }
        entry.startHeldRun = null; entry.startHoldRun = null; entry.singleTapRun = null; entry.repeatRun = null
    }

    private fun startHold(entry: Entry) {
        entry.startHoldRun = null
        if (!entry.pressed || entry.holding) return
        entry.holding = true
        entry.pendingSingle = false
        entry.channel.update(0f)
        if (entry.hold != null) entry.hold.update(1f)
        if (entry.repeat && entry.repeatRun == null) {
            val tick = object : Runnable {
                override fun run() {
                    if (!entry.pressed || !entry.holding && entry.hold != null) { entry.repeatRun = null; return }
                    if (entry.hold != null) entry.hold.update(1f) else entry.channel.update(1f)
                    handler.postDelayed({
                        if (entry.pressed && entry.repeatRun != null) {
                            if (entry.hold != null) entry.hold.update(0f) else entry.channel.update(0f)
                        }
                    }, ScreenKeyTiming.repeatMs / 2)
                    handler.postDelayed(this, ScreenKeyTiming.repeatMs)
                }
            }
            entry.repeatRun = tick
            handler.postDelayed(tick, ScreenKeyTiming.repeatMs)
        }
    }

    private fun startHeld(entry: Entry) {
        entry.startHeldRun = null
        if (!entry.pressed || !entry.pendingSingle) return
        entry.pendingSingle = false
        entry.channel.update(1f)
    }

    private fun singleTap(entry: Entry) {
        entry.singleTapRun = null
        entry.awaitingDouble = false
        if (!entry.pressed && entry.heldDevice == -1) { entry.channel.update(1f); entry.channel.update(0f) }
    }

    private fun press(entry: Entry) {
        if (entry.pressed) return
        entry.pressed = true
        entry.pressedAt = SystemClock.uptimeMillis()
        if (entry.awaitingDouble) {
            entry.awaitingDouble = false
            entry.doubleFiring = true
            entry.double?.update(1f)
            return
        }
        if (entry.double != null) {
            entry.pendingSingle = true
            if (entry.hold == null && !entry.repeat) {
                val run = Runnable { startHeld(entry) }
                entry.startHeldRun = run
                handler.postDelayed(run, ScreenKeyTiming.doubleMs)
            }
        } else entry.channel.update(1f)
        if (entry.hold != null || entry.repeat) {
            val run = Runnable { startHold(entry) }
            entry.startHoldRun = run
            handler.postDelayed(run, ScreenKeyTiming.holdMs)
        }
    }

    private fun releasePress(entry: Entry) {
        if (!entry.pressed) return
        entry.pressed = false
        val wasDoubleFiring = entry.doubleFiring
        val wasHolding = entry.holding
        val hadPendingSingle = entry.pendingSingle
        clearTimers(entry)
        if (wasDoubleFiring) {
            entry.double?.update(0f)
            return
        }
        if (wasHolding) {
            entry.hold?.update(0f)
            entry.channel.update(0f)
            return
        }
        if (hadPendingSingle) {
            entry.pendingSingle = false
            if (entry.double != null && SystemClock.uptimeMillis() - entry.pressedAt < ScreenKeyTiming.holdMs) {
                entry.awaitingDouble = true
                val run = Runnable { singleTap(entry) }
                entry.singleTapRun = run
                handler.postDelayed(run, ScreenKeyTiming.doubleMs)
            } else { entry.channel.update(1f); entry.channel.update(0f) }
            return
        }
        entry.channel.update(0f)
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
                    releasePress(entry)
                    entry.heldDevice = -1; entry.suppressed = false
                }
                return true
            }
            if (!enabled || event.action != KeyEvent.ACTION_DOWN || entry.binding.action is InputAction.Native) continue
            val identity = devices[event.deviceId] ?: event.device?.let { PhysicalInputKey.identity(it) }?.also { devices[event.deviceId] = it } ?: continue
            if (identity != key.device) continue
            entry.heldDevice = event.deviceId
            entry.suppressed = event.repeatCount != 0
            if (!entry.suppressed) press(entry)
            return true
        }
        return false
    }

    fun release() {
        for (entry in entries) {
            releasePress(entry)
            entry.channel.release(); entry.hold?.release(); entry.double?.release()
            if (entry.heldDevice != -1) entry.suppressed = true
        }
    }

    fun disconnected(id: Int) {
        devices.remove(id)
        released.removeAll { it.heldDevice == id }
        for (entry in entries) if (entry.heldDevice == id) {
            releasePress(entry)
            entry.channel.release(); entry.hold?.release(); entry.double?.release()
            entry.heldDevice = -1
        }
    }

    fun close() {
        for (entry in entries) {
            clearTimers(entry)
            entry.channel.close(); entry.hold?.close(); entry.double?.close()
        }
        entries = emptyList()
        released.clear()
        devices.clear()
    }
}
