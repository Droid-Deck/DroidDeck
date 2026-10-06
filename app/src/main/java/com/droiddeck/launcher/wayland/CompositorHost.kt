package com.droiddeck.launcher.wayland

import android.view.Choreographer
import android.view.Surface

/**
 * The compositor is a process-wide thing, not an activity's: it is started once, keeps running
 * while the session does, and merely swaps the Surface it presents into as the activity comes and
 * goes. An activity that started it again on re-creation would be starting a second compositor on
 * a socket the first one owns.
 */
object CompositorHost {
    @Volatile
    private var started = false
    private var vsyncRunning = false

    private val vsync = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            WaylandCompositor.nativeVsync(frameTimeNanos)
            if (vsyncRunning) Choreographer.getInstance().postFrameCallback(this)
        }
    }

    /** True when this call was the one that started the compositor. */
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
        if (started) {
            // The compositor outlives sessions, and the next one may have another resolution: the
            // size it configures gamescope's window with must be this session's, not the first's.
            WaylandCompositor.nativeSetOutputSize(outputWidth, outputHeight)
            WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
            attached = surface
            WaylandCompositor.nativeSetSurface(surface)
            resumeVsync()
            return false
        }
        WaylandCompositor.nativeSetOutputSize(outputWidth, outputHeight)
        WaylandCompositor.nativeSetOutputRefreshRate(refreshHz)
        WaylandCompositor.nativeStartWithSurface(
            surface, xdgRuntimeDir, driverPath, libraryName, nativeLibDir,
        )
        attached = surface
        started = true
        resumeVsync()
        return true
    }

    /**
     * The session's frame cap, on every attach: the compositor outlives sessions, and the next one
     * may have a different cap. The release pacer hands a game one buffer back per capped frame
     * (per refresh without a cap), and the zero-copy layer votes for the same cadence. Left at 0
     * the vote is inferred from the rate already being achieved, and on a phone whose vendor picks
     * clocks from that a session that has been slowed reads as one that wants to be. (WinNative,
     * WaylandSession.)
     */
    private fun pace(refreshHz: Float, fpsLimit: Int) {
        WaylandCompositor.nativeSetFpsLimit(fpsLimit)
        WaylandCompositor.nativeSetLayerFrameRate(if (fpsLimit > 0) fpsLimit.toFloat() else refreshHz)
    }

    /**
     * The Surface changed size under the compositor (a foldable opening or closing). Android does
     * not always recreate the Surface for that, and a swapchain built for the old size keeps
     * presenting: the system then scales those buffers onto the new window, which on a Fold showed
     * a 16:9 picture stretched to the square panel. Rebinding the window rebuilds the swapchain at
     * the new size, and the scale mode letterboxes as it should.
     */
    @Synchronized
    fun resize(surface: Surface, rearm: () -> Unit) {
        if (!started) return
        // Frame generation does not live through a resize: its ring, its effects chain and its
        // multi-present pacing were all built against the old extent, and on a Fold the picture
        // came back stretched with an engine on and right with it off. Disarmed, the window is
        // rebound and the swapchain rebuilt; the caller re-arms once that has settled, and every
        // engine-side object is made again for the new size.
        WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        WaylandCompositor.nativeLog("screen", "surface resized: rebinding the window, frame generation re-armed after")
        WaylandCompositor.nativeSetSurface(null)
        attached = surface
        WaylandCompositor.nativeSetSurface(surface)
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(rearm, 600)
    }

    /**
     * The window the guest presents through has changed (the Steam menu opening or closing over a
     * fullscreen game swaps gamescope between forwarding the game's own buffers and its composited
     * output). The engine's ring and history straddle that switch and come back wrong-sized - a
     * game shown as a strip in a corner - so it is disarmed and re-armed against the new window.
     */
    fun rearmFrameGen(rearm: () -> Unit) {
        if (!started) return
        WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        WaylandCompositor.nativeLog("screen", "presenting window changed: frame generation re-armed")
        android.os.Handler(android.os.Looper.getMainLooper()).postDelayed(rearm, 600)
    }

    /**
     * The activity is going away. The compositor keeps running with nothing to present into -
     * the guest carries on, and its next frames land on the Surface the next activity brings.
     */
    /** The Surface the compositor presents on; only its own owner may take it away. */
    private var attached: Surface? = null

    /**
     * Only for the Surface that is attached. When one session hands over to the next (the
     * desktop's Steam launchers), the new activity attaches its Surface before the old one is
     * destroyed - and the old one's surfaceDestroyed then arrived here and took the new Surface
     * away: gamescope's frames came in by the thousand and nothing reached the screen.
     */
    @Synchronized
    fun detach(surface: Surface?) {
        if (!started) return
        if (surface != null && attached != null && attached !== surface) return
        attached = null
        pauseVsync()
        WaylandCompositor.nativeSetSurface(null)
    }

    /** Frames are only worth drawing while a Surface is attached. */
    private fun resumeVsync() {
        if (vsyncRunning) return
        vsyncRunning = true
        Choreographer.getInstance().postFrameCallback(vsync)
    }

    private fun pauseVsync() {
        vsyncRunning = false
    }

    /**
     * A new session behind a compositor that has already presented: the first-frame notice is
     * one-shot in native code, so it is re-armed here or the loading panel would never leave.
     */
    fun newSession() {
        if (started) WaylandCompositor.nativeResetFirstFrame()
    }

    val isStarted: Boolean get() = started
}
