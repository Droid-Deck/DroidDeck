package com.droiddeck.launcher.wayland

import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.view.Choreographer
import android.view.Surface
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState

object CompositorHost {
    @Volatile
    private var started = false

    @Volatile
    var displayChoice: String? = null

    @Volatile
    private var vsyncRunning = false

    @Volatile
    private var ownVsyncThread = true

    private val vsyncThread: HandlerThread by lazy {
        HandlerThread("droiddeck-vsync", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }
    private val vsyncHandler: Handler by lazy { Handler(vsyncThread.looper) }

    @Volatile
    private var vsyncGeneration = 0

    private fun vsyncCallback(generation: Int) = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (generation != vsyncGeneration) return
            WaylandCompositor.nativeVsync(frameTimeNanos)
            if (vsyncRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @androidx.annotation.RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun vsyncTimelineCallback(generation: Int) = object : Choreographer.VsyncCallback {
        override fun onVsync(data: Choreographer.FrameData) {
            if (generation != vsyncGeneration) return
            val lines = data.frameTimelines
            val preferred = data.preferredFrameTimeline
            val period = if (lines.size >= 2) lines[1].expectedPresentationTimeNanos - lines[0].expectedPresentationTimeNanos else 0L
            WaylandCompositor.nativeVsyncTimeline(
                data.frameTimeNanos, preferred.expectedPresentationTimeNanos, preferred.deadlineNanos, period,
            )
            if (vsyncRunning) Choreographer.getInstance().postVsyncCallback(this)
        }
    }

    private fun postFirstVsync(generation: Int) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Choreographer.getInstance().postVsyncCallback(vsyncTimelineCallback(generation))
        } else {
            Choreographer.getInstance().postFrameCallback(vsyncCallback(generation))
        }
    }

    @Synchronized
    fun startOrAttach(
        context: android.content.Context,
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
        WaylandCompositor.nativeSetClockControl(java.io.File(context.filesDir, "${com.droiddeck.launcher.runtime.LinuxRuntime.DIR}/tmp/.bl-clock").path)
        ownVsyncThread = SessionPrefs.vsyncThread(context)
        WaylandCompositor.nativeSetRenderOnCommit(SessionPrefs.renderOnCommit(context))
        WaylandCompositor.nativeSetPresentCadence(SessionPrefs.presentCadence(context))
        blackFrameInsertion = SessionPrefs.blackFrameInsertion(context)
        WaylandCompositor.nativeSetBlackFrameInsertion(blackFrameInsertion)
        pace(refreshHz, fpsLimit)
        if (!SessionState.running) WaylandCompositor.nativeSetOutputSize(outputWidth, outputHeight)
        if (started) {
            attached = surface
            WaylandCompositor.nativeSetSurface(surface)
            resumeVsync()
            return false
        }
        com.droiddeck.launcher.gpu.TpUbwcHint.applyToProcess(context)
        WaylandCompositor.nativeStartWithSurface(
            surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir,
            SessionPrefs.androidDriver(context).isEmpty(),
        )
        attached = surface
        started = true
        resumeVsync()
        return true
    }

    private var blackFrameInsertion = false
    private var pictureHolds = 0

    private fun pace(refreshHz: Float, fpsLimit: Int) {
        WaylandCompositor.nativeSetFpsLimit(fpsLimit)
        WaylandCompositor.nativeSetLayerFrameRate(if (fpsLimit > 0 && !blackFrameInsertion) fpsLimit.toFloat() else refreshHz)
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
        SessionState.refreshHz = refreshHz
    }

    @Synchronized
    fun updatePacing(refreshHz: Float, fpsLimit: Int) {
        if (started) pace(refreshHz, fpsLimit)
    }

    fun setPresentCadence(on: Boolean) {
        WaylandCompositor.nativeSetPresentCadence(on)
    }

    /** Pacing follows the phone screen's refreshes, so it waits while the game is shown on glasses or another screen. */
    fun setGameOnOtherDisplay(on: Boolean) {
        WaylandCompositor.nativeSetGameOnOtherDisplay(on)
    }

    @Synchronized
    fun setBlackFrameInsertion(on: Boolean, refreshHz: Float, fpsLimit: Int) {
        blackFrameInsertion = on
        WaylandCompositor.nativeSetBlackFrameInsertion(on)
        if (started) pace(refreshHz, fpsLimit)
    }

    /** Presents only pictures until the matching release; false when nothing is strobing. */
    @Synchronized
    fun holdPicture(): Boolean {
        if (!started || !blackFrameInsertion) return false
        if (pictureHolds++ == 0) WaylandCompositor.nativeSetPictureHold(true)
        return true
    }

    @Synchronized
    fun releasePicture() {
        if (pictureHolds == 0) return
        if (--pictureHolds == 0) WaylandCompositor.nativeSetPictureHold(false)
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
        val generation = ++vsyncGeneration
        vsyncRunning = true
        WaylandCompositor.nativeLog("screen", if (ownVsyncThread) "screen tick runs on its own display-priority thread" else "screen tick runs on the main thread")
        if (!ownVsyncThread) {
            postFirstVsync(generation)
            return
        }
        vsyncHandler.post { postFirstVsync(generation) }
    }

    private fun pauseVsync() {
        vsyncRunning = false
    }

    @Synchronized
    fun newSession() {
        WaylandCompositor.nativeSetClockControl(null)
        if (started) WaylandCompositor.nativeResetFirstFrame()
    }

    val isStarted: Boolean get() = started
}
