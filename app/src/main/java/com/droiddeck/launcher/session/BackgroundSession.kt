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
import java.util.concurrent.atomic.AtomicBoolean

object BackgroundSession {
    private const val TAG = "BackgroundSession"
    private const val WIDTH = 1280
    private const val HEIGHT = 720
    private const val REFRESH_HZ = 60f
    private const val BEGIN_MS = 30_000L
    private const val AGENT_MS = 120_000L
    private const val STOP_MS = 30_000L
    private val ended = setOf(SessionPhase.IDLE, SessionPhase.FAILED)
    private val main by lazy { Handler(Looper.getMainLooper()) }
    private var reader: ImageReader? = null

    fun start(context: Context, program: String, args: List<String>): String? {
        val app = context.applicationContext
        val claim = AtomicBoolean(false)
        val began = CountDownLatch(1)
        var ok = false
        main.post {
            if (claim.compareAndSet(false, true)) {
                ok = runCatching { begin(app, program, args) }
                    .onFailure { Log.w(TAG, "could not start a hidden session for $program", it) }
                    .getOrDefault(false)
            }
            began.countDown()
        }
        if (!began.await(BEGIN_MS, TimeUnit.MILLISECONDS) && claim.compareAndSet(false, true)) return null
        began.await()
        if (!ok) return null
        val deadline = System.currentTimeMillis() + AGENT_MS
        val settle = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            val session = SessionState.sessionId
            if (SessionState.running && session != null && AgentGuest.hello(app) != null) return session
            if (SessionState.phase == SessionPhase.FAILED) break
            if (!SessionState.running && SessionState.phase in ended && System.currentTimeMillis() > settle) break
            Thread.sleep(250)
        }
        SessionState.sessionId?.let { stop(app, it) } ?: main.post { SessionService.stop(app) }
        return null
    }

    fun stop(context: Context, session: String) {
        val app = context.applicationContext
        if (SessionState.sessionId != session) return
        main.post { if (SessionState.sessionId == session) SessionService.stop(app) }
        val deadline = System.currentTimeMillis() + STOP_MS
        while (live(session) && System.currentTimeMillis() < deadline) Thread.sleep(250)
        if (!live(session)) {
            main.post { release() }
            return
        }
        Thread {
            while (live(session)) Thread.sleep(1_000)
            main.post { release() }
        }.start()
    }

    private fun live(session: String) =
        SessionState.sessionId == session && (SessionState.running || SessionState.phase !in ended)

    private fun begin(context: Context, program: String, args: List<String>): Boolean {
        if (SessionState.running || SessionState.phase !in ended || LinuxRuntimeInstaller.isBusy() ||
            SessionState.installing != null || StorageState.cleaning) return false
        release()
        SessionState.stopRequested = false
        SessionEvents.transition(SessionPhase.STARTING_COMPOSITOR, "compositor.starting")
        var requested = false
        try {
            val output = CompositorHost.prepare(context)
            val size = WIDTH to HEIGHT
            SessionState.outputSize = size
            SessionState.refreshHz = REFRESH_HZ
            CompositorHost.logToSession(context)
            CompositorHost.applyHdr(context, SessionPrefs.prefMode(SessionService.MODE_RUN))
            val offscreen = ImageReader.newInstance(WIDTH, HEIGHT, PixelFormat.RGBA_8888, 2)
            reader = offscreen
            offscreen.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, main)
            CompositorHost.start(context, output, offscreen.surface, size, REFRESH_HZ, 0)
            CompositorHost.detach(offscreen.surface)
            SessionEvents.record("compositor.started")
            SessionEvents.transition(SessionPhase.STARTING_GUEST, "guest.starting")
            requested = true
            SessionService.start(context, SessionService.MODE_RUN, program, programArgs = args.toTypedArray())
            return true
        } catch (e: Exception) {
            if (requested) SessionService.stop(context)
            if (!SessionState.running) {
                reader?.close()
                reader = null
                SessionEvents.transition(SessionPhase.FAILED, "hidden.failed", mapOf("error" to e.toString()))
            }
            throw e
        }
    }

    private fun release() {
        if (SessionState.running) return
        reader?.close()
        reader = null
    }
}
