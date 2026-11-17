package com.droiddeck.launcher.input

import android.app.Service
import android.content.Context
import android.content.Intent
import android.hardware.input.InputManager
import android.os.Binder
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Parcel
import android.view.KeyEvent
import com.droiddeck.launcher.HomeApp
import com.droiddeck.shortcuts.Relay

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
                val take = key in held || (event.repeatCount == 0 && active() && isKeyboard(event) && captures(event.keyCode, event.metaState))
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

class ShortcutsRelay : Service(), InputManager.InputDeviceListener {
    private val main = Handler(Looper.getMainLooper())
    private val trusted = java.util.concurrent.ConcurrentHashMap.newKeySet<Int>()
    private var bottomScreen: BottomScreenWatch? = null

    private val binder = object : Binder() {
        override fun onTransact(code: Int, data: Parcel, reply: Parcel?, flags: Int): Boolean {
            if (code !in Relay.CONFIG..Relay.WINDOWS) return super.onTransact(code, data, reply, flags)
            data.enforceInterface(Relay.DESCRIPTOR)
            if (!trusts(Binder.getCallingUid())) return false
            when (code) {
                Relay.CONFIG -> reply?.writeInt(if (bottomScreen != null) Relay.WANTS_WINDOWS else 0)
                Relay.KEY -> reply?.writeInt(if (onMain(KeyEvent.CREATOR.createFromParcel(data))) 1 else 0)
                Relay.INTERRUPT -> main.post { HomeButtonShortcuts.release() }
                Relay.ARRIVAL -> bottomScreen?.onArrival(data.readInt(), data.readString() ?: return true, data.readLong())
                Relay.WINDOWS -> bottomScreen?.onWindows(Relay.decode(data.createByteArray() ?: return true))
            }
            return true
        }
    }

    private fun trusts(uid: Int): Boolean {
        if (uid !in trusted) {
            if (!packageManager.getPackagesForUid(uid).orEmpty().contains(Relay.PACKAGE)) return false
            trusted += uid
        }
        return com.droiddeck.launcher.core.SteamIntegration.shortcutsEnabled(this)
    }

    private fun onMain(event: KeyEvent): Boolean {
        // A key the companion already gave up on must not run later and leave a press without its release.
        val claimed = java.util.concurrent.atomic.AtomicBoolean(false)
        val task = java.util.concurrent.FutureTask {
            claimed.compareAndSet(false, true) &&
                (SystemKeyCapture.onKeyEvent(event) || HomeButtonShortcuts.onKeyEvent(this, event))
        }
        main.postAtFrontOfQueue(task)
        return try {
            task.get(KEY_TIMEOUT_MS, java.util.concurrent.TimeUnit.MILLISECONDS)
        } catch (e: java.util.concurrent.TimeoutException) {
            if (claimed.compareAndSet(false, true)) false else runCatching { task.get() }.getOrDefault(false)
        } catch (e: Exception) {
            false
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(InputManager::class.java).registerInputDeviceListener(this, main)
        if (BottomScreenGuests.supported()) bottomScreen = BottomScreenWatch(this).also { it.start() }
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onUnbind(intent: Intent?): Boolean {
        HomeButtonShortcuts.release()
        return super.onUnbind(intent)
    }

    override fun onInputDeviceAdded(deviceId: Int) {}
    override fun onInputDeviceChanged(deviceId: Int) {}
    override fun onInputDeviceRemoved(deviceId: Int) { HomeButtonShortcuts.onDeviceRemoved(deviceId) }

    override fun onDestroy() {
        HomeButtonShortcuts.release()
        bottomScreen?.stop()
        bottomScreen = null
        getSystemService(InputManager::class.java).unregisterInputDeviceListener(this)
        super.onDestroy()
    }

    private companion object {
        const val KEY_TIMEOUT_MS = 200L
    }

    // Frontends such as Cocoon treat a running player process as an open game, so the relay runs only while a screen is started.
    object Holder : android.app.Application.ActivityLifecycleCallbacks, android.content.ServiceConnection {
        private const val RELEASE_MS = 1_000L
        private val handler = Handler(Looper.getMainLooper())
        private var app: android.app.Application? = null
        private var started = 0
        private var bound = false
        private val release = Runnable { unbind() }

        fun install(application: android.app.Application) {
            app?.unregisterActivityLifecycleCallbacks(this)
            handler.removeCallbacks(release)
            started = 0
            bound = false
            app = application
            application.registerActivityLifecycleCallbacks(this)
        }

        override fun onActivityStarted(activity: android.app.Activity) {
            started++
            handler.removeCallbacks(release)
            if (!bound) bind()
        }

        override fun onActivityStopped(activity: android.app.Activity) {
            started = (started - 1).coerceAtLeast(0)
            if (started == 0) handler.postDelayed(release, RELEASE_MS)
        }

        private fun bind() {
            val context = app ?: return
            if (runCatching { context.packageManager.getPackageInfo(Relay.PACKAGE, 0) }.isFailure) return
            bound = runCatching {
                context.bindService(Intent(context, ShortcutsRelay::class.java), this, Context.BIND_AUTO_CREATE)
            }.getOrDefault(false)
        }

        private fun unbind() {
            if (!bound) return
            bound = false
            runCatching { app?.unbindService(this) }
        }

        override fun onServiceConnected(name: android.content.ComponentName?, service: IBinder?) = Unit
        override fun onServiceDisconnected(name: android.content.ComponentName?) = Unit
        override fun onActivityCreated(activity: android.app.Activity, savedInstanceState: android.os.Bundle?) = Unit
        override fun onActivityResumed(activity: android.app.Activity) = Unit
        override fun onActivityPaused(activity: android.app.Activity) = Unit
        override fun onActivitySaveInstanceState(activity: android.app.Activity, outState: android.os.Bundle) = Unit
        override fun onActivityDestroyed(activity: android.app.Activity) = Unit
    }
}
