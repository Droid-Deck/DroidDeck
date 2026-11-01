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
    private var anchorX = Float.NaN
    private var snaps = true
    private var snapping = false
    private var previewFade: ValueAnimator? = null
    private var flingX = 0f
    private var engine = ""
    private var gpuName = ""
    private var protonPid = 0
    private var proton = ""
    @Volatile private var lastWindow: String? = null

    var onPresentingWindowChanged: (() -> Unit)? = null

    val liveFps: Float? get() = if (polling) feed.stats.fps else null

    var onConfigChanged: (() -> Unit)? = null

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

    fun attach(root: FrameLayout, index: Int) {
        if (view != null) return
        val hud = DroidDeckHudView(root.context)
        hud.setFrameStats(feed.stats)
        hud.setMangoReadings(readings)
        hud.setGpuModel(cleanGpuName(GpuInfo.detect().displayName(context)))
        hud.onRefresh = Runnable { refreshLabels(hud) }
        hud.setOnDraggedListener { rawX, rawY ->
            anchorX = Float.NaN; snapping = false
            previewFade?.cancel(); previewFade = null
            stopMotion(hud)
            shrinkPastEdge(hud, rawX, rawY)
        }
        hud.setOnFlungListener { vx, _ -> flingX = vx }
        hud.setOnTabDraggedListener { x, y -> dragTab(hud, x, y) }
        hud.setOnTabDroppedListener { dropTab(hud) }
        hud.setOnTabDragCancelledListener { snapping = false; keepOnScreen(hud) }
        hud.setOnMovedListener { x, y ->
            wantX = x; wantY = y
            val vx = flingX
            flingX = 0f
            dockEdgeFor(hud)?.let { edge -> setDock(hud, edge); return@setOnMovedListener }
            stopMotion(hud)
            fadeDockPreview(hud)
            if (snaps && !hud.isStrip) { snapToAnchor(hud, vx); return@setOnMovedListener }
            val fromX = hud.x; val fromY = hud.y
            keepOnScreen(hud)
            wantX = hud.x; wantY = hud.y
            SessionPrefs.setDroidDeckHudPosition(context, hud.x, hud.y)
            glideFrom(hud, fromX, fromY)
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
        if (!snaps) anchorX = Float.NaN
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

    fun setHidden(hide: Boolean) {
        hidden = hide
        applyShown()
    }

    fun setBackgrounded(stopped: Boolean) {
        backgrounded = stopped
        applyShown()
    }

    private fun applyShown() {
        if (!running) return
        val show = !hidden && !backgrounded
        view?.visibility = if (show) View.VISIBLE else View.GONE
        showing = show
        if (show != polling) {
            polling = show
            if (show) Choreographer.getInstance().postFrameCallback(frameCallback)
            else Choreographer.getInstance().removeFrameCallback(frameCallback)
        }
    }

    fun exportDiagnostics(): File? = runCatching {
        val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), "DroidDeck")
        dir.mkdirs()
        val stamp = java.text.SimpleDateFormat("yyyyMMdd-HHmmss", java.util.Locale.US).format(java.util.Date())
        val file = File(dir, "HUD-diagnostics-$stamp.txt")
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

    private fun dockEdgeFor(v: View): DroidDeckHudDock? {
        val over = overhang(v) ?: return null
        return over.key.takeIf { over.value >= DOCK_OVERHANG }
    }

    private fun overhang(v: View): Map.Entry<DroidDeckHudDock, Float>? {
        val p = v.parent as? View ?: return null
        if (p.width <= 0 || p.height <= 0 || v.width <= 0 || v.height <= 0) return null
        val strip = (v as? DroidDeckHudView)?.isStrip == true
        val density = v.resources.displayMetrics.density
        val w = if (strip) minOf(v.width.toFloat(), STRIP_PULL_ACROSS_DP * density) else v.width.toFloat()
        val h = if (strip) maxOf(v.height.toFloat(), STRIP_PULL_OFF_DP * density) else v.height.toFloat()
        return mapOf(
            DroidDeckHudDock.LEFT to -v.x / w,
            DroidDeckHudDock.RIGHT to (v.x + v.width - p.width) / w,
            DroidDeckHudDock.TOP to -v.y / h,
            DroidDeckHudDock.BOTTOM to (v.y + v.height - p.height) / h,
        ).maxByOrNull { it.value }
    }

    private fun dragTab(hud: DroidDeckHudView, x: Float, y: Float) {
        val p = hud.parent as? View ?: return
        stopMotion(hud)
        snapping = false
        hud.x = x.coerceIn(0f, (p.width - hud.width).toFloat().coerceAtLeast(0f))
        hud.y = y.coerceIn(0f, (p.height - hud.height).toFloat().coerceAtLeast(0f))
    }

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

    private fun stopMotion(hud: DroidDeckHudView) {
        hud.animate().setUpdateListener(null)
        hud.animate().cancel()
    }

    private fun glideFrom(hud: DroidDeckHudView, fromX: Float, fromY: Float) {
        if (Motion.scale == 0f || (fromX == hud.x && fromY == hud.y && hud.scaleX == 1f && hud.scaleY == 1f)) {
            hud.scaleX = 1f; hud.scaleY = 1f
            return
        }
        val toX = hud.x; val toY = hud.y
        hud.x = fromX; hud.y = fromY
        snapping = true
        hud.animate().x(toX).y(toY).scaleX(1f).scaleY(1f).setDuration(Motion.ms(220).toLong()).setInterpolator(EASE)
            .withEndAction { snapping = false; keepOnScreen(hud) }.start()
    }

    private fun fadeDockPreview(hud: DroidDeckHudView) {
        val from = hud.dockPreviewBlend
        if (from <= 0f) return
        val edge = overhang(hud)?.key ?: DroidDeckHudDock.NONE
        if (Motion.scale == 0f) { hud.setDockPreview(DroidDeckHudDock.NONE, 0f); return }
        previewFade?.cancel()
        previewFade = ValueAnimator.ofFloat(from, 0f).apply {
            duration = Motion.ms(200).toLong()
            addUpdateListener { hud.setDockPreview(edge, it.animatedValue as Float) }
            start()
        }
    }

    private fun snapToAnchor(hud: DroidDeckHudView, vx: Float) {
        val p = hud.parent as? View ?: return
        val maxX = (p.width - hud.width).toFloat().coerceAtLeast(0f)
        val maxY = (p.height - hud.height).toFloat().coerceAtLeast(0f)
        anchorX = if (hud.x + vx * FLING_REACH_S < maxX / 2f) 0f else 1f
        wantX = anchorX * maxX
        wantY = hud.y.coerceIn(0f, maxY)
        SessionPrefs.setDroidDeckHudPosition(context, wantX, wantY)
        stopMotion(hud)
        if (Motion.scale == 0f) {
            hud.scaleX = 1f; hud.scaleY = 1f
            hud.x = wantX; hud.y = wantY
            return
        }
        snapping = true
        hud.animate().x(wantX).y(wantY).scaleX(1f).scaleY(1f).setDuration(Motion.ms(260).toLong()).setInterpolator(EASE)
            .withEndAction { snapping = false; keepOnScreen(hud) }.start()
    }

    private fun shrinkPastEdge(hud: DroidDeckHudView, rawX: Float, rawY: Float) {
        if (hud.dock != DroidDeckHudDock.NONE) return
        val p = hud.parent as? View ?: return
        val edge = overhang(hud) ?: return
        val over = edge.value
        val t = (over / DOCK_OVERHANG).coerceIn(0f, 1f)
        val fade = ((t - PREVIEW_FROM) / (1f - PREVIEW_FROM)).coerceIn(0f, 1f)
        hud.setDockPreview(edge.key, fade * fade * (3f - 2f * fade))
        val (tabW, tabH) = hud.dockTabSize()
        val e = t * t * (3f - 2f * t)
        val at = IntArray(2).also { p.getLocationOnScreen(it) }
        hud.pivotX = rawX - at[0] - hud.x
        hud.pivotY = rawY - at[1] - hud.y
        if (hud.isStrip) {
            hud.scaleX = 1f + (tabW / hud.width - 1f) * e
            hud.scaleY = 1f
            return
        }
        val tabScale = maxOf(tabW / hud.width, tabH / hud.height).coerceAtMost(1f)
        val s = 1f + (tabScale - 1f) * e
        hud.scaleX = s; hud.scaleY = s
    }

    private fun setDock(hud: DroidDeckHudView, edge: DroidDeckHudDock) {
        if (edge == hud.dock) return
        animateDock(hud, edge) { commitDock(hud, edge) }
    }

    private fun commitDock(hud: DroidDeckHudView, edge: DroidDeckHudDock) {
        val tabX = hud.x; val tabY = hud.y
        if (edge != DroidDeckHudDock.NONE) {
            val p = hud.parent as? View
            if (p != null) {
                wantX = wantX.coerceIn(0f, p.width.toFloat())
                wantY = wantY.coerceIn(0f, p.height.toFloat())
            }
            SessionPrefs.setDroidDeckHudPosition(context, wantX, wantY)
        }
        DroidDeckHudConfig.set(context, "hudDock", edge.token)
        applyConfig()
        if (edge == DroidDeckHudDock.NONE) settleOutOfTab(hud, tabX, tabY)
        hud.post { keepOnScreen(hud) }
        onConfigChanged?.invoke()
    }

    private fun settleOutOfTab(hud: DroidDeckHudView, tabX: Float, tabY: Float) {
        val p = hud.parent as? View
        val maxX = if (p == null) Float.MAX_VALUE else (p.width - hud.contentWidth).coerceAtLeast(0f)
        val maxY = if (p == null) Float.MAX_VALUE else (p.height - hud.contentHeight).coerceAtLeast(0f)
        wantX = tabX.coerceIn(0f, maxX); wantY = tabY.coerceIn(0f, maxY)
        anchorX = if (snaps && !hud.isStrip && p != null) (if (wantX < maxX / 2f) 0f else 1f) else Float.NaN
        if (!anchorX.isNaN()) wantX = anchorX * maxX
        SessionPrefs.setDroidDeckHudPosition(context, wantX, wantY)
    }

    private fun animateDock(hud: DroidDeckHudView, edge: DroidDeckHudDock, commit: () -> Unit) {
        stopMotion(hud)
        previewFade?.cancel(); previewFade = null
        snapping = false
        hud.alpha = 1f
        val p = hud.parent as? View
        if (Motion.scale == 0f || p == null || !hud.isLaidOut || hud.width <= 0) {
            hud.scaleX = 1f; hud.scaleY = 1f
            hud.setDockPreview(DroidDeckHudDock.NONE, 0f)
            commit(); return
        }
        if (edge != DroidDeckHudDock.NONE) {
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

    private fun pivotFor(from: Float, to: Float, scale: Float): Float =
        if (scale > 0.999f) from else (to - from * scale) / (1f - scale)

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

    private fun keepOnScreen(v: View) {
        if (snapping) return
        val p = v.parent as? View ?: return
        if (p.width <= 0 || p.height <= 0 || v.width <= 0 || v.height <= 0) return
        if (wantX.isNaN()) { wantX = v.x; wantY = v.y }
        val maxX = (p.width - v.width).toFloat().coerceAtLeast(0f)
        val maxY = (p.height - v.height).toFloat().coerceAtLeast(0f)
        val anchored = (v as? DroidDeckHudView)?.let { it.dock == DroidDeckHudDock.NONE && !it.isStrip } == true
        if (snaps && anchored && anchorX.isNaN()) {
            anchorX = if (wantX.coerceIn(0f, maxX) < maxX / 2f) 0f else 1f
        }
        if (snaps && anchored && !anchorX.isNaN()) wantX = anchorX * maxX
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
        private const val DOCK_OVERHANG = 0.4f
        private const val FLING_REACH_S = 0.18f
        private const val PREVIEW_FROM = 0.55f
        private const val STRIP_PULL_ACROSS_DP = 600f
        private const val STRIP_PULL_OFF_DP = 150f
        private const val EXPAND_FADE = 0.5f
        private val EASE = PathInterpolator(0.2f, 0.8f, 0.2f, 1f)

        @Volatile @JvmStatic var showing = false
            private set

        internal fun cleanGpuName(name: String): String = name.replace("(TM)", "").replace(Regex("\\s+"), " ").trim()

        internal fun protonName(pid: Int): String? = runCatching {
            val environ = File("/proc/$pid/environ").readBytes().toString(Charsets.UTF_8).split('\u0000')
            val paths = environ.firstOrNull { it.startsWith("STEAM_COMPAT_TOOL_PATHS=") }?.substringAfter('=') ?: return null
            paths.split(':').firstOrNull { it.isNotBlank() }?.trimEnd('/')?.substringAfterLast('/')
        }.getOrNull()?.takeIf { it.isNotBlank() }
    }
}
