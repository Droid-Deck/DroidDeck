package com.droiddeck.launcher.session

import android.content.Context
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.storage.StorageState
import com.droiddeck.launcher.wayland.CompositorHost
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

object BackgroundSession {
    private const val TAG = "BackgroundSession"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val REFRESH_HZ = 60f
    private const val AGENT_MS = 120_000L
    private const val STOP_MS = 30_000L
    private val ended = setOf(SessionPhase.IDLE, SessionPhase.FAILED)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var reader: ImageReader? = null

    fun start(context: Context, program: String, args: List<String>): Boolean {
        val app = context.applicationContext
        val began = CountDownLatch(1)
        var ok = false
        main.post {
            ok = runCatching { begin(app, program, args) }
                .onFailure { Log.w(TAG, "could not start a hidden session for $program", it) }
                .getOrDefault(false)
            began.countDown()
        }
        if (!began.await(30, TimeUnit.SECONDS) || !ok) return false
        val deadline = System.currentTimeMillis() + AGENT_MS
        val settle = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (AgentGuest.hello(app) != null) return true
            if (SessionState.phase == SessionPhase.FAILED) break
            if (!SessionState.running && SessionState.phase in ended && System.currentTimeMillis() > settle) break
            Thread.sleep(250)
        }
        stop(app)
        return false
    }

    fun stop(context: Context) {
        val app = context.applicationContext
        main.post { SessionService.stop(app) }
        val deadline = System.currentTimeMillis() + STOP_MS
        while ((SessionState.running || SessionState.phase !in ended) && System.currentTimeMillis() < deadline) Thread.sleep(250)
        main.post { release() }
    }

    private fun begin(context: Context, program: String, args: List<String>): Boolean {
        if (SessionState.running || SessionState.phase !in ended || LinuxRuntimeInstaller.isBusy() ||
            SessionState.installing != null || StorageState.cleaning) return false
        release()
        SessionState.stopRequested = false
        SessionEvents.transition(SessionPhase.STARTING_COMPOSITOR, "compositor.starting")
        val output = CompositorHost.prepare(context)
        val size = WIDTH to HEIGHT
        SessionState.outputSize = size
        SessionState.refreshHz = REFRESH_HZ
        CompositorHost.logToSession(context)
        CompositorHost.applyHdr(context, SessionPrefs.prefMode(SessionService.MODE_RUN))
        val offscreen = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
        offscreen.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, main)
        reader = offscreen
        CompositorHost.start(context, output, offscreen.surface, size, REFRESH_HZ, 0)
        CompositorHost.detach(offscreen.surface)
        SessionEvents.record("compositor.started")
        SessionEvents.transition(SessionPhase.STARTING_GUEST, "guest.starting")
        SessionService.start(context, SessionService.MODE_RUN, program, programArgs = args.toTypedArray())
        return true
    }

    private fun release() {
        if (SessionState.running) return
        reader?.close()
        reader = null
    }
}
