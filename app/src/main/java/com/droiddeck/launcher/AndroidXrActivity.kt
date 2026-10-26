package com.droiddeck.launcher

import android.app.NativeActivity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import android.view.KeyEvent
import com.droiddeck.launcher.input.XrGamepadInput
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import com.droiddeck.launcher.xr.SteamVrHandVisuals
import com.droiddeck.launcher.session.SessionPrefs
import java.io.File

class AndroidXrActivity : NativeActivity() {
    private lateinit var gamepad: XrGamepadInput
    private var steamVrStarted = false
    @Volatile private var closing = false
    @Volatile private var xrVisible = false
    @Volatile private var steamVrStatus = SteamVr.Status.UNAVAILABLE
    private var sessionId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        intent.putExtra("xr_input", "hands")
        gamepad = XrGamepadInput(this) { SessionState.padBridge }
        sessionId = SessionState.sessionId
        publishVisibility(true)
        super.onCreate(savedInstanceState)
    }

    override fun onResume() {
        super.onResume()
        gamepad.resume()
    }

    override fun onPause() {
        gamepad.pause()
        super.onPause()
    }

    fun onXrInputFocus(focused: Boolean) = gamepad.focus(focused)
    fun onXrGamepadMenu(): Int = gamepad.menuSequence

    fun onXrGamepadKey(device: Int, source: Int, action: Int, code: Int, scan: Int,
                       repeat: Int, meta: Int, flags: Int, downTime: Long, eventTime: Long): Boolean =
        gamepad.key(KeyEvent(downTime, eventTime, action, code, repeat, meta, device, scan, flags, source))

    fun onXrGamepadMotion(device: Int, source: Int, action: Int, downTime: Long,
                          eventTime: Long, axes: FloatArray): Boolean =
        gamepad.nativeMotion(device, source, action, downTime, eventTime, axes)

    fun onXrActive(active: Boolean) {
        xrVisible = active
        if (active) {
            check(CompositorHost.isStarted) { "DroidDeck compositor is not running" }
            SessionService.resume(this)
            WaylandCompositor.nativeSetCardboard(false, 1, 0.064f, 3f, 4f, 0f, 0f, 90f, 0f, 0f, false, 0f)
            WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
            WaylandCompositor.nativeSetZeroCopy(false)
            CompositorHost.attachXr()
            WaylandCompositor.nativeVsync(System.nanoTime())
            check(WaylandCompositor.nativeWaitSurface(3000)) { "XR frame GPU handshake timed out" }
        }
        publishVisibility(active)
        if (active) startSteamVr()
    }

    private fun startSteamVr() {
        if (!steamVrStarted) {
            steamVrStarted = true
            Thread({
                try {
                    val started = synchronized(steamVrLock) {
                        if (closing || !sessionAlive()) false else {
                            steamVrOwner = this
                            SteamVr.run(this, "start")
                        }
                    }
                    if (!started) {
                        if (!closing) leaveXr("SteamVR start failed", sessionAlive())
                        return@Thread
                    }
                    val startupDeadline = SystemClock.elapsedRealtime() + 30_000
                    var lastRunning: Long? = null
                    while (!closing) {
                        if (!sessionAlive()) {
                            leaveXr("Steam session ended", false)
                            break
                        }
                        if (!xrVisible) { lastRunning = SystemClock.elapsedRealtime(); Thread.sleep(500); continue }
                        steamVrStatus = SteamVr.status(this)
                        val now = SystemClock.elapsedRealtime()
                        if (steamVrStatus == SteamVr.Status.RUNNING) lastRunning = now
                        val deadline = lastRunning?.plus(5_000) ?: startupDeadline
                        if (steamVrStatus != SteamVr.Status.RUNNING && now >= deadline) {
                            leaveXr("SteamVR stopped or did not finish starting", true)
                            break
                        }
                        Thread.sleep(1000)
                    }
                } finally {
                    synchronized(steamVrLock) {
                        // A retiring activity must not stop a replacement's SteamVR session.
                        if (steamVrOwner === this) {
                            if (sessionAlive()) SteamVr.run(this, "stop")
                            steamVrOwner = null
                        }
                    }
                }
            }, "xr-steamvr-start").start()
        }
    }

    private fun publishVisibility(visible: Boolean) {
        xrVisibility.update(this, visible && !closing) { anyVisible ->
            SessionService.setXrActivityVisible(this, anyVisible)
        }
    }

    private fun sessionAlive() = SessionState.running && !SessionState.stopRequested &&
        sessionId != null && SessionState.sessionId == sessionId

    private fun leaveXr(reason: String, failed: Boolean) {
        Log.w("DroidDeckXR", reason)
        closing = true
        runOnUiThread {
            if (failed) Toast.makeText(this, getString(R.string.cardboard_steamvr_failed) + "\n" + reason, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    fun onXrError(reason: String) {
        leaveXr(reason, true)
    }

    fun onXrFrame(frameTimeNanos: Long) {
        WaylandCompositor.nativeVsync(frameTimeNanos)
    }

    fun onXrStatus(): Int = steamVrStatus.ordinal

    fun onXrHandModel(hand: Int): ByteArray? = runCatching {
        SteamVrHandVisuals.model(SessionPrefs.activeSteamRoot(this), hand != 0)
    }.onFailure { Log.w("DroidDeckXR", "SteamVR reference hand model unavailable", it) }.getOrNull()

    fun onXrShowHands(): Boolean = SteamVrHandVisuals.visible(
        File(filesDir, ".wayland-rt/droiddeck-steamvr/scene-focus.json"))

    override fun onDestroy() {
        closing = true
        gamepad.pause()
        publishVisibility(false)
        super.onDestroy()
    }

    override fun onStop() {
        publishVisibility(false)
        super.onStop()
    }

    companion object {
        private val xrVisibility = com.droiddeck.xr.XrActivityVisibility()
        private val steamVrLock = Any()
        private var steamVrOwner: AndroidXrActivity? = null
    }
}
