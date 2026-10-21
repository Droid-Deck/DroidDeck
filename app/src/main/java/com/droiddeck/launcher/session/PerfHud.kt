package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.droiddeck.launcher.R
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class PerfHud(context: Context) {
    var text by mutableStateOf("")
        private set
    var graph by mutableStateOf(FloatArray(0))
        private set

    private val context = context.applicationContext
    private val frames = AtomicInteger()
    private var lastTick = 0L
    private var lastFps = 0f
    private var running = false
    private val handler = Handler(Looper.getMainLooper())
    private val intervals = FloatArray(GRAPH)
    private val graphWrite = AtomicInteger()
    private val lastFrameNs = AtomicLong()

    @Volatile private var showFps = true
    @Volatile private var showBattery = true
    @Volatile private var showGraph = true

    private val enabled: Boolean
        get() = SessionPrefs.hudEnabled(context)
            && !File(Environment.getExternalStorageDirectory(), "Download/droiddeck-no-hud").exists()

    private val anyPart: Boolean
        get() = showFps || showBattery || showGraph

    var onPresentingWindowChanged: (() -> Unit)? = null
    private var lastWindow: String? = null

    private val listener = object : WaylandCompositor.GameListener {
        override fun onGameSurface(window: String?, gpuName: String?) {
            if (window != null && window != lastWindow) {
                lastWindow = window
                handler.post { onPresentingWindowChanged?.invoke() }
            }
        }
        override fun onGameFrame() {
            frames.incrementAndGet()
            if (!showGraph) return
            val now = System.nanoTime()
            val previous = lastFrameNs.getAndSet(now)
            if (previous == 0L) return
            val ms = ((now - previous) / 1_000_000f).coerceIn(0f, 50f)
            val slot = graphWrite.get()
            intervals[slot and (GRAPH - 1)] = ms
            graphWrite.compareAndSet(slot, slot + 1)
        }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick).coerceAtLeast(1L) / 1000f
            lastTick = now
            lastFps = frames.getAndSet(0) / dt
            text = readout(lastFps)
            if (showGraph) publishGraph() else clearGraph()
            handler.postDelayed(this, TICK_MS)
        }
    }

    fun refresh() {
        readOptions()
        if (!enabled || !anyPart) {
            stop()
            return
        }
        if (!running) start() else {
            if (!showGraph) clearGraph() else publishGraph()
            text = readout(lastFps)
        }
    }

    fun start() {
        readOptions()
        if (!enabled || !anyPart || running) return
        running = true
        lastTick = SystemClock.elapsedRealtime()
        lastFrameNs.set(0L)
        frames.set(0)
        graphWrite.set(0)
        WaylandCompositor.setGameListener(listener)
        handler.postDelayed(tick, TICK_MS)
    }

    fun stop() {
        running = false
        handler.removeCallbacks(tick)
        WaylandCompositor.clearGameListener(listener)
        text = ""
        graph = FloatArray(0)
    }

    private fun readOptions() {
        showFps = SessionPrefs.hudShowFps(context)
        showBattery = SessionPrefs.hudShowBattery(context)
        showGraph = SessionPrefs.hudShowGraph(context)
    }

    private fun readout(fpsValue: Float): String {
        val parts = ArrayList<String>(2)
        if (showFps) parts.add(line(fpsValue))
        if (showBattery) batteryCelsius(readBatteryDeci())?.let { parts.add(it) }
        return parts.joinToString("\n")
    }

    private fun readBatteryDeci(): Int {
        val sticky = runCatching {
            context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        }.getOrNull() ?: return 0
        return sticky.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0)
    }

    private fun clearGraph() {
        lastFrameNs.set(0L)
        graphWrite.set(0)
        if (graph.isNotEmpty()) graph = FloatArray(0)
    }

    private fun publishGraph() {
        val count = graphWrite.get()
        val shown = minOf(count, GRAPH)
        if (shown < 2) {
            graph = FloatArray(0)
            return
        }
        val out = FloatArray(shown)
        val start = count - shown
        for (i in 0 until shown) out[i] = intervals[(start + i) and (GRAPH - 1)]
        graph = out
    }

    private fun line(base: Float): String {
        val mode = FrameGen.mode(context)
        if (mode.engine == FrameGen.ENGINE_OFF) return fps(base)
        val lsfg = mode.engine == FrameGen.ENGINE_LSFG
        val name = if (lsfg) "LSFG" else "DD-FG"
        val label = FrameGen.label(context, mode)
        if (lsfg && Lossless.cacheFile(context)?.isFile != true) return context.getString(R.string.hud_fg_needs_lossless, name)
        val stats = try { WaylandCompositor.nativeFrameGenStats() } catch (t: Throwable) { null }
        val problem = try { WaylandCompositor.nativeFrameGenProblem() } catch (t: Throwable) { -1 }
        return when {
            problem == 1 -> context.getString(R.string.hud_fg_unsupported, name)
            problem == 2 -> context.getString(R.string.hud_fg_failed, name)
            stats != null && stats[3] > 1f -> String.format(Locale.US, "%s  %.0f → %.0f fps", label, base, stats[3])
            else -> context.getString(R.string.hud_fg_starting, label, fps(base))
        }
    }

    private fun fps(value: Float) = String.format(Locale.US, "%.0f fps", value)

    companion object {
        private const val TICK_MS = 1_000L
        private const val GRAPH = 128

        internal fun batteryCelsius(deci: Int): String? =
            if (deci <= 0) null else String.format(Locale.US, "%.1f°C", deci / 10f)
    }
}
