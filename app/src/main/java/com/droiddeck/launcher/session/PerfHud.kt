package com.droiddeck.launcher.session

import android.content.Context
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

/**
 * The numbers behind the top-right line: the game's own frame rate and, when frame generation
 * is on, what the screen is actually being shown - "60 → 118 fps" is the proof that the engine
 * is doing something, and "FG starting" or a reason is the proof that it is not.
 *
 * The base rate is counted here from the compositor's per-frame callback, so it is right with or
 * without an engine. The presented rate comes from the engine's own telemetry while it generates.
 */
class PerfHud(context: Context) {
    /** Empty when the HUD is off or not yet started. */
    var text by mutableStateOf("")
        private set

    private val context = context.applicationContext
    private val frames = AtomicInteger()
    private var lastTick = 0L
    private var running = false
    private val handler = Handler(Looper.getMainLooper())

    /** The session's own switch (drawer), with the Downloads file as a device-side override. */
    private val enabled: Boolean
        get() = SessionPrefs.hudEnabled(context)
            && !File(Environment.getExternalStorageDirectory(), "Download/droiddeck-no-hud").exists()

    /** Told, on the main thread, when the window presenting frames changes. */
    var onPresentingWindowChanged: (() -> Unit)? = null
    private var lastWindow: String? = null

    private val listener = object : WaylandCompositor.GameListener {
        override fun onGameSurface(window: String?, gpuName: String?) {
            // gamescope forwards a fullscreen game's own buffers straight through and its composited
            // output when the Steam menu is up; each is a different window here. Compositor thread.
            if (window != null && window != lastWindow) {
                lastWindow = window
                handler.post { onPresentingWindowChanged?.invoke() }
            }
        }
        override fun onGameFrame() { frames.incrementAndGet() }
    }

    private val tick = object : Runnable {
        override fun run() {
            if (!running) return
            val now = SystemClock.elapsedRealtime()
            val dt = (now - lastTick).coerceAtLeast(1L) / 1000f
            lastTick = now
            text = line(frames.getAndSet(0) / dt)
            handler.postDelayed(this, 1000)
        }
    }

    /** Re-reads the switch: starts or stops to match it. */
    fun refresh() {
        if (enabled) start() else stop()
    }

    fun start() {
        if (!enabled || running) return
        running = true
        lastTick = SystemClock.elapsedRealtime()
        frames.set(0)
        WaylandCompositor.setGameListener(listener)
        handler.post(tick)
    }

    fun stop() {
        running = false
        WaylandCompositor.clearGameListener(listener)
        text = ""
    }

    private fun line(base: Float): String {
        val mode = FrameGen.mode(context)
        if (mode.engine == FrameGen.ENGINE_OFF) return fps(base)
        val lsfg = mode.engine == FrameGen.ENGINE_LSFG
        val name = if (lsfg) "LSFG" else "Win-FG"
        val label = FrameGen.label(context, mode)
        if (lsfg && Lossless.cacheFile(context)?.isFile != true) return context.getString(R.string.hud_fg_needs_lossless, name)
        val stats = try { WaylandCompositor.nativeFrameGenStats() } catch (t: Throwable) { null }
        val problem = try { WaylandCompositor.nativeFrameGenProblem() } catch (t: Throwable) { -1 }
        return when {
            problem == 1 -> context.getString(R.string.hud_fg_unsupported, name)
            problem == 2 -> context.getString(R.string.hud_fg_failed, name)
            stats != null && stats[3] > 1f -> {
                val source = if (stats[2] > 1f) stats[2] else base
                String.format(Locale.US, "%s  %.0f → %.0f fps", label, source, stats[3])
            }
            else -> context.getString(R.string.hud_fg_starting, label, fps(base))
        }
    }

    private fun fps(value: Float) = String.format(Locale.US, "%.0f fps", value)
}
