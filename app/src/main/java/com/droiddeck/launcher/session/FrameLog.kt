package com.droiddeck.launcher.session

import com.droiddeck.launcher.hud.GameFrameFeed
import java.io.File
import java.util.Locale
import kotlin.math.roundToInt

internal class FramePacing {
    private var times = LongArray(INITIAL_RING)
    private var frametimes = LongArray(INITIAL_RING)
    private var head = 0
    private var size = 0
    private var frames = 0
    private var sumNs = 0L
    private var worstNs = 0L
    private var over50 = 0
    private var over250 = 0

    @Synchronized
    fun add(frametimeNs: Long, timeNs: Long) {
        if (frametimeNs <= 0L) return
        frames++
        sumNs += frametimeNs
        if (frametimeNs > worstNs) worstNs = frametimeNs
        if (frametimeNs > HITCH_NS) over50++
        if (frametimeNs > FREEZE_NS) over250++
        while (size > 0 && timeNs - times[(head - size + times.size) % times.size] > LOWS_WINDOW_NS) size--
        if (size == times.size) grow()
        times[head] = timeNs
        frametimes[head] = frametimeNs
        head = (head + 1) % times.size
        if (size < times.size) size++
    }

    private fun grow() {
        val cap = times.size
        if (cap >= MAX_RING) return
        val oldest = (head - size + cap) % cap
        val oldTimes = times
        val oldFrametimes = frametimes
        times = LongArray(cap * 2) { if (it < size) oldTimes[(oldest + it) % cap] else 0L }
        frametimes = LongArray(cap * 2) { if (it < size) oldFrametimes[(oldest + it) % cap] else 0L }
        head = size
    }

    @Synchronized
    fun line(nowNs: Long, periodMs: Long): String? {
        if (frames == 0) {
            if (size == 0) return null
            val since = nowNs - times[(head - 1 + times.size) % times.size]
            return if (since > LOWS_WINDOW_NS) null
                else String.format(Locale.ROOT, "last %.0f s: no frames, last one %.1f s ago", periodMs / 1000.0, since / 1e9)
        }
        val recent = LongArray(size)
        var n = 0
        for (i in 0 until size) {
            val at = (head - 1 - i + times.size) % times.size
            if (nowNs - times[at] > LOWS_WINDOW_NS) continue
            if (frametimes[at] < PAUSE_NS) recent[n++] = frametimes[at]
        }
        val sorted = recent.copyOf(n).apply { sort() }
        fun low(p: Double): String = if (sorted.isEmpty()) "n/a" else
            String.format(Locale.ROOT, "%.1f fps", 1e9 / sorted[(p * (sorted.size - 1)).roundToInt().coerceIn(0, sorted.size - 1)])
        val text = String.format(Locale.ROOT,
            "last %.0f s: %d frames (%.1f fps), frame time %.1f ms avg, %.0f ms worst, %d over 50 ms, %d over 250 ms | " +
                "last 30 s: 1%% low %s, 0.1%% low %s",
            periodMs / 1000.0, frames, frames * 1000.0 / periodMs, sumNs / 1e6 / frames, worstNs / 1e6, over50, over250,
            low(0.99), low(0.999))
        frames = 0
        sumNs = 0L
        worstNs = 0L
        over50 = 0
        over250 = 0
        return text
    }

    companion object {
        private const val INITIAL_RING = 8192
        private const val MAX_RING = 1 shl 18
        private const val HITCH_NS = 50_000_000L
        private const val FREEZE_NS = 250_000_000L
        private const val PAUSE_NS = 1_000_000_000L
        const val LOWS_WINDOW_NS = 30_000_000_000L
    }
}

/**
 * One logger's output. Every line goes out under this object's lock, so once [stop] returns nothing more is
 * written, even if the logger thread is still finishing a poll.
 */
internal class FrameLogRun(private val pacing: FramePacing, startNs: Long, private val sink: (String) -> Unit) {
    private var lastNs = startNs
    private var stopped = false

    @Synchronized
    fun tick(nowNs: Long) {
        if (stopped || nowNs - lastNs < PERIOD_NS) return
        pacing.line(nowNs, (nowNs - lastNs) / 1_000_000L)?.let(sink)
        lastNs = nowNs
    }

    /** Ends the run; with [flush] it first logs the frames since the last line. */
    @Synchronized
    fun stop(nowNs: Long, flush: Boolean) {
        if (stopped) return
        stopped = true
        if (flush && nowNs - lastNs >= 1_000_000_000L) pacing.line(nowNs, (nowNs - lastNs) / 1_000_000L)?.let(sink)
    }

    companion object {
        const val PERIOD_NS = 3_000_000_000L
    }
}

object FrameLog {
    private const val TAG = "Frames"
    private const val POLL_MS = 1_000L
    private var thread: Thread? = null
    private var run: FrameLogRun? = null
    private var owner = 0

    /** Logs frame pacing for session [gen], replacing a logger an older session left running. */
    @Synchronized
    fun start(root: File, gen: Int) {
        if (thread != null && owner == gen) return
        run?.stop(System.nanoTime(), flush = false)
        thread?.interrupt()
        owner = gen
        val pacing = FramePacing()
        val current = FrameLogRun(pacing, System.nanoTime()) { android.util.Log.i(TAG, it) }
        run = current
        thread = Thread({
            val feed = runCatching { GameFrameFeed.forRoot(root) { ft, at -> pacing.add(ft, at) } }.getOrNull() ?: return@Thread
            try {
                while (!Thread.currentThread().isInterrupted) {
                    val now = System.nanoTime()
                    runCatching { feed.poll(now) }
                    current.tick(now)
                    Thread.sleep(POLL_MS)
                }
            } catch (_: InterruptedException) {
            } finally {
                feed.close()
            }
        }, "frame-log").apply { isDaemon = true; start() }
    }

    /**
     * Stops the logger if session [gen] started it and logs its last partial period on the caller's thread,
     * while that session still owns app.log. A stale session's teardown leaves a newer logger running.
     */
    @Synchronized
    fun stop(gen: Int) {
        if (gen != owner) return
        run?.stop(System.nanoTime(), flush = true)
        run = null
        thread?.interrupt()
        thread = null
    }
}
