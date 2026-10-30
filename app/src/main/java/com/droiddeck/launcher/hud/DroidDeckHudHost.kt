package com.droiddeck.launcher.hud

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.view.Choreographer
import android.view.View
import android.view.animation.PathInterpolator
import android.view.ViewGroup
import android.widget.FrameLayout
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.Motion
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import kotlin.math.roundToInt

/**
 * DroidDeck HUD on the session screen, in place of the old text HUD: the view, the frame feed from gamescope
 * (MangoHud's frame times) and MangoHud's readings, started with the session's first frame.
 */
class DroidDeckHudHost(context: Context) {
    private val context = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val feed = GameFrameFeed(File(LinuxRuntime.rootDir(this.context), HudFrames.PATH))
    private val readings = MangoReadings(File(this.context.filesDir, "session"), LinuxRuntime.rootDir(this.context))
    private var view: DroidDeckHudView? = null
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
            if (!gpuName.isNullOrBlank()) handler.post { this@DroidDeckHudHost.gpuName = cleanGpuName(gpuName) }
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
        val hud = DroidDeckHudView(root.context)
        hud.setFrameStats(feed.stats)
        hud.setMangoReadings(readings)
        hud.setGpuModel(cleanGpuName(GpuInfo.detect().displayName(context)))
        hud.onRefresh = Runnable { refreshLabels(hud) }
        hud.setOnMovedListener { x, y ->
            wantX = x; wantY = y
            dockEdgeFor(hud)?.let { edge -> setDock(hud, edge); return@setOnMovedListener }
            keepOnScreen(hud)
            wantX = hud.x; wantY = hud.y
            SessionPrefs.setDroidDeckHudPosition(context, hud.x, hud.y)
        }
        hud.setOnSizeCycledListener { size -> DroidDeckHudConfig.set(context, "hudSize", size); onConfigChanged?.invoke() }
        hud.setOnDockChangedListener { token -> setDock(hud, DroidDeckHudDock.from(token)) }
        hud.setOnScaledListener { percent ->
            DroidDeckHudConfig.set(context, "hudScale", percent.toString())
            onConfigChanged?.invoke()
        }
        hud.setOnLockChangedListener { locked ->
            DroidDeckHudConfig.set(context, "hudLocked", if (locked) "1" else "0")
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
        SessionPrefs.droidDeckHudPosition(context)?.let { (x, y) -> wantX = x; wantY = y; hud.post { keepOnScreen(hud) } }
        view = hud
        parent = root
        applyConfig()
    }

