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

open class QuestXrActivity : NativeActivity() {
    protected open val keepDesktopPanel = false
    private var steamVrStarted = false
    @Volatile private var closing = false
    @Volatile private var steamVrStatus = SteamVr.Status.UNAVAILABLE
    private var sessionId: String? = null
    private var xrOwner = 0L
    private var runtimeAttempt = 0L
    override fun onCreate(savedInstanceState: Bundle?) {
        sessionId = SessionState.sessionId
        xrOwner = SessionService.registerXrActivity()
        runtimeAttempt = com.droiddeck.launcher.session.OpenXrRuntime.sessionStarting()
        reportVisible(true)
        super.onCreate(savedInstanceState)
    }

    open fun onXrActive(active: Boolean) {
        if (active) {
            com.droiddeck.launcher.session.OpenXrRuntime.sessionReady(runtimeAttempt)
            check(CompositorHost.isStarted) { "DroidDeck compositor is not running" }
            if (!keepDesktopPanel) {
                SessionService.resume(this)
                WaylandCompositor.nativeSetCardboard(false, 1, 0.064f, 3f, 4f, 0f, 0f, 90f, 0f, 0f, false, 0f)
                WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
                WaylandCompositor.nativeSetZeroCopy(false)
            }
            CompositorHost.attachXr(keepDesktopPanel)
            if (!keepDesktopPanel) {
                WaylandCompositor.nativeVsync(System.nanoTime())
                check(WaylandCompositor.nativeWaitSurface(3000)) { "Desktop surface handoff timed out" }
            }
        }
        reportVisible(active)
        if (active) startSteamVr()
    }

    protected open fun startSteamVr() {
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
                        if (steamVrOwner === this) {
                            if (sessionAlive()) SteamVr.run(this, "stop")
                            steamVrOwner = null
                        }
                    }
                }
            }, "xr-steamvr-start").start()
        }
    }

    private fun sessionAlive() = SessionState.running && !SessionState.stopRequested &&
        sessionId != null && SessionState.sessionId == sessionId

    private fun leaveXr(reason: String, failed: Boolean) {
        Log.w("DroidDeckXR", reason)
        if (failed) com.droiddeck.launcher.session.OpenXrRuntime.sessionFailed(runtimeAttempt, reason)
        closing = true
        runOnUiThread {
            if (failed) Toast.makeText(this, R.string.cardboard_steamvr_failed, Toast.LENGTH_LONG).show()
            finish()
        }
    }

    fun onXrFrame(frameTimeNanos: Long) {
        CompositorHost.xrVsync(frameTimeNanos, keepDesktopPanel)
    }

    open fun onXrStatus(): Int = steamVrStatus.ordinal


    fun onXrFailure(error: String) =
        com.droiddeck.launcher.session.OpenXrRuntime.sessionFailed(runtimeAttempt, error)

    private fun reportVisible(visible: Boolean) {
        SessionService.setXrActivityVisible(this, visible, sessionId, xrOwner)
        Log.i("DroidDeckXR", "activity=${javaClass.simpleName} task=$taskId owner=$xrOwner visible=$visible")
    }

    override fun onDestroy() {
        closing = true
        com.droiddeck.launcher.session.OpenXrRuntime.sessionEnded(runtimeAttempt)
        reportVisible(false)
        super.onDestroy()
    }

    override fun onStop() {
        reportVisible(false)
        super.onStop()
    }

    companion object {
        private val steamVrLock = Any()
        private var steamVrOwner: QuestXrActivity? = null
    }
}
