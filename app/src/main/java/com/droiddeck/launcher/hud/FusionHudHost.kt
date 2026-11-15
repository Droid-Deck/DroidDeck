package com.droiddeck.launcher.hud

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import kotlin.math.roundToInt

/**
 * FusionHUD on the session screen, in place of the old text HUD: the view, the frame feed from gamescope
 * (MangoHud's frame times) and MangoHud's readings, started with the session's first frame.
 */
class FusionHudHost(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val feed = GameFrameFeed(File(LinuxRuntime.rootDir(this.context), HudFrames.PATH))
    private val readings = MangoReadings(File(this.context.filesDir, "session"), LinuxRuntime.rootDir(this.context))
    private var view: FusionHudView? = null
    private var parent: ViewGroup? = null
    private var running = false
    private var hidden = false
    private var backgrounded = false
    private var polling = false
    private var wantX = Float.NaN
    private var wantY = Float.NaN
    private var engine = ""
    private var gpuName = ""
    private var protonPid = 0
    private var proton = ""
    @Volatile private var lastWindow: String? = null

    var onPresentingWindowChanged: (() -> Unit)? = null

    /** The HUD itself changed a setting: a tap cycles the size, a long press locks it. */
    var onConfigChanged: (() -> Unit)? = null

    /** Screen point → whether an on-screen control takes a touch there; a locked HUD lets those through. */
    var passesTouchAt: ((rawX: Float, rawY: Float) -> Boolean)? = null

    private val listener = object : WaylandCompositor.GameListener {
        override fun onGameSurface(window: String?, gpuName: String?) {
            if (!gpuName.isNullOrBlank()) handler.post { this@FusionHudHost.gpuName = cleanGpuName(gpuName) }
            if (window != null && window != lastWindow) {
                lastWindow = window
                handler.post { onPresentingWindowChanged?.invoke() }
            }
        }

        override fun onGameFrame() {
            feed.onCompositorFrame(System.nanoTime())
        }
    }

    private val frameCallback = object : Choreographer.FrameCallback {
        override fun doFrame(frameTimeNanos: Long) {
            if (!running || !polling) return
            if (feed.poll(System.nanoTime())) view?.onFrames()
            Choreographer.getInstance().postFrameCallback(this)
        }
    }

    private val enabled: Boolean
        get() = SessionPrefs.hudEnabled(context) &&
            !File(Environment.getExternalStorageDirectory(), "Download/droiddeck-no-hud").exists()

    /** Adds the HUD to [root] at [index] (above the game surface, under the session overlay). */
    fun attach(root: FrameLayout, index: Int) {
        if (view != null) return
        val hud = FusionHudView(root.context)
        hud.setFrameStats(feed.stats)
        hud.setMangoReadings(readings)
        hud.setGpuModel(cleanGpuName(GpuInfo.detect().displayName(context)))
        hud.onRefresh = Runnable { refreshLabels(hud) }
        hud.setOnMovedListener { x, y ->
            wantX = x; wantY = y
            keepOnScreen(hud)
            wantX = hud.x; wantY = hud.y
            SessionPrefs.setFusionHudPosition(context, hud.x, hud.y)
        }
        hud.setOnSizeCycledListener { size -> FusionHudConfig.set(context, "hudSize", size); onConfigChanged?.invoke() }
        hud.setOnLockChangedListener { locked ->
            FusionHudConfig.set(context, "hudLocked", if (locked) "1" else "0")
            onConfigChanged?.invoke()
        }
        hud.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> if (!hud.isDragging) keepOnScreen(hud) }
        hud.passesTouchAt = { x, y -> passesTouchAt?.invoke(x, y) == true }
        hud.visibility = View.GONE
        val margin = (16 * root.resources.displayMetrics.density).roundToInt()
        root.addView(hud, index, FrameLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply {
            leftMargin = margin
            topMargin = margin
        })
        SessionPrefs.fusionHudPosition(context)?.let { (x, y) -> wantX = x; wantY = y; hud.post { keepOnScreen(hud) } }
        view = hud
        parent = root
        applyConfig()
    }

    fun applyConfig() {
        view?.applyConfig(FusionHudConfig.encode(FusionHudConfig.load(context)))
    }

    fun refresh() {
        applyConfig()
        if (!enabled) stop() else if (!running) start()
    }

    fun start() {
        if (running || !enabled || view == null) return
        running = true
        WaylandCompositor.setGameListener(listener)
        applyShown()
    }

    fun stop() {
        running = false
        showing = false
        polling = false
        Choreographer.getInstance().removeFrameCallback(frameCallback)
        WaylandCompositor.clearGameListener(listener)
        feed.close()
        view?.visibility = View.GONE
    }

    /** Picture-in-picture and similar: keep running, draw nothing. */
    fun setHidden(hide: Boolean) {
        hidden = hide
        applyShown()
    }

    /** The session screen is stopped (another app in front): draw nothing and stop reading sensors. */
    fun setBackgrounded(stopped: Boolean) {
        backgrounded = stopped
        applyShown()
    }

    private fun applyShown() {
        if (!running) return
        val show = !hidden && !backgrounded
        view?.visibility = if (show) View.VISIBLE else View.GONE
        showing = show
        // Frames are read once per display frame only while the HUD is drawn.
        if (show != polling) {
            polling = show
            if (show) Choreographer.getInstance().postFrameCallback(frameCallback)
            else Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    /** A sensor report (FusionHUD's diagnostics) in Downloads/DroidDeck; null when it can't be written. */
    fun exportDiagnostics(): File? = runCatching {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DroidDeck")
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "HUD-diagnostics-$stamp.txt")
        // Its own reader: the HUD's CPU load is the change since its previous sample, which an export must
        // not move. Two samples half a second apart, MangoHud's sampling period.
        val metrics = HudMetrics(context)
        metrics.setMangoReadings(MangoReadings(File(context.filesDir, "session"), LinuxRuntime.rootDir(context)))
        metrics.snapshot()
        Thread.sleep(500)
        val mango = metrics.snapshot()
        file.writeText(metrics.also { it.setMangoReadings(null) }.buildDiagnosticsReport(context) + "\n== As the HUD shows them (MangoHud's sources) ==\n" +
            "CPU ${mango.cpuPercent}% ${mango.cpuTempC}°C, GPU ${mango.gpuPercent}% ${mango.gpuClockMhz}MHz ${mango.gpuTempC}°C, " +
            "RAM ${mango.ramUsedText()}/${mango.ramTotalText()}, VRAM ${mango.vramText()}, battery ${mango.battery.percent}% " +
            "${mango.battery.watts}W charging=${mango.battery.charging}\n" +
            "CPU temperature zones: ${LinuxRuntime.cpuTempZones().joinToString { it.name }}\n" +
            "Frames: ${if (feed.engine.isNotEmpty() || feed.pid > 0) "gamescope (pid ${feed.pid}, ${feed.engine})" else "compositor"}, " +
            "fps ${feed.stats.fps}\n")
        file
    }.getOrNull()

    private fun refreshLabels(hud: FusionHudView) {
        if (feed.engine.isNotBlank() && !feed.steamFocused) engine = feed.engine
        hud.setEngineLabel(engine)
        if (gpuName.isNotBlank()) hud.setGpuModel(gpuName)
        hud.setResolution(if (feed.outputWidth > 0 && feed.outputHeight > 0) buildString {
            append(feed.outputWidth).append('x').append(feed.outputHeight)
            if (feed.refreshMilliHz > 0) append(" @ ").append((feed.refreshMilliHz + 499) / 1000).append("Hz")
        } else "")
        val pid = feed.pid
        if (pid > 0 && pid != protonPid) {
            protonPid = pid
            proton = protonName(pid) ?: proton
        }
        hud.setWineVersion(proton)
        hud.setPresentedFps(presentedFps())
    }

    private fun presentedFps(): Float {
        if (FrameGen.mode(context).engine == FrameGen.ENGINE_OFF) return 0f
        val stats = runCatching { WaylandCompositor.nativeFrameGenStats() }.getOrNull() ?: return 0f
        return if (stats.size > 3 && stats[3] > 1f) stats[3] else 0f
    }

    // The HUD is clamped from where the user left it (wantX/wantY, set on a drop or restore), not from where
    // it is drawn: a window that shrinks and grows back (picture-in-picture, a fold) must not leave it in the
    // corner the small window pushed it to.
    private fun keepOnScreen(v: View) {
        val p = v.parent as? View ?: return
        if (p.width <= 0 || p.height <= 0 || v.width <= 0 || v.height <= 0) return
        if (wantX.isNaN()) { wantX = v.x; wantY = v.y }
        val maxX = (p.width - v.width).toFloat().coerceAtLeast(0f)
        val maxY = (p.height - v.height).toFloat().coerceAtLeast(0f)
        val x = wantX.coerceIn(0f, maxX)
        val y = wantY.coerceIn(0f, maxY)
        if (v.x != x) v.x = x
        if (v.y != y) v.y = y
    }

    companion object {
        /** True while the HUD is on screen; DroidDeck keeps sampling VRAM for it (GpuMemComponent). */
        @Volatile @JvmStatic var showing = false
            private set

        /** "Adreno (TM) 750" as the driver names it, "Adreno 750" as DroidDeck does elsewhere. */
        internal fun cleanGpuName(name: String): String = name.replace("(TM)", "").replace(Regex("\\s+"), " ").trim()

        /** The Proton build of a game process: the first STEAM_COMPAT_TOOL_PATHS entry's folder. */
        internal fun protonName(pid: Int): String? = runCatching {
            val environ = File("/proc/$pid/environ").readBytes().toString(Charsets.UTF_8).split('\u0000')
            val paths = environ.firstOrNull { it.startsWith("STEAM_COMPAT_TOOL_PATHS=") }?.substringAfter('=') ?: return null
            paths.split(':').firstOrNull { it.isNotBlank() }?.trimEnd('/')?.substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
