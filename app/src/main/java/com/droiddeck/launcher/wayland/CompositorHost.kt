package com.droiddeck.launcher.wayland

import android.view.Choreographer
import android.view.Surface
import com.droiddeck.launcher.session.SessionState

object CompositorHost {
    @Volatile
    private var started = false

    @Volatile
    var displayChoice: String? = null

    @Volatile
    private var vsyncRunning = false

    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            WaylandCompositor.nativeVsync(frameTimeNanos)
            if (vsyncRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Synchronized
    fun startOrAttach(
        surface: Surface,
        xdgRuntimeDir: String,
        driverPath: String?,
        libraryName: String?,
        nativeLibDir: String,
        outputWidth: Int,
        outputHeight: Int,
        refreshHz: Float,
        fpsLimit: Int,
    ): Boolean {
        pace(refreshHz, fpsLimit)
        if (!SessionState.running) WaylandCompositor.nativeSetOutputSize(outputWidth, outputHeight)
        if (started) {
            attached = surface
            WaylandCompositor.nativeSetSurface(surface)
            resumeVsync()
            return false
        }
        com.droiddeck.launcher.gpu.TpUbwcHint.applyToProcess()
        WaylandCompositor.nativeStartWithSurface(
            surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir,
        )
        attached = surface
        started = true
        resumeVsync()
        return true
    }

    private fun pace(refreshHz: Float, fpsLimit: Int) {
        WaylandCompositor.nativeSetFpsLimit(fpsLimit)
        WaylandCompositor.nativeSetLayerFrameRate(if (fpsLimit > 0) fpsLimit.toFloat() else refreshHz)
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
        SessionState.refreshHz = refreshHz
    }

    @Synchronized
    fun updatePacing(refreshHz: Float, fpsLimit: Int) {
        if (started) pace(refreshHz, fpsLimit)
    }

    @Synchronized
    fun resize(surface: Surface, rearm: () -> Unit) {
        if (!started) return
        WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        WaylandCompositor.nativeLog("screen", "surface resized: rebinding the window, frame generation re-armed after")
        WaylandCompositor.nativeSetSurface(null)
        attached = surface
        WaylandCompositor.nativeSetSurface(surface)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(rearm, 600)
    }

    fun rearmFrameGen(rearm: () -> Unit) {
        if (!started) return
        WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        WaylandCompositor.nativeLog("screen", "presenting window changed: frame generation re-armed")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(rearm, 600)
    }

    private var attached: Surface? = null

    @Synchronized
    fun attachXr() {
        check(started) { "DroidDeck compositor is not running" }
        pauseVsync()
        attached = null
        WaylandCompositor.nativeSetSurface(null)
    }

    @Synchronized
    fun detach(surface: Surface?) {
        if (!started) return
        if (surface != null && attached != null && attached !== surface) return
        attached = null
        pauseVsync()
        WaylandCompositor.nativeSetSurface(null)
    }

    private fun resumeVsync() {
        if (vsyncRunning) return
        vsyncRunning = true
        Choreographer.getInstance().postFrameCallback(vsync)
    }

    private fun pauseVsync() {
        vsyncRunning = false
    }

    fun newSession() {
        if (started) WaylandCompositor.nativeResetFirstFrame()
    }

    val isStarted: Boolean get() = started
}
