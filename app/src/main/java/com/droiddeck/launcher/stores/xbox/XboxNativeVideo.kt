package com.droiddeck.launcher.stores.xbox

import android.content.Context
import android.graphics.PixelFormat
import android.hardware.HardwareBuffer
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.wayland.CompositorHost
import com.droiddeck.launcher.wayland.WaylandCompositor
import org.webrtc.EglBase
import org.webrtc.EglRenderer
import org.webrtc.GlRectDrawer
import org.webrtc.VideoFrame
import org.webrtc.VideoSink
import java.io.File
import java.util.ArrayDeque

/**
 * The native player's picture: WebRTC's hardware-decoded frames are drawn (GL, WebRTC's EglRenderer) into an
 * ImageReader whose images are GPU-shareable HardwareBuffers, and each one goes to DroidDeck's own compositor in
 * stream mode (WaylandCompositor.nativeStreamFrame) as the whole scene, where DroidDeck's frame generation (DDFG,
 * or LSFG with the Lossless.dll) multiplies it. The player runs in its own process (:xcloudnative), so this
 * compositor is never the one a Linux session uses. Android 10+ (ImageReader usage flags).
 */
internal class XboxNativeVideo(private val context: Context, eglBase: EglBase, private val log: (String) -> Unit) : VideoSink {
    companion object {
        const val FG_OFF = "off"
        const val FG_DDFG = "ddfg"
        const val FG_LSFG = "lsfg"
        const val FG_LSFG3 = "lsfg3"
        const val FG_LSFG4 = "lsfg4"
        fun isLsfg(mode: String) = mode == FG_LSFG || mode == FG_LSFG3 || mode == FG_LSFG4
        fun multiplier(mode: String) = when (mode) { FG_LSFG3 -> 3; FG_LSFG4 -> 4; FG_OFF -> 0; else -> 2 }
        fun modes(lsfg: Boolean) = if (lsfg) listOf(FG_OFF, FG_DDFG, FG_LSFG, FG_LSFG3, FG_LSFG4) else listOf(FG_OFF, FG_DDFG)
        fun modeName(mode: String) = when (mode) {
            FG_DDFG -> "DDFG 2×"; FG_LSFG -> "LSFG 2×"; FG_LSFG3 -> "LSFG 3×"; FG_LSFG4 -> "LSFG 4×"; else -> "Off"
        }
        /** LSFG can run: the Lossless.dll was imported (or found in Steam) and its shader cache built. */
        fun lsfgReady(context: Context) = Lossless.state(context).ready
        private const val SCALE_FIT = 1
        private const val ALIGN_CENTER = 0
    }

    private val lock = Any()
    private val egl = EglRenderer("xcloud-native").apply { init(eglBase.eglBaseContext, EglBase.CONFIG_RGBA, GlRectDrawer()) }
    private val readerThread = HandlerThread("xcloud-frames").apply { start() }
    private val readerHandler = Handler(readerThread.looper)
    private var reader: ImageReader? = null
    private var readerW = 0
    private var readerH = 0
    /** Images the compositor may still read: the oldest goes back to the reader once two newer ones arrived. */
    private val held = ArrayDeque<Image>()
    private var surface: Surface? = null
    private var attached = false
    @Volatile private var frameGen = FG_OFF
    @Volatile private var refreshHz = 60f
    /** The frame-rate target (0 = the screen's rate): the frame-gen pacer's cap, or a limit on stream frames without it. */
    @Volatile private var fpsTarget = 0
    private var nextDueNs = 0L
    @Volatile var frames = 0L
        private set

    /** The screen: the compositor presents to it (null detaches, e.g. for zero-copy or when the surface goes). */
    fun setSurface(target: Surface?, width: Int, height: Int, displayRefreshHz: Float) = synchronized(lock) {
        refreshHz = displayRefreshHz
        if (target == null) {
            if (attached) letGo()
            attached = false; surface = null
            return@synchronized
        }
        surface = target
        val runtime = File(context.cacheDir, "xcloud-wayland").apply { mkdirs() }
        // System Vulkan driver; this process's compositor shows only the stream.
        val started = CompositorHost.startOrAttach(context, target, runtime.path, null, null, context.applicationInfo.nativeLibraryDir,
            width, height, displayRefreshHz, 0)
        if (started) {
            WaylandCompositor.nativeSetScaleMode(SCALE_FIT, ALIGN_CENTER)
            log("compositor started on ${width}x$height at ${displayRefreshHz.toInt()} Hz")
        } else {
            WaylandCompositor.nativeResizeOutput(width, height)
        }
        WaylandCompositor.nativeStreamMode(true)
        attached = true
        applyFrameGen()
    }

