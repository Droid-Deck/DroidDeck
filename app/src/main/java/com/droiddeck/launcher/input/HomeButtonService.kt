package com.droiddeck.launcher.input

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Handler
import android.os.Looper
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent
import com.droiddeck.launcher.HomeApp

object HomeButtonShortcuts {
    private var owner: Any? = null
    private var enabled: () -> Boolean = { false }
    private var setPressed: (Boolean) -> Unit = {}
    private var onCancel: () -> Unit = {}
    private val input = HomeGuideInput({ setPressed(it) }, { onCancel() })

    fun attach(owner: Any, enabled: () -> Boolean, setPressed: (Boolean) -> Unit, onCancel: () -> Unit) {
        input.cancel()
        this.owner = owner
        this.enabled = enabled
        this.setPressed = setPressed
        this.onCancel = onCancel
    }

    fun cancel(owner: Any) { if (this.owner === owner) input.cancel() }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        input.cancel()
        this.owner = null
        enabled = { false }
        setPressed = {}
        onCancel = {}
    }

    fun onKeyEvent(context: Context, event: KeyEvent): Boolean {
        if (!HomeGuideInput.isHome(event.keyCode) || !PadBridge.isControllerKeyEvent(event)) return false
        return input.onKeyEvent(event, enabled() && HomeApp.isDefault(context))
    }

    fun release() = input.cancel()
    fun onDeviceRemoved(device: Int) = input.onDeviceRemoved(device)
}

object SystemKeyCapture {
    private var owner: Any? = null
    private var active: () -> Boolean = { false }
    private var send: (KeyEvent) -> Unit = {}
    private val held = HashMap<Pair<Int, Int>, KeyEvent>()
    private val releasing = HashSet<Pair<Int, Int>>()
    internal var isKeyboard: (KeyEvent) -> Boolean = ::fromKeyboard

    fun attach(owner: Any, active: () -> Boolean, send: (KeyEvent) -> Unit) {
        this.owner = owner
        this.active = active
        this.send = send
    }

    fun detach(owner: Any) {
        if (this.owner !== owner) return
        held.values.forEach { send(KeyEvent.changeAction(it, KeyEvent.ACTION_UP)) }
        releasing += held.keys
        held.clear()
        this.owner = null
        active = { false }
        send = {}
    }

    internal fun captures(keyCode: Int, metaState: Int): Boolean = when {
        keyCode == KeyEvent.KEYCODE_META_LEFT || keyCode == KeyEvent.KEYCODE_META_RIGHT -> true
        metaState and KeyEvent.META_META_ON != 0 -> true
        metaState and KeyEvent.META_ALT_ON != 0 -> keyCode == KeyEvent.KEYCODE_TAB || keyCode == KeyEvent.KEYCODE_ESCAPE
        else -> false
    }

    private fun fromKeyboard(event: KeyEvent): Boolean =
        event.device?.let { !it.isVirtual && it.keyboardType == android.view.InputDevice.KEYBOARD_TYPE_ALPHABETIC } == true

    fun onKeyEvent(event: KeyEvent): Boolean {
        val key = event.deviceId to event.keyCode
        if (event.action == KeyEvent.ACTION_DOWN && event.repeatCount == 0) {
            releasing -= key
        } else if (key in releasing) {
            if (event.action == KeyEvent.ACTION_UP) releasing -= key
            return true
        }
        val taken = when (event.action) {
            KeyEvent.ACTION_DOWN -> {
                val take = key in held || (active() && isKeyboard(event) && captures(event.keyCode, event.metaState))
                if (take) held[key] = KeyEvent(event)
                take
            }
            KeyEvent.ACTION_UP -> held.remove(key) != null
            else -> false
        }
        if (taken) send(event)
        return taken
    }
}

class HomeButtonService : AccessibilityService(), InputManager.InputDeviceListener {
    private var bottomScreen: BottomScreenWatch? = null

    override fun onServiceConnected() {
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
        if (bottomScreen == null && BottomScreenGuests.supported()) bottomScreen = BottomScreenWatch(this).also { it.start() }
    }

    private fun stopBottomScreen() {
        bottomScreen?.stop()
        bottomScreen = null
    }

    override fun onKeyEvent(event: KeyEvent) =
        SystemKeyCapture.onKeyEvent(event) || HomeButtonShortcuts.onKeyEvent(this, event)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) { event?.let { bottomScreen?.onEvent(it) } }
    override fun onInterrupt() { HomeButtonShortcuts.release() }
    override fun onInputDeviceAdded(deviceId: Int) {}
    override fun onInputDeviceChanged(deviceId: Int) {}
    override fun onInputDeviceRemoved(deviceId: Int) { HomeButtonShortcuts.onDeviceRemoved(deviceId) }

    override fun onUnbind(intent: Intent?): Boolean {
        HomeButtonShortcuts.release()
        stopBottomScreen()
        com.droiddeck.launcher.core.SteamIntegration.syncShortcutsListing(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        HomeButtonShortcuts.release()
        stopBottomScreen()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }
}
