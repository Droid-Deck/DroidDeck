package com.droiddeck.launcher.hud

import android.content.Context
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.animation.ValueAnimator
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
    // With snapping on, the HUD keeps to a corner or an edge middle: these are its share of the free space across
    // and down (0, 0.5 or 1), so it stays there when its size changes. NaN = not snapped.
    private var anchorX = Float.NaN
    private var anchorY = Float.NaN
    private var snaps = true
    private var snapping = false
    private var flingX = 0f
    private var flingY = 0f
    private var engine = ""
    private var gpuName = ""
    private var protonPid = 0
    private var proton = ""
    @Volatile private var lastWindow: String? = null

    var onPresentingWindowChanged: (() -> Unit)? = null

    /** The frame rate the HUD shows; null while the HUD is off and not counting frames. */
    val liveFps: Float? get() = if (polling) feed.stats.fps else null

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
        hud.setOnDraggedListener { rawX, rawY ->
            anchorX = Float.NaN; anchorY = Float.NaN; snapping = false
            shrinkPastEdge(hud, rawX, rawY)
        }
        hud.setOnFlungListener { vx, vy -> flingX = vx; flingY = vy }
        hud.setOnTabDraggedListener { x, y -> dragTab(hud, x, y) }
        hud.setOnTabDroppedListener { dropTab(hud) }
        hud.setOnMovedListener { x, y ->
            wantX = x; wantY = y
            val vx = flingX; val vy = flingY
            flingX = 0f; flingY = 0f
            if (!hud.isStrip) dockEdgeFor(hud)?.let { edge -> setDock(hud, edge); return@setOnMovedListener }
            fadeDockPreview(hud)
            if (snaps && !hud.isStrip) { snapToAnchor(hud, vx, vy); return@setOnMovedListener }
            if (hud.scaleX != 1f) hud.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ms(200).toLong()).setInterpolator(EASE).start()
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
        val config = DroidDeckHudConfig.load(context)
        snaps = config["hudSnap"] != "0"
        if (!snaps) { anchorX = Float.NaN; anchorY = Float.NaN }
        view?.applyConfig(DroidDeckHudConfig.encode(config))
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
        val over = overhang(v) ?: return null
        return over.key.takeIf { over.value >= DOCK_OVERHANG }
    }

    /** The edge the HUD hangs furthest past, with how much of it does, as a share of its own size. */
    private fun overhang(v: View): Map.Entry<DroidDeckHudDock, Float>? {
        val p = v.parent as? View ?: return null
        if (p.width <= 0 || p.height <= 0 || v.width <= 0 || v.height <= 0) return null
        return mapOf(
            DroidDeckHudDock.LEFT to -v.x / v.width,
            DroidDeckHudDock.RIGHT to (v.x + v.width - p.width) / v.width,
            DroidDeckHudDock.TOP to -v.y / v.height,
            DroidDeckHudDock.BOTTOM to (v.y + v.height - p.height) / v.height,
        ).maxByOrNull { it.value }
    }

    /** The closest of the corners and edge middles to (x, y), as shares of the free space (maxX, maxY). */
    private fun nearestAnchor(x: Float, y: Float, maxX: Float, maxY: Float): Pair<Float, Float> {
        val spots = listOf(0f, 0.5f, 1f).flatMap { ax -> listOf(0f, 0.5f, 1f).map { ay -> ax to ay } }
            .filter { it != 0.5f to 0.5f }
        return spots.minBy { (ax, ay) -> (ax * maxX - x).let { it * it } + (ay * maxY - y).let { it * it } }
    }

    /** The tab follows the finger along its edge and away from it, staying on screen. */
    private fun dragTab(hud: DroidDeckHudView, x: Float, y: Float) {
        val p = hud.parent as? View ?: return
        hud.animate().cancel()
        hud.x = x.coerceIn(0f, (p.width - hud.width).toFloat().coerceAtLeast(0f))
        hud.y = y.coerceIn(0f, (p.height - hud.height).toFloat().coerceAtLeast(0f))
    }

    /** A tab pulled out by more than its own size comes out as the HUD there; a shorter pull slides it back onto its edge. */
    private fun dropTab(hud: DroidDeckHudView) {
        val p = hud.parent as? View ?: return
        val maxX = (p.width - hud.width).toFloat().coerceAtLeast(0f)
        val maxY = (p.height - hud.height).toFloat().coerceAtLeast(0f)
        val pulled = when (hud.dock) {
            DroidDeckHudDock.LEFT -> hud.x / hud.width
            DroidDeckHudDock.RIGHT -> (maxX - hud.x) / hud.width
            DroidDeckHudDock.TOP -> hud.y / hud.height
            DroidDeckHudDock.BOTTOM -> (maxY - hud.y) / hud.height
            DroidDeckHudDock.NONE -> 0f
        }
        if (pulled >= 1f) { setDock(hud, DroidDeckHudDock.NONE); return }
        wantX = hud.x; wantY = hud.y
        val (x, y) = settled(p, hud.dock, hud.width.toFloat(), hud.height.toFloat())
        wantX = x; wantY = y
        SessionPrefs.setDroidDeckHudPosition(context, x, y)
        if (Motion.scale == 0f) { hud.x = x; hud.y = y; return }
        snapping = true
        hud.animate().x(x).y(y).setDuration(Motion.ms(220).toLong()).setInterpolator(EASE)
            .withEndAction { snapping = false }.start()
    }

    /** A drop short of docking fades the tab preview back out. */
    private fun fadeDockPreview(hud: DroidDeckHudView) {
        val from = hud.dockPreviewBlend
        if (from <= 0f) return
        val edge = overhang(hud)?.key ?: DroidDeckHudDock.NONE
        if (Motion.scale == 0f) { hud.setDockPreview(DroidDeckHudDock.NONE, 0f); return }
        ValueAnimator.ofFloat(from, 0f).apply {
            duration = Motion.ms(200).toLong()
            addUpdateListener { hud.setDockPreview(edge, it.animatedValue as Float) }
            start()
        }
    }

    /**
     * Dropped, the HUD glides to the nearest corner or edge middle as a picture-in-picture
     * window does, carried toward where a fling throws it.
     */
    private fun snapToAnchor(hud: DroidDeckHudView, vx: Float, vy: Float) {
        val p = hud.parent as? View ?: return
        val maxX = (p.width - hud.width).toFloat().coerceAtLeast(0f)
        val maxY = (p.height - hud.height).toFloat().coerceAtLeast(0f)
        val (ax, ay) = nearestAnchor(hud.x + vx * FLING_REACH_S, hud.y + vy * FLING_REACH_S, maxX, maxY)
        anchorX = ax; anchorY = ay
        wantX = anchorX * maxX; wantY = anchorY * maxY
        SessionPrefs.setDroidDeckHudPosition(context, wantX, wantY)
        hud.animate().cancel()
        if (Motion.scale == 0f) {
            hud.scaleX = 1f; hud.scaleY = 1f
            hud.x = wantX; hud.y = wantY
            return
        }
        snapping = true
        hud.animate().x(wantX).y(wantY).scaleX(1f).scaleY(1f).setDuration(Motion.ms(260).toLong()).setInterpolator(EASE)
            .withEndAction { snapping = false; keepOnScreen(hud) }.start()
    }

    /**
     * Dragged past an edge, the HUD shrinks around the finger toward the tab it docks as, reaching the tab's
     * size where a drop would dock it, so the drop finishes a contraction the drag already started.
     */
    private fun shrinkPastEdge(hud: DroidDeckHudView, rawX: Float, rawY: Float) {
        if (hud.dock != DroidDeckHudDock.NONE || hud.isStrip) return
        val p = hud.parent as? View ?: return
        val edge = overhang(hud) ?: return
        val over = edge.value
        val t = (over / DOCK_OVERHANG).coerceIn(0f, 1f)
        val fade = ((t - PREVIEW_FROM) / (1f - PREVIEW_FROM)).coerceIn(0f, 1f)
        hud.setDockPreview(edge.key, fade * fade * (3f - 2f * fade))
        val (tabW, tabH) = hud.dockTabSize()
        val tabScale = maxOf(tabW / hud.width, tabH / hud.height).coerceAtMost(1f)
        val s = 1f + (tabScale - 1f) * t * t * (3f - 2f * t)
        val at = IntArray(2).also { p.getLocationOnScreen(it) }
        hud.pivotX = rawX - at[0] - hud.x
        hud.pivotY = rawY - at[1] - hud.y
        hud.scaleX = s; hud.scaleY = s
    }

    /** Tucks the HUD against [edge] as its FPS tab, or with [DroidDeckHudDock.NONE] brings it back out from where it was docked. */
    private fun setDock(hud: DroidDeckHudView, edge: DroidDeckHudDock) {
        if (edge == hud.dock) return
        animateDock(hud, edge) { commitDock(hud, edge) }
    }

    private fun commitDock(hud: DroidDeckHudView, edge: DroidDeckHudDock) {
        if (edge == DroidDeckHudDock.NONE) {
            // Out where the tab is: against its edge, or wherever it was pulled to.
            wantX = hud.x; wantY = hud.y
            anchorX = Float.NaN; anchorY = Float.NaN
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
     * One shape contracts into the tab, and the tab expands back out to the HUD: the view is scaled onto the
     * tab's exact rectangle, and the layouts swap at that same size and place. Timing and curve are the
     * launcher's own ([Motion]), and off when animations are off.
     */
    private fun animateDock(hud: DroidDeckHudView, edge: DroidDeckHudDock, commit: () -> Unit) {
        hud.animate().cancel()
        snapping = false
        hud.alpha = 1f
        val p = hud.parent as? View
        if (Motion.scale == 0f || p == null || !hud.isLaidOut || hud.width <= 0) {
            hud.scaleX = 1f; hud.scaleY = 1f
            hud.setDockPreview(DroidDeckHudDock.NONE, 0f)
            commit(); return
        }
        if (edge != DroidDeckHudDock.NONE) {
            // Continue from what the drag left on screen: the shrunk HUD, and the tab where it is along the edge.
            val left = hud.x + hud.pivotX * (1f - hud.scaleX); val top = hud.y + hud.pivotY * (1f - hud.scaleY)
            val shownW = hud.width * hud.scaleX; val shownH = hud.height * hud.scaleY
            hud.pivotX = 0f; hud.pivotY = 0f
            hud.x = left; hud.y = top
            val (tabW, tabH) = hud.dockTabSize()
            wantX = left + (shownW - tabW) / 2f
            wantY = top + (shownH - tabH) / 2f
            val (tabX, tabY) = settled(p, edge, tabW, tabH)
            val sx = tabW / hud.width; val sy = tabH / hud.height
            val from = hud.dockPreviewBlend
            hud.animate().x(tabX).y(tabY).scaleX(sx).scaleY(sy).setDuration(Motion.ms(220).toLong()).setInterpolator(EASE)
                .setUpdateListener { hud.setDockPreview(edge, from + (1f - from) * it.animatedFraction) }
                .withEndAction {
                    hud.animate().setUpdateListener(null)
                    commit()
                    hud.setDockPreview(DroidDeckHudDock.NONE, 0f)
                    hud.scaleX = 1f; hud.scaleY = 1f
                    hud.x = tabX; hud.y = tabY
                }.start()
        } else {
            hud.scaleX = 1f; hud.scaleY = 1f
            val was = hud.dock
            val tabX = hud.x; val tabY = hud.y
            val tabW = hud.width.toFloat(); val tabH = hud.height.toFloat()
            commit()
            val w = hud.contentWidth; val h = hud.contentHeight
            val (x, y) = settled(p, DroidDeckHudDock.NONE, w, h)
            hud.x = x; hud.y = y
            val sx = tabW / w; val sy = tabH / h
            hud.pivotX = pivotFor(x, tabX, sx) - x
            hud.pivotY = pivotFor(y, tabY, sy) - y
            hud.scaleX = sx; hud.scaleY = sy
            hud.setDockPreview(was, 1f)
            hud.animate().scaleX(1f).scaleY(1f).setDuration(Motion.ms(360).toLong()).setInterpolator(EASE)
                .setUpdateListener {
                    val f = (1f - it.animatedFraction / EXPAND_FADE).coerceIn(0f, 1f)
                    hud.setDockPreview(was, f * f * (3f - 2f * f))
                }
                .withEndAction { hud.animate().setUpdateListener(null); hud.setDockPreview(DroidDeckHudDock.NONE, 0f) }
                .start()
        }
    }

    /** The pivot, in parent coordinates, that scaling by [scale] takes a span starting at [from] onto one starting at [to]. */
    private fun pivotFor(from: Float, to: Float, scale: Float): Float =
        if (scale > 0.999f) from else (to - from * scale) / (1f - scale)

    /** Where a [w] x [h] HUD or tab sits once [keepOnScreen] has settled it against [dock]. */
    private fun settled(p: View, dock: DroidDeckHudDock, w: Float, h: Float): Pair<Float, Float> {
        val maxX = (p.width - w).coerceAtLeast(0f); val maxY = (p.height - h).coerceAtLeast(0f)
        var x = wantX.coerceIn(0f, maxX); var y = wantY.coerceIn(0f, maxY)
        when (dock) {
            DroidDeckHudDock.LEFT -> x = 0f
            DroidDeckHudDock.RIGHT -> x = maxX
            DroidDeckHudDock.TOP -> y = 0f
            DroidDeckHudDock.BOTTOM -> y = maxY
            DroidDeckHudDock.NONE -> {}
        }
        return Pair(x, y)
    }

    // The HUD is clamped from where the user left it (wantX/wantY, set on a drop or restore), not from where
    // it is drawn: a window that shrinks and grows back (picture-in-picture, a fold) must not leave it in the
    // corner the small window pushed it to.
    private fun keepOnScreen(v: View) {
        if (snapping) return
        val p = v.parent as? View ?: return
        if (p.width <= 0 || p.height <= 0 || v.width <= 0 || v.height <= 0) return
        if (wantX.isNaN()) { wantX = v.x; wantY = v.y }
        val maxX = (p.width - v.width).toFloat().coerceAtLeast(0f)
        val maxY = (p.height - v.height).toFloat().coerceAtLeast(0f)
        val anchored = (v as? DroidDeckHudView)?.let { it.dock == DroidDeckHudDock.NONE && !it.isStrip } == true
        if (snaps && anchored && anchorX.isNaN()) {
            val (ax, ay) = nearestAnchor(wantX.coerceIn(0f, maxX), wantY.coerceIn(0f, maxY), maxX, maxY)
            anchorX = ax; anchorY = ay
        }
        if (snaps && anchored && !anchorX.isNaN()) { wantX = anchorX * maxX; wantY = anchorY * maxY }
        var x = wantX.coerceIn(0f, maxX)
        var y = wantY.coerceIn(0f, maxY)
        if ((v as? DroidDeckHudView)?.isStrip == true) y = if (y + v.height / 2f < p.height / 2f) 0f else maxY
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
        /** How far ahead a fling carries the HUD when picking the spot it snaps to, as seconds of its speed. */
        private const val FLING_REACH_S = 0.18f
        /** How far toward docking a drag goes, as a share of the way, before the tab starts to fade in over the HUD. */
        private const val PREVIEW_FROM = 0.55f
        /** The share of the expand animation over which the tab fades out to the HUD. */
        private const val EXPAND_FADE = 0.5f
        private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

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