    fun applyConfig() {
        view?.applyConfig(DroidDeckHudConfig.encode(DroidDeckHudConfig.load(context)))
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

    /** A sensor report (DroidDeck HUD's diagnostics) in Downloads/DroidDeck; null when it can't be written. */
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

    private fun refreshLabels(hud: DroidDeckHudView) {
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

    /** The edge the HUD was dropped over, once a good part of it hangs past that edge; null when it stays. */
    private fun dockEdgeFor(v: View): DroidDeckHudDock? {
        val p = v.parent as? View ?: return null
        if (p.width <= 0 || p.height <= 0 || v.width <= 0 || v.height <= 0) return null
        val over = mapOf(
            DroidDeckHudDock.LEFT to -v.x / v.width,
            DroidDeckHudDock.RIGHT to (v.x + v.width - p.width) / v.width,
            DroidDeckHudDock.TOP to -v.y / v.height,
            DroidDeckHudDock.BOTTOM to (v.y + v.height - p.height) / v.height,
        ).maxByOrNull { it.value } ?: return null
        return over.key.takeIf { over.value >= DOCK_OVERHANG }
    }

    /** Tucks the HUD against [edge] as its FPS tab, or with [DroidDeckHudDock.NONE] brings it back out from where it was docked. */
    private fun setDock(hud: DroidDeckHudView, edge: DroidDeckHudDock) {
        if (edge == hud.dock) return
        animateDock(hud, edge) { commitDock(hud, edge) }
    }

    private fun commitDock(hud: DroidDeckHudView, edge: DroidDeckHudDock) {
        if (edge == DroidDeckHudDock.NONE) {
            when (hud.dock) {
                DroidDeckHudDock.LEFT -> wantX = 0f
                DroidDeckHudDock.RIGHT -> wantX = Float.MAX_VALUE
                DroidDeckHudDock.TOP -> wantY = 0f
                DroidDeckHudDock.BOTTOM -> wantY = Float.MAX_VALUE
                DroidDeckHudDock.NONE -> {}
            }
        } else {
            val p = hud.parent as? View
            if (p != null) {
                wantX = wantX.coerceIn(0f, p.width.toFloat())
                wantY = wantY.coerceIn(0f, p.height.toFloat())
            }
            SessionPrefs.setDroidDeckHudPosition(context, wantX, wantY)
        }
        DroidDeckHudConfig.set(context, "hudDock", edge.token)
        applyConfig()
        hud.post { keepOnScreen(hud) }
        onConfigChanged?.invoke()
    }

    /**
     * The HUD shrinks toward the screen edge it docks to and the tab grows out of that edge; undocking plays
     * it the other way. [commit] swaps the layout while the view is invisible between the two halves. Timing
     * and curve are the launcher's own ([Motion]), and off when animations are off.
     */
    private fun animateDock(hud: DroidDeckHudView, edge: DroidDeckHudDock, commit: () -> Unit) {
        hud.animate().cancel()
        hud.scaleX = 1f; hud.scaleY = 1f; hud.alpha = 1f
        val p = hud.parent as? View
        if (Motion.scale == 0f || p == null || !hud.isLaidOut || hud.width <= 0) { commit(); return }
        val side = if (edge == DroidDeckHudDock.NONE) hud.dock else edge
        val cx = hud.x + hud.width / 2f
        val cy = hud.y + hud.height / 2f
        fun pivotToward(v: View) {
            when (side) {
                DroidDeckHudDock.LEFT -> { v.pivotX = -v.x; v.pivotY = cy - v.y }
                DroidDeckHudDock.RIGHT -> { v.pivotX = p.width - v.x; v.pivotY = cy - v.y }
                DroidDeckHudDock.TOP -> { v.pivotX = cx - v.x; v.pivotY = -v.y }
                DroidDeckHudDock.BOTTOM -> { v.pivotX = cx - v.x; v.pivotY = p.height - v.y }
                DroidDeckHudDock.NONE -> {}
            }
        }
        pivotToward(hud)
        hud.animate().scaleX(TUCKED).scaleY(TUCKED).alpha(0f)
            .setDuration(Motion.ms(150).toLong()).setInterpolator(EXIT)
            .withEndAction {
                commit()
                // The new layout is not measured yet; its size is known from the rebuild, and the edge it grows
                // from is the view's own edge, so there is no frame to wait for.
                val w = hud.contentWidth; val h = hud.contentHeight
                placeNow(hud, p, w, h)
                hud.pivotX = when (side) {
                    DroidDeckHudDock.LEFT -> 0f
                    DroidDeckHudDock.RIGHT -> w
                    else -> (cx - hud.x).coerceIn(0f, w)
                }
                hud.pivotY = when (side) {
                    DroidDeckHudDock.TOP -> 0f
                    DroidDeckHudDock.BOTTOM -> h
                    else -> (cy - hud.y).coerceIn(0f, h)
                }
                hud.scaleX = TUCKED; hud.scaleY = TUCKED; hud.alpha = 0f
                hud.animate().scaleX(1f).scaleY(1f).alpha(1f)
                    .setDuration(Motion.ms(360).toLong()).setInterpolator(EASE).start()
            }.start()
    }

    /** Puts the HUD where [keepOnScreen] will settle it once its new size is laid out, so it does not start the animation off screen. */
    private fun placeNow(v: DroidDeckHudView, p: View, w: Float, h: Float) {
        val maxX = (p.width - w).coerceAtLeast(0f); val maxY = (p.height - h).coerceAtLeast(0f)
        var x = wantX.coerceIn(0f, maxX); var y = wantY.coerceIn(0f, maxY)
        when (v.dock) {
            DroidDeckHudDock.LEFT -> x = 0f
            DroidDeckHudDock.RIGHT -> x = maxX
            DroidDeckHudDock.TOP -> y = 0f
            DroidDeckHudDock.BOTTOM -> y = maxY
            DroidDeckHudDock.NONE -> {}
        }
        v.x = x; v.y = y
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
        var x = wantX.coerceIn(0f, maxX)
        var y = wantY.coerceIn(0f, maxY)
        when (v.let { it as? DroidDeckHudView }?.dock) {
            DroidDeckHudDock.LEFT -> x = 0f
            DroidDeckHudDock.RIGHT -> x = maxX
            DroidDeckHudDock.TOP -> y = 0f
            DroidDeckHudDock.BOTTOM -> y = maxY
            else -> {}
        }
        if (v.x != x) v.x = x
        if (v.y != y) v.y = y
    }

    companion object {
        /** How much of the HUD, as a share of its own size, must hang past a screen edge for it to dock there. */
        private const val DOCK_OVERHANG = 0.4f
        private const val TUCKED = 0.3f
        private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)
        // The way out speeds up so the HUD stays in view until the way back in takes over.
        private val EXIT = PathInterpolator(0.4f, 0f, 1f, 1f)

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
