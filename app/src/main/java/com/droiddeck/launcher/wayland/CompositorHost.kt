package com.droiddeck.launcher.wayland

import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.Process
import android.util.Log
import android.view.Choreographer
import android.view.Display
import android.view.Surface
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.session.SessionPaths
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionState
import java.io.File

object CompositorHost {
    @Volatile
    private var started = false

    @Volatile
    var displayChoice: String? = null

    @Volatile
    private var vsyncRunning = false

    @Volatile
    private var ownVsyncThread = true

    // The screen tick gets its own thread so a busy main thread cannot delay the compositor's frames.
    private val vsyncThread: HandlerThread by lazy {
        HandlerThread("droiddeck-vsync", Process.THREAD_PRIORITY_DISPLAY).apply { start() }
    }
    private val vsyncHandler: Handler by lazy { Handler(vsyncThread.looper) }

    @Volatile
    private var vsyncGeneration = 0

    // Each resume gets a new callback; one left queued from an earlier resume, on either thread, stops itself.
    private fun vsyncCallback(generation: Int) = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (generation != vsyncGeneration) return
            WaylandCompositor.nativeVsync(frameTimeNanos)
            if (vsyncRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    @Synchronized
    fun prepare(
        context: Context,
        outputWidth: Int,
        outputHeight: Int,
        refreshHz: Float,
        fpsLimit: Int,
        mode: String = SessionState.mode,
        display: Display? = null,
        checkCurrent: () -> Unit = {},
    ) {
        checkCurrent()
        startOrAttachOutput(context, null, outputWidth, outputHeight, refreshHz, fpsLimit) {
            checkCurrent()
            val runtimeDir = File(context.filesDir, ".wayland-rt").apply { mkdirs() }
            FileUtils.copyAsset(context, "wayland/keymap.xkb", File(runtimeDir, "keymap.xkb"))
            val choice = SessionPrefs.androidDriver(context)
            displayChoice = choice
            com.droiddeck.launcher.gpu.AutoDriverTrial.displayStarting(context, choice)
            val turnip = TurnipDriver(context)
            val driverId = turnip.install()
            checkCurrent()
            configureOutput(context, mode, display)
            newSession(context)
            com.droiddeck.launcher.gpu.TpUbwcHint.applyToProcess(context)
            com.droiddeck.launcher.gpu.DriverSetupLog.write(context, "compositor.start driver=$driverId")
            WaylandCompositor.nativeStartWithSurface(
                null, runtimeDir.path, driverId?.let { turnip.driverPath(it) },
                driverId?.let { turnip.libraryName(it) }, context.applicationInfo.nativeLibraryDir,
            )
        }
    }

    @Synchronized
    fun attach(
        context: Context,
        surface: Surface,
        outputWidth: Int,
        outputHeight: Int,
        refreshHz: Float,
        fpsLimit: Int,
        display: Display? = null,
    ) {
        check(started) { "DroidDeck compositor is not prepared" }
        updateDisplay(context, display)
        startOrAttachOutput(context, surface, outputWidth, outputHeight, refreshHz, fpsLimit) {}
    }

    fun startOrAttach(
        context: Context,
        surface: Surface,
        xdgRuntimeDir: String,
        driverPath: String?,
        libraryName: String?,
        nativeLibDir: String,
        outputWidth: Int,
        outputHeight: Int,
        refreshHz: Float,
        fpsLimit: Int,
    ): Boolean = startOrAttachOutput(context, surface, outputWidth, outputHeight, refreshHz, fpsLimit) {
        com.droiddeck.launcher.gpu.TpUbwcHint.applyToProcess(context)
        WaylandCompositor.nativeStartWithSurface(surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir)
    }

    @Synchronized
    private fun startOrAttachOutput(
        context: Context,
        surface: Surface?,
        outputWidth: Int,
        outputHeight: Int,
        refreshHz: Float,
        fpsLimit: Int,
        start: () -> Unit,
    ): Boolean {
        if (started && surface == null && attached != null) return false
        ownVsyncThread = SessionPrefs.vsyncThread(context)
        WaylandCompositor.nativeSetRenderOnCommit(SessionPrefs.renderOnCommit(context))
        pace(refreshHz, fpsLimit)
        if (!SessionState.running || !started || surface == null) WaylandCompositor.nativeSetOutputSize(outputWidth, outputHeight)
        if (started) {
            if (surface != null) {
                attached = surface
                WaylandCompositor.nativeSetSurface(surface)
                resumeVsync()
            }
            return false
        }
        start()
        attached = surface
        started = true
        if (surface != null) resumeVsync()
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
        val callback = vsyncCallback(++vsyncGeneration)
        vsyncRunning = true
        WaylandCompositor.nativeLog("screen", if (ownVsyncThread) "screen tick runs on its own display-priority thread" else "screen tick runs on the main thread")
        if (!ownVsyncThread) {
            Choreographer.getInstance().postFrameCallback(callback)
            return
        }
        vsyncHandler.post { Choreographer.getInstance().postFrameCallback(callback) }
    }

    private fun pauseVsync() {
        vsyncRunning = false
    }

    fun newSession(context: Context) {
        val log = File(SessionPaths.beginOrCurrent(context), "wayland.log")
        WaylandCompositor.setSessionLogFile(log)
        runCatching { android.system.Os.setenv("BL_WAYLAND_LOG", log.path, true) }
            .onFailure { Log.w("CompositorHost", "could not set compositor log", it) }
        if (started) WaylandCompositor.nativeResetFirstFrame()
    }

    private fun updateDisplay(context: Context, display: Display?): HdrProbe {
        val probe = HdrSupport.probe(context, display)
        WaylandCompositor.nativeSetHdrDisplay(
            probe.displayId, probe.name, probe.formats, probe.hdr10,
            probe.maxLuminance, probe.maxAverageLuminance, probe.minLuminance,
            probe.ratioAvailable, probe.ratio, Build.VERSION.SDK_INT,
        )
        return probe
    }

    private fun configureOutput(context: Context, mode: String, display: Display?) {
        val probe = updateDisplay(context, display)
        if (started) return
        val wanted = SessionPrefs.hdr(context, mode)
        val on = wanted && probe.reason == null
        SessionState.hdr = on
        if (on) {
            try { android.system.Os.setenv("DROIDDECK_WAYLAND_HDR", "1", true) } catch (e: Exception) { Log.w("CompositorHost", "DROIDDECK_WAYLAND_HDR", e) }
            WaylandCompositor.nativeSetZeroCopy(true)
        } else {
            runCatching { android.system.Os.unsetenv("DROIDDECK_WAYLAND_HDR") }
                .onFailure { Log.w("CompositorHost", "DROIDDECK_WAYLAND_HDR", it) }
        }
        WaylandCompositor.nativeSetHdrRequest(
            if (on) WaylandCompositor.HDR_MODE_ON else WaylandCompositor.HDR_MODE_OFF,
            "$mode session settings" + (if (wanted && !on) " (refused: ${probe.reason})" else ""),
            on, on,
        )
        Log.i("CompositorHost", "hdr: " + (if (on) "on" else if (wanted) "wanted but ${probe.reason}" else "off") + " · display ${probe.formats.ifEmpty { "SDR" }}")
    }

    val isStarted: Boolean get() = started
}
