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

    private fun postVsync(generation: Int) {
        val choreographer = Choreographer.getInstance()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            choreographer.postVsyncCallback { data ->
                if (generation != vsyncGeneration) return@postVsyncCallback
                val lines = data.frameTimelines
                val line = data.preferredFrameTimeline
                val period = if (lines.size > 1) {
                    lines[1].expectedPresentationTimeNanos - lines[0].expectedPresentationTimeNanos
                } else {
                    0L
                }
                WaylandCompositor.nativeFrameTimeline(line.expectedPresentationTimeNanos, line.deadlineNanos, period)
                WaylandCompositor.nativeVsync(data.frameTimeNanos)
                if (vsyncRunning) postVsync(generation)
            }
            return
        }
        choreographer.postFrameCallback { frameTimeNanos ->
            if (generation != vsyncGeneration) return@postFrameCallback
            WaylandCompositor.nativeVsync(frameTimeNanos)
            if (vsyncRunning) postVsync(generation)
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
        WaylandCompositor.nativeSetClockControl(java.io.File(context.filesDir, "${com.droiddeck.launcher.runtime.LinuxRuntime.DIR}/tmp/.dd-clock").path)
        ownVsyncThread = SessionPrefs.vsyncThread(context)
        WaylandCompositor.nativeSetRenderOnCommit(SessionPrefs.renderOnCommit(context))
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
        if (!started || attached !== surface) return
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
    private val windowBindings = mutableMapOf<String, Long>()

    @Synchronized
    fun attachWindow(key: String, generation: Long, surface: Surface, width: Int, height: Int) {
        if (!started || generation < (windowBindings[key] ?: 0)) return
        windowBindings[key] = generation
        WaylandCompositor.nativeAttachWindow(key, generation, surface, width, height)
        resumeVsync()
    }

    @Synchronized
    fun detachWindow(key: String, generation: Long) {
        if (windowBindings[key] != generation) return
        windowBindings.remove(key)
        WaylandCompositor.nativeDetachWindow(key, generation)
        if (attached == null && windowBindings.isEmpty()) pauseVsync()
    }

    @Synchronized
    fun attachXr(keepPanel: Boolean = false) {
        check(started) { "DroidDeck compositor is not running" }
        if (keepPanel) {
            WaylandCompositor.nativeLog("screen", "OpenXR active: retaining the desktop surface and screen tick")
            return
        }
        pauseVsync()
        attached = null
        WaylandCompositor.nativeSetSurface(null)
    }

    @Synchronized
    fun detach(surface: Surface?) {
        if (!started) return
        if (surface != null && attached !== surface) return
        attached = null
        if (windowBindings.isEmpty()) pauseVsync()
        WaylandCompositor.nativeSetSurface(null)
    }

    private fun resumeVsync() {
        if (vsyncRunning) return
        val generation = ++vsyncGeneration
        vsyncRunning = true
        WaylandCompositor.nativeLog("screen", if (ownVsyncThread) "screen tick runs on its own display-priority thread" else "screen tick runs on the main thread")
        if (!ownVsyncThread) {
            postVsync(generation)
            return
        }
        vsyncHandler.post { postVsync(generation) }
    }

    private fun pauseVsync() {
        vsyncRunning = false
        ++vsyncGeneration
    }

    fun xrVsync(frameTimeNanos: Long, keepPanel: Boolean) {
        if (!keepPanel || !vsyncRunning) WaylandCompositor.nativeVsync(frameTimeNanos)
    }

    @Synchronized
    fun newSession() {
        WaylandCompositor.nativeSetClockControl(null)
        if (started) WaylandCompositor.nativeResetFirstFrame()
    }

    @Synchronized
    fun diagnostics(): org.json.JSONObject = org.json.JSONObject()
        .put("surfaceAttached", attached?.isValid == true)
        .put("desktopVsync", vsyncRunning)
        .put("cardboardActive", started && WaylandCompositor.nativeCardboardActive())
        .put("presentedFrames", if (started) WaylandCompositor.nativeHudFrameTimes(FloatArray(1)) else 0L)

    val isStarted: Boolean get() = started
}
