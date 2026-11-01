package com.droiddeck.launcher.hud

import java.io.File
import kotlin.math.roundToInt

/**
 * Frame statistics by MangoHud's rules, so the HUD and Steam's performance overlay agree: MangoHud
 * overlay.cpp update_hud_info_with_frametime and hud_elements.cpp fps() / frame_timing(). The FPS is the
 * frames counted over a sampling period of at least 500 ms, the frame time next to it is 1000 / FPS, and
 * the graph holds the last 200 frame times (zeros until filled) on a fixed 0-50 ms scale, min and max
 * taken over those 200. Average and lows are DroidDeck HUD's own readouts.
 */
internal class FrameStats {
    private val graph = FloatArray(GRAPH_FRAMES)
    private var graphHead = 0
    private var lastPresentNs = 0L
    private var lastFpsUpdateNs = 0L
    private var framesSinceUpdate = 0

    private val lowsRing = LongArray(LOWS_FRAMES)
    private var lowsHead = 0
    private var lowsSize = 0
    private var cachedLows = Lows.EMPTY
    private var cachedLowsNs = 0L

    private var sessionStartNs = 0L
    private var sessionFrames = 0L

    @Volatile var fps = 0f
        private set

    /** Completed sampling periods; MangoHud redraws its readouts when one completes. */
    @Volatile var fpsUpdates = 0L
        private set

    /** Frames added so far; the HUD redraws its graph when this moves. */
    @Volatile var count = 0L
        private set

    @Synchronized
    fun add(frametimeNs: Long, timeNs: Long) {
        if (frametimeNs <= 0L) return
        if (lastPresentNs != 0L) {
            graph[graphHead] = frametimeNs / 1e6f
            graphHead = (graphHead + 1) % GRAPH_FRAMES
        }
        val elapsed = timeNs - lastFpsUpdateNs
        if (elapsed >= FPS_PERIOD_NS) {
            fps = (1e9 * framesSinceUpdate / elapsed).toFloat()
            framesSinceUpdate = 0
            lastFpsUpdateNs = timeNs
            fpsUpdates++
        }
        lastPresentNs = timeNs
        framesSinceUpdate++

        if (frametimeNs < PAUSE_NS) {
            lowsRing[lowsHead] = frametimeNs
            lowsHead = (lowsHead + 1) % LOWS_FRAMES
            if (lowsSize < LOWS_FRAMES) lowsSize++
            if (sessionStartNs == 0L) sessionStartNs = timeNs - frametimeNs
            sessionFrames++
        }
        count++
    }

    @Synchronized
    fun reset() {
        graph.fill(0f)
        graphHead = 0
        lastPresentNs = 0L
        lastFpsUpdateNs = 0L
        framesSinceUpdate = 0
        lowsHead = 0
        lowsSize = 0
        cachedLows = Lows.EMPTY
        cachedLowsNs = 0L
        sessionStartNs = 0L
        sessionFrames = 0L
        fps = 0f
        count++
    }

    /** MangoHud's frame time readout: 1000 / FPS. */
    fun frametimeMs(): Float = 1000f / maxOf(fps, 1f)

    /** The graph, oldest first, into [out] (GRAPH_FRAMES long). */
    @Synchronized
    fun graph(out: FloatArray) {
        for (i in 0 until GRAPH_FRAMES) out[i] = graph[(graphHead + i) % GRAPH_FRAMES]
    }

    @Synchronized
    fun graphMinMs(): Float = graph.min()

    @Synchronized
    fun graphMaxMs(): Float = graph.max()

    /** Average over the frames this session, from the first frame to [nowNs]'s last frame. */
    @Synchronized
    fun averageFps(): Float {
        if (sessionFrames < 2 || lastPresentNs <= sessionStartNs) return 0f
        return (1e9 * sessionFrames / (lastPresentNs - sessionStartNs)).toFloat()
    }

    @Synchronized
    fun sessionSeconds(nowNs: Long): Float = if (sessionStartNs == 0L) 0f else (nowNs - sessionStartNs) / 1e9f

    class Lows(val low1Fps: Float, val low01Fps: Float, val low001Fps: Float) {
        companion object {
            val EMPTY = Lows(0f, 0f, 0f)
        }
    }

    /** FPS at the 99th, 99.9th and 99.99th percentile frame time of the last LOWS_FRAMES frames. */
    fun lows(nowNs: Long): Lows {
        val copy: LongArray
        synchronized(this) {
            if (cachedLows !== Lows.EMPTY && nowNs - cachedLowsNs < LOWS_RECOMPUTE_NS) return cachedLows
            if (lowsSize < 2) return Lows.EMPTY
            copy = LongArray(lowsSize)
            val start = (lowsHead - lowsSize + LOWS_FRAMES) % LOWS_FRAMES
            for (i in 0 until lowsSize) copy[i] = lowsRing[(start + i) % LOWS_FRAMES]
        }
        copy.sort()
        fun at(p: Double): Float {
            val ft = copy[(p * (copy.size - 1)).roundToInt().coerceIn(0, copy.size - 1)]
            return if (ft > 0) (1e9 / ft).toFloat() else 0f
        }
        val result = Lows(at(0.99), at(0.999), at(0.9999))
        synchronized(this) {
            cachedLows = result
            cachedLowsNs = nowNs
        }
        return result
    }

