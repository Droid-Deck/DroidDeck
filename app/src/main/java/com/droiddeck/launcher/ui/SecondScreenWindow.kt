package com.droiddeck.launcher.ui

import android.app.Activity
import android.app.ActivityOptions
import android.content.Intent
import android.os.Bundle
import android.view.Display
import android.view.View
import android.view.WindowManager
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.input.PadBridge
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionState
import java.lang.ref.WeakReference
import java.util.concurrent.atomic.AtomicLong

class SecondScreenWindow(
    private val sessionActivity: SessionActivity,
    val display: Display,
    private val sendPointer: (Float, Float, Float, Float) -> Unit,
    private val sendButton: (Int, Boolean) -> Unit,
    private val sendWheel: (Int) -> Unit,
    private val onSteamMenu: () -> Unit,
    private val onQam: () -> Unit,
    private val onClose: () -> Unit,
    private val pad: PadBridge? = null,
) {
    private val token = tokens.incrementAndGet()
    private var activity = WeakReference<SecondScreenActivity>(null)
    private var controls: SecondScreenControls? = null
    private var mode = SecondScreenMode.NONE
    private var launching = false
    private var closed = false
    val window get() = activity.get()?.window
    val isShowing get() = !closed && (launching || activity.get()?.isFinishing == false)

    fun show() {
        if (isShowing || closed) return
        owners[token] = WeakReference(this)
        launching = true
        try {
            val intent = Intent(sessionActivity, SecondScreenActivity::class.java)
                .putExtra(OWNER, token)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_MULTIPLE_TASK)
            sessionActivity.startActivity(intent, ActivityOptions.makeBasic().setLaunchDisplayId(display.displayId).toBundle())
        } catch (error: Exception) {
            launching = false
            owners.remove(token)
            throw error
        }
    }

    fun showMode(mode: SecondScreenMode) {
        this.mode = mode
        controls?.showMode(mode)
    }

    fun closeControls() {
        closed = true
        launching = false
        owners.remove(token)
        controls?.closeControls()
        controls = null
        activity.get()?.finishAndRemoveTask()
        activity.clear()
    }

    internal fun attach(host: SecondScreenActivity): View {
        controls?.closeControls()
        activity = WeakReference(host)
        launching = false
        return SecondScreenControls(host, sessionActivity, sendPointer, sendButton, sendWheel,
            onSteamMenu, onQam, onClose, pad).also {
            controls = it
            it.showMode(mode)
        }
    }

    internal fun detach(host: SecondScreenActivity) {
        if (activity.get() !== host) return
        controls?.closeControls()
        controls = null
        activity.clear()
        if (host.isFinishing && !closed) onClose()
    }

    internal fun refreshClipboard() = sessionActivity.refreshClipboard()

    companion object {
        private const val OWNER = "secondScreenOwner"
        private val tokens = AtomicLong()
        private val owners = mutableMapOf<Long, WeakReference<SecondScreenWindow>>()
        internal fun find(intent: Intent): SecondScreenWindow? =
            owners[intent.getLongExtra(OWNER, 0)]?.get()?.takeUnless { it.closed }
    }
}

class SecondScreenActivity : Activity() {
    private var owner: SecondScreenWindow? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val host = SecondScreenWindow.find(intent)
        if (host == null || !SessionState.running) {
            finishAndRemoveTask()
            return
        }
        owner = host
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        setContentView(host.attach(this))
        applyImmersiveMode()
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) {
            applyImmersiveMode()
            owner?.refreshClipboard()
        }
    }

    @Deprecated("Back is handled by the session controls")
    override fun onBackPressed() = Unit

    override fun onDestroy() {
        owner?.detach(this)
        owner = null
        super.onDestroy()
    }

    private fun applyImmersiveMode() {
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_LAYOUT_STABLE or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
            or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION)
    }
}