    /**
     * Gives the screen surface up and waits until the compositor has really disconnected from it: the window request
     * is applied on the compositor thread at its next tick, and the decoder (zero-copy) can only connect after that.
     */
    private fun letGo() {
        WaylandCompositor.nativeSetFrameGenArmed(false, 0, 0)
        CompositorHost.detach(surface)
        WaylandCompositor.nativeVsync(System.nanoTime())
        if (!WaylandCompositor.nativeWaitSurface(1000)) log("compositor did not let go of the screen within 1 s")
    }

    /** Frame-rate target, 0 = the screen's rate. Live. */
    fun setFpsTarget(fps: Int) {
        fpsTarget = fps
        synchronized(lock) { if (attached) applyFrameGen() }
    }

    /** off, ddfg (2x) or lsfg / lsfg3 / lsfg4 (needs the Lossless.dll). Live. */
    fun setFrameGeneration(mode: String) {
        frameGen = mode
        synchronized(lock) { if (attached) applyFrameGen() }
    }

    override fun onFrame(frame: VideoFrame) {
        val w = frame.rotatedWidth; val h = frame.rotatedHeight
        synchronized(lock) { if (w != readerW || h != readerH) startReader(w, h) }
        egl.onFrame(frame)
    }

    private fun startReader(w: Int, h: Int) {
        val old = reader
        if (old != null) egl.releaseEglSurface { }
        held.forEach { runCatching { it.close() } }; held.clear()
        old?.close()
        readerW = w; readerH = h
        val next = ImageReader.newInstance(w, h, PixelFormat.RGBA_8888, 4,
            HardwareBuffer.USAGE_GPU_SAMPLED_IMAGE or HardwareBuffer.USAGE_GPU_COLOR_OUTPUT)
        next.setOnImageAvailableListener({ r -> onImage(r) }, readerHandler)
        reader = next
        egl.createEglSurface(next.surface)
        log("native video ${w}x$h")
    }

    private fun onImage(r: ImageReader) {
        val image = runCatching { r.acquireLatestImage() }.getOrNull() ?: return
        synchronized(lock) {
            if (r !== reader) { image.close(); return }
            val buffer = image.hardwareBuffer
            // Without frame generation a target below the stream's rate drops stream frames evenly: each frame has a
            // due time one interval after the last one's, so 30 of 60 frames (or 45: three of four) reach the screen.
            val now = System.nanoTime()
            val target = fpsTarget
            val skip = frameGen == FG_OFF && target in 1 until 60 && run {
                val interval = 1_000_000_000L / target
                if (nextDueNs == 0L || now - nextDueNs > interval) nextDueNs = now
                if (now + 4_000_000L < nextDueNs) true else { nextDueNs += interval; false }
            }
            if (attached && buffer != null && !skip) { WaylandCompositor.nativeStreamFrame(buffer); frames++ }
            buffer?.close()
            held.addLast(image)
            while (held.size > 2) held.removeFirst().close()
        }
    }

    private fun applyFrameGen() {
        // The pacer caps generated output at this rate: the user's target, else the screen's.
        val cap = if (fpsTarget > 0) fpsTarget.toFloat() else refreshHz
        val mode = when {
            frameGen == FG_DDFG -> FrameGen.Mode(FrameGen.ENGINE_DDFG, 2)
            isLsfg(frameGen) -> FrameGen.Mode(FrameGen.ENGINE_LSFG, multiplier(frameGen))
            else -> FrameGen.Mode.OFF
        }
        val problem = FrameGen.apply(context, mode, cap)
        log("native frame generation: $frameGen, target ${if (fpsTarget > 0) fpsTarget else "screen"} fps" + (problem?.let { " ($it)" } ?: ""))
    }

    /** {planned, planned, sourceFps, presentedFps, thermal, -1}, or null while nothing is shown. */
    fun stats(): FloatArray? = synchronized(lock) { if (attached) WaylandCompositor.nativeFrameGenStats() else null }

    fun frameGenProblem(): Int = synchronized(lock) { if (attached) WaylandCompositor.nativeFrameGenProblem() else -1 }

    fun release() {
        egl.release()
        synchronized(lock) {
            if (attached) { WaylandCompositor.nativeStreamMode(false); letGo() }
            attached = false; surface = null
            held.forEach { runCatching { it.close() } }; held.clear()
            reader?.close(); reader = null
        }
        readerThread.quitSafely()
    }
}