    companion object {
        const val GRAPH_FRAMES = 200
        const val GRAPH_MAX_MS = 50f
        const val FPS_PERIOD_NS = 500_000_000L
        const val LOWS_FRAMES = 8192
        private const val PAUSE_NS = 1_000_000_000L
        private const val LOWS_RECOMPUTE_NS = 250_000_000L
    }
}

/**
 * Feeds [stats] with the frame times gamescope shares (the ones MangoHud shows). Sessions without the
 * shared file, or where gamescope stopped sending, fall back to the frames DroidDeck's compositor shows,
 * which stop at the screen's refresh rate.
 */
internal class GameFrameFeed(private val file: File, val stats: FrameStats = FrameStats()) {
    private var handle = 0L
    private var nextOpenNs = 0L
    private var lastRingFrameNs = 0L
    private var lastInfoNs = 0L
    private val frametimes = LongArray(HudFrames.CAPACITY)
    private val times = LongArray(HudFrames.CAPACITY)
    private val info = IntArray(5)
    @Volatile private var fromGamescope = false
    private var lastCompositorNs = 0L
    @Volatile private var compositorFrames = 0L     // written by the compositor thread only
    private var seenCompositorFrames = 0L

    @Volatile var engine = ""
        private set
    @Volatile var steamFocused = false
        private set
    @Volatile var pid = 0
        private set
    @Volatile var outputWidth = 0
        private set
    @Volatile var outputHeight = 0
        private set
    @Volatile var refreshMilliHz = 0
        private set

    /** Main thread, once per display frame. True when new frames arrived. */
    fun poll(nowNs: Long): Boolean {
        val compositorAdded = compositorFrames != seenCompositorFrames
        seenCompositorFrames = compositorFrames
        if (handle == 0L) {
            if (nowNs < nextOpenNs) return compositorAdded
            nextOpenNs = nowNs + REOPEN_NS
            handle = runCatching { HudFrames.nativeOpen(file.path) }.getOrDefault(0L)
            if (handle == 0L) return compositorAdded
            // Frames already in the file were written before this reader started.
            HudFrames.nativePoll(handle, frametimes, times)
        }
        val n = HudFrames.nativePoll(handle, frametimes, times)
        if (n > 0) {
            if (!fromGamescope) {
                stats.reset()
                fromGamescope = true
            }
            for (i in 0 until n) stats.add(frametimes[i], times[i])
            lastRingFrameNs = times[n - 1]
        } else if (lastRingFrameNs != 0L && nowNs - lastRingFrameNs > LIVE_NS) {
            fromGamescope = false
        }
        // A new session writes the file again from the start; reopen when gamescope has gone quiet.
        if (n <= 0 && nowNs - maxOf(lastRingFrameNs, nextOpenNs - REOPEN_NS) > STALE_NS) close()
        if (nowNs - lastInfoNs >= INFO_NS) {
            lastInfoNs = nowNs
            readInfo()
        }
        return n > 0 || compositorAdded
    }

    /** Compositor thread: one frame shown on the panel. */
    fun onCompositorFrame(nowNs: Long) {
        if (fromGamescope) {
            lastCompositorNs = 0L
            return
        }
        val last = lastCompositorNs
        lastCompositorNs = nowNs
        if (last != 0L) {
            stats.add(nowNs - last, nowNs)
            compositorFrames++
        }
    }

    fun close() {
        if (handle != 0L) HudFrames.nativeClose(handle)
        handle = 0L
    }

    private fun readInfo() {
        val name = if (handle != 0L && fromGamescope) HudFrames.nativeInfo(handle, info) else null
        if (name == null) {
            engine = ""
            return
        }
        pid = info[0]
        outputWidth = info[1]
        outputHeight = info[2]
        refreshMilliHz = info[3]
        steamFocused = info[4] != 0
        engine = engineLabel(name)
    }

    companion object {
        private const val REOPEN_NS = 2_000_000_000L
        private const val LIVE_NS = 2_000_000_000L
        private const val STALE_NS = 5_000_000_000L
        private const val INFO_NS = 500_000_000L

        /** DroidDeck's mangoapp's names for gamescope's engine field (Valve's 20260902.1 build, app/main.cpp
         *  engine_type_from_str and overlay.h engines); gamescope sends "gamescope" when it knows none. */
        fun engineLabel(name: String): String = when (name) {
            "DXVK" -> "DXVK"
            "vkd3d" -> "VKD3D"
            "mesa zink" -> "ZINK"
            "Damavand" -> "DAMAVAND"
            "Feral3D" -> "Feral3D"
            "gamescope", "" -> "GAMESCOPE"
            else -> "VULKAN"
        }
    }
}
