package com.droiddeck.launcher.hud

import java.io.File
import kotlin.math.roundToInt

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

    private val slowTimes = LongArray(SLOW_FRAMES)
    private var slowHead = 0
    private var slowSize = 0

    @Volatile var fps = 0f
        private set

    @Volatile var fpsUpdates = 0L
        private set

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
            if (frametimeNs > SLOW_NS) {
                slowTimes[slowHead] = timeNs
                slowHead = (slowHead + 1) % SLOW_FRAMES
                if (slowSize < SLOW_FRAMES) slowSize++
            }
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
        slowHead = 0
        slowSize = 0
        fps = 0f
        count++
    }

    fun frametimeMs(): Float = 1000f / maxOf(fps, 1f)

    @Synchronized
    fun graph(out: FloatArray) {
        for (i in 0 until GRAPH_FRAMES) out[i] = graph[(graphHead + i) % GRAPH_FRAMES]
    }

    @Synchronized
    fun graphMinMs(): Float = graph.min()

    @Synchronized
    fun graphMaxMs(): Float = graph.max()

    @Synchronized
    fun averageFps(): Float {
        if (sessionFrames < 2 || lastPresentNs <= sessionStartNs) return 0f
        return (1e9 * sessionFrames / (lastPresentNs - sessionStartNs)).toFloat()
    }

    @Synchronized
    fun sessionSeconds(nowNs: Long): Float = if (sessionStartNs == 0L) 0f else (nowNs - sessionStartNs) / 1e9f

    @Synchronized
    fun slowFrames(nowNs: Long, windowNs: Long): Int {
        var n = 0
        for (i in 0 until slowSize) {
            if (nowNs - slowTimes[(slowHead - 1 - i + SLOW_FRAMES) % SLOW_FRAMES] > windowNs) break
            n++
        }
        return n
    }

    class Lows(val low1Fps: Float, val low01Fps: Float, val low001Fps: Float) {
        companion object {
            val EMPTY = Lows(0f, 0f, 0f)
        }
    }

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
        const val SLOW_NS = 33_333_334L
        private const val SLOW_FRAMES = 2048
        private const val LOWS_RECOMPUTE_NS = 250_000_000L
    }
}

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
    @Volatile private var compositorFrames = 0L
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

    val live: Boolean get() = fromGamescope

    fun poll(nowNs: Long): Boolean {
        val compositorAdded = compositorFrames != seenCompositorFrames
        seenCompositorFrames = compositorFrames
        if (handle == 0L) {
            if (nowNs < nextOpenNs) return compositorAdded
            nextOpenNs = nowNs + REOPEN_NS
            handle = runCatching { HudFrames.nativeOpen(file.path) }.getOrDefault(0L)
            if (handle == 0L) return compositorAdded
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
        if (n <= 0 && nowNs - maxOf(lastRingFrameNs, nextOpenNs - REOPEN_NS) > STALE_NS) close()
        if (nowNs - lastInfoNs >= INFO_NS) {
            lastInfoNs = nowNs
            readInfo()
        }
        return n > 0 || compositorAdded
    }

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
