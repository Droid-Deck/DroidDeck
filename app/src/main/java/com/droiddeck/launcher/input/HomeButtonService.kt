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
        // AYN maps its system-assistant key (gpio-keys KEY_F24) to HOME too; only the pad's Home is Guide.
        if (!HomeGuideInput.isHome(event.keyCode) || !PadBridge.isControllerKeyEvent(event)) return false
        return input.onKeyEvent(event, enabled() && HomeApp.isDefault(context))
    }

    fun release() = input.cancel()
    fun onDeviceRemoved(device: Int) = input.onDeviceRemoved(device)
}

class HomeButtonService : AccessibilityService(), InputManager.InputDeviceListener {
    override fun onServiceConnected() {
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, Handler(Looper.getMainLooper()))
    }

    override fun onKeyEvent(event: KeyEvent) = HomeButtonShortcuts.onKeyEvent(this, event)
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() { HomeButtonShortcuts.release() }
    override fun onInputDeviceAdded(deviceId: Int) {}
    override fun onInputDeviceChanged(deviceId: Int) {}
    override fun onInputDeviceRemoved(deviceId: Int) { HomeButtonShortcuts.onDeviceRemoved(deviceId) }

    override fun onUnbind(intent: Intent?): Boolean {
        HomeButtonShortcuts.release()
        com.droiddeck.launcher.core.SteamIntegration.syncShortcutsListing(this)
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        HomeButtonShortcuts.release()
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }
}
