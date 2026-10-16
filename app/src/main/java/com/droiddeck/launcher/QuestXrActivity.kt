package com.droiddeck.launcher

import android.app.NativeActivity
import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.Toast
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor

class QuestXrActivity : NativeActivity() {
    private var steamVrStarted = false
    @Volatile private var closing = false
    @Volatile private var steamVrStatus = SteamVr.Status.UNAVAILABLE
    private var sessionId: String? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        sessionId = SessionState.sessionId
        SessionService.setXrActivityVisible(this, true)
        super.onCreate(savedInstanceState)
    }

    fun onXrActive(active: Boolean) {
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
        SessionService.setXrActivityVisible(this, active)
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
            }, "quest-steamvr-start").start()
        }
    }

    private fun sessionAlive() = SessionState.running && !SessionState.stopRequested &&
        sessionId != null && SessionState.sessionId == sessionId

    private fun leaveXr(reason: String, failed: Boolean) {
        Log.w("DroidDeckXR", reason)
        closing = true
        runOnUiThread {
            if (failed) Toast.makeText(this, R.string.cardboard_steamvr_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    fun onXrFrame(frameTimeNanos: Long) {
        WaylandCompositor.nativeVsync(frameTimeNanos)
    }

    fun onXrStatus(): Int = steamVrStatus.ordinal

    override fun onDestroy() {
        closing = true
        SessionService.setXrActivityVisible(this, false)
        super.onDestroy()
    }

    override fun onStop() {
        SessionService.setXrActivityVisible(this, false)
        super.onStop()
    }

    companion object {
        private val steamVrLock = Any()
        private var steamVrOwner: QuestXrActivity? = null
    }
}
