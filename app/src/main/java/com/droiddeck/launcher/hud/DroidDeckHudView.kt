/*
 * FusionHUD — a lightweight in-game performance HUD for Android / Winlator overlays.
 * Copyright (C) The412Banner  <https://github.com/The412Banner>
 *
 * Licensed under the GNU General Public License v3.0 (see LICENSE), with an
 * ADDITIONAL attribution requirement under GPL-3.0 §7(b) (see ATTRIBUTION.md):
 * any use, fork, or distribution must preserve credit to "The412Banner" and a
 * link to https://github.com/The412Banner/FusionHUD in the project documentation
 * AND in the in-app credits/about screen.
 *
 * Ported to DroidDeck from FusionHUD (Bannerlator's HUD) and modified for DroidDeck.
 */

package com.droiddeck.launcher.hud

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BlurMaskFilter
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.Typeface
import android.os.SystemClock
import android.text.format.DateFormat
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.annotation.VisibleForTesting
import androidx.compose.ui.graphics.toArgb
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.ui.Motion
import com.droiddeck.launcher.ui.Palette
import com.droiddeck.launcher.ui.Themes
import java.util.Date
import java.util.Locale
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.IntConsumer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

class DroidDeckHudView(context: Context) : View(context) {

    private var frames: FrameStats? = null
    internal fun setFrameStats(stats: FrameStats?) { frames = stats }
    internal fun setMangoReadings(readings: MangoReadings?) { metrics.setMangoReadings(readings) }

    private var presentedFps = 0f
    fun setPresentedFps(fps: Float) { presentedFps = fps }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var updateJob: Job? = null
    private var hueJob: Job? = null
    private val metrics = HudMetrics(context)

    private val density = resources.displayMetrics.density

    private var size = DroidDeckHudSize.FULL
    private var scale = 1f
    private var bgOpacity = 0.8f
    private var outlineIntensity = 0.4f
    private var outlineFollowAccent = true
    private var colorIntensity = 1f
    private var tempDisplay = HudMetrics.TempDisplay.from(null)

    private var showFPS = true
    private var showEngine = true
    private var showGpuModel = true
    private var showCPU = true
    private var showGPU = true
    private var showCpuTemp = true
    private var showGpuTemp = false
    private var showVram = true
    private var showRAM = true
    private var showBattery = true
    private var showPower = true
    private var showBatteryTemp = false
    private var showGraph = false
    private var showLow001 = true
    private var fpsDecimal = true
    private var showClockTime = true
    private var showPerCore = true
    private var showSwap = true
    private var showNet = true
    private var showResolution = true
    private var showProton = true
    private var showWrapper = true
    private var showDxVer = true
    private var showSession = true

    private var engineLabel = ""
    private var displayServer = ""
    private var hdrState = DroidDeckHudHdr.NONE
    private var gpuModel = ""
    private var gpuSpoofName = ""
    private var wineVersion = ""
    private var graphicsWrapper = ""
    private var dxvkVersion = ""
    private var vkd3dVersion = ""

    private var snap: HudMetrics.Snapshot? = null
    private var fpsNow = 0f
    private var fpsAvg = 0f
    private var lows: FrameStats.Lows = FrameStats.Lows.EMPTY
    private var graphMinMs = 0f
    private var graphMaxMs = 0f
    private val graphBuf = FloatArray(FrameStats.GRAPH_FRAMES)
    private val graphPath = Path()
    private var resolution = ""

    private var accent = 0xFF1A9FFF.toInt()
    private var surfaceRgb = 0x121417
    private var headerMark: RectF? = null
    private var chipRect: RectF? = null

    var dock = DroidDeckHudDock.NONE
        private set
    private var tabRect: RectF? = null
    private var stripRect: RectF? = null
    private val tabPath = Path()

    private var dPanel: RectF? = null
    private var dRadius = 0f
    private var dDividerTop = -1f
    private var dFrame: Bitmap? = null
    private var dFrameKey = ""
    private val glowPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private val colGpu = 0xFF5EE08A.toInt()
    private val colCpu = 0xFF58A6FF.toInt()
    private val colVram = 0xFFC98BFF.toInt()
    private val colRam = 0xFFFF7BC0.toInt()
    private val colBat = 0xFFFFAB5E.toInt()
    private var colFps = 0xFF1A9FFF.toInt()
    private var colGraph = 0xFF5CB8FF.toInt()
    private val colValue = 0xFFF2F5F9.toInt()
    private val colDim = 0xFF9AA4B2.toInt()
    private val colLo = 0xFFE4E8EE.toInt()
    private val colDisp = 0xFF4DD0E1.toInt()
    private val colHdr = 0xFFFFD54F.toInt()

    private val measurePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val drawPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.MONOSPACE, Typeface.BOLD)
    }
    private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE }
    private val tilePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val graphPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND
        color = colGraph
    }
    private val headerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val wordmarkPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        typeface = Typeface.create(Typeface.SANS_SERIF, Typeface.BOLD)
    }

    private class Glyph(val x: Float, val baseline: Float, val text: String, val color: Int, val sizePx: Float, val sans: Boolean = false, val glow: Boolean = false) {
        fun moved(dx: Float, dy: Float) = Glyph(x + dx, baseline + dy, text, color, sizePx, sans, glow)
    }
    private class Span(val text: String, val color: Int, val sizePx: Float, val glow: Boolean = false)
    private class HudRow(val label: Span, val vals: List<Span>, val inline: Boolean = false)
    private val glyphs = ArrayList<Glyph>()
    private val tileRects = ArrayList<RectF>()
    private var graphRect: RectF? = null
    private var contentW = 0f
    private var contentH = 0f

    private var onMovedListener: BiConsumer<Float, Float>? = null
    private var onSizeCycledListener: Consumer<String>? = null
    private var onLockChangedListener: Consumer<Boolean>? = null
    fun setOnMovedListener(l: BiConsumer<Float, Float>?) { onMovedListener = l }
    fun setOnSizeCycledListener(l: Consumer<String>?) { onSizeCycledListener = l }
    fun setOnLockChangedListener(l: Consumer<Boolean>?) { onLockChangedListener = l }
    private var onDockChangedListener: Consumer<String>? = null
    private var onScaledListener: IntConsumer? = null
    private var onDraggedListener: BiConsumer<Float, Float>? = null
    fun setOnDraggedListener(l: BiConsumer<Float, Float>?) { onDraggedListener = l }
    private var onFlungListener: BiConsumer<Float, Float>? = null
    fun setOnFlungListener(l: BiConsumer<Float, Float>?) { onFlungListener = l }
    fun setOnDockChangedListener(l: Consumer<String>?) { onDockChangedListener = l }
    fun setOnScaledListener(l: IntConsumer?) { onScaledListener = l }

    private val lockController = HudLockController(context, this, object : HudLockController.Callbacks {
        override fun onTap() { cycleSize() }
        override fun onMoved(x: Float, y: Float) { onMovedListener?.accept(x, y) }
        override fun onDragged(rawX: Float, rawY: Float) { onDraggedListener?.accept(rawX, rawY) }
        override fun onFlung(velocityX: Float, velocityY: Float) { onFlungListener?.accept(velocityX, velocityY) }
        override fun onLockChanged(locked: Boolean) { onLockChangedListener?.accept(locked) }
        override fun onPinch(factor: Float) {
            val next = (scale * factor).coerceIn(MIN_SCALE, MAX_SCALE)
            if (next == scale) return
            scale = next
            rebuildAndInvalidate()
        }
        override fun onPinchEnd() { onScaledListener?.accept((scale * 100f).roundToInt()) }
    })

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var tabDownRawX = 0f
    private var tabDownRawY = 0f
    private var tabStartX = 0f
    private var tabStartY = 0f
    private var tabDragging = false
    private var onTabDraggedListener: BiConsumer<Float, Float>? = null
    private var onTabDroppedListener: Runnable? = null
    private var onTabDragCancelledListener: Runnable? = null
    fun setOnTabDraggedListener(l: BiConsumer<Float, Float>?) { onTabDraggedListener = l }
    fun setOnTabDroppedListener(l: Runnable?) { onTabDroppedListener = l }
    fun setOnTabDragCancelledListener(l: Runnable?) { onTabDragCancelledListener = l }

    private fun onTabTouch(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                tabDownRawX = event.rawX; tabDownRawY = event.rawY
                tabStartX = x; tabStartY = y
                tabDragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.rawX - tabDownRawX; val dy = event.rawY - tabDownRawY
                if (!tabDragging && dx * dx + dy * dy > touchSlop * touchSlop) tabDragging = true
                if (tabDragging) onTabDraggedListener?.accept(tabStartX + dx, tabStartY + dy)
            }
            MotionEvent.ACTION_UP -> {
                if (tabDragging) { tabDragging = false; onTabDroppedListener?.run() }
                else onDockChangedListener?.accept(DroidDeckHudDock.NONE.token)
            }
            MotionEvent.ACTION_CANCEL -> if (tabDragging) {
                tabDragging = false
                onTabDragCancelledListener?.run()
            }
        }
        return true
    }

    private var previewEdge = DroidDeckHudDock.NONE
    private var previewBlend = 0f
    internal fun setDockPreview(edge: DroidDeckHudDock, blend: Float) {
        val b = blend.coerceIn(0f, 1f)
        if (edge == previewEdge && b == previewBlend) return
        previewEdge = edge; previewBlend = b
        invalidate()
    }
    internal val dockPreviewBlend: Float get() = previewBlend

    fun setEngineLabel(s: String?) { if ((s ?: "") != engineLabel) { engineLabel = s ?: ""; post { rebuildAndInvalidate() } } }
    fun setGpuModel(s: String?) { if ((s ?: "") != gpuModel) { gpuModel = s ?: ""; post { rebuildAndInvalidate() } } }
    fun setWineVersion(s: String?) { if ((s ?: "") != wineVersion) { wineVersion = s ?: ""; post { rebuildAndInvalidate() } } }

    fun setResolution(s: String?) { resolution = s ?: "" }

    private val periodDone = Channel<Unit>(Channel.CONFLATED)
    private var seenFpsUpdates = 0L

    fun onFrames() {
        val updates = frames?.fpsUpdates ?: 0L
        if (updates != seenFpsUpdates) {
            seenFpsUpdates = updates
            periodDone.trySend(Unit)
        }
        if (graphRect != null) invalidate()
    }

    private fun cycleSize() {
        size = size.next()
        onSizeCycledListener?.accept(size.token)
        rebuildAndInvalidate()
    }

    fun applyConfig(configString: String?) {
        if (configString.isNullOrEmpty()) return
        val cfg = KeyValueSet(configString)

        size = DroidDeckHudSize.from(cfg.get("hudSize", "minimal"))
        showFPS = cfg.get("showFPS", "1") == "1"
        showEngine = cfg.get("showEngine", "1") == "1"
        showGpuModel = cfg.get("showGpuModel", "1") == "1"
        showCPU = cfg.get("showCPUUsage", cfg.get("showCPULoad", "1")) == "1"
        showGPU = cfg.get("showGPULoad", "1") == "1"
        showCpuTemp = cfg.get("showTemp", "1") == "1"
        showGpuTemp = cfg.get("showGpuTemp", "0") == "1"
        showVram = cfg.get("showVram", "1") == "1"
        showRAM = cfg.get("showRAM", "1") == "1"
        showBattery = cfg.get("showBattery", "1") == "1"
        showPower = cfg.get("showPower", "1") == "1"
        showBatteryTemp = cfg.get("showBatteryTemp", "0") == "1"
        showGraph = cfg.get("showFPSGraph", "0") == "1"
        showLow001 = cfg.get("showLow001", "1") == "1"
        fpsDecimal = cfg.get("fpsDecimal", "1") == "1"
        showClockTime = cfg.get("showClock", "1") == "1"
        showPerCore = cfg.get("showPerCore", "1") == "1"
        showSwap = cfg.get("showSwap", "1") == "1"
        showNet = cfg.get("showNet", "1") == "1"
        showResolution = cfg.get("showResolution", "1") == "1"
        showProton = cfg.get("showProton", "1") == "1"
        showWrapper = cfg.get("showWrapper", "1") == "1"
        showDxVer = cfg.get("showDxVer", "1") == "1"
        showSession = cfg.get("showSession", "1") == "1"
        tempDisplay = HudMetrics.TempDisplay.from(cfg)
        lockController.setLocked(cfg.get("hudLocked", "1") == "1")
        dock = DroidDeckHudDock.from(cfg.get("hudDock", "none"))

        colorIntensity = when (cfg.get("hudColor", "vivid")) {
            "soft" -> 0.72f; "mid" -> 0.88f; else -> 1.0f
        }
        outlineIntensity = (parseOutline(cfg.get("hudOutline", "40")) / 100f).coerceIn(0f, 1f)
        outlineFollowAccent = cfg.get("hudOutlineAccent", "1") == "1"
        scale = (parseIntOr(cfg.get("hudScale", "100"), 100).coerceIn(50, 200)) / 100f
        bgOpacity = (parseIntOr(cfg.get("hudOpacity", "80"), 80).coerceIn(0, 100)) / 100f
        applyTheme()

        rebuildAndInvalidate()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (isShown) startUpdates()
    }

    override fun onDetachedFromWindow() {
        stopUpdates()
        super.onDetachedFromWindow()
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (isAttachedToWindow && isShown) startUpdates() else stopUpdates()
    }

    private fun stopUpdates() {
        updateJob?.cancel(); updateJob = null
        hueJob?.cancel(); hueJob = null
    }

    private fun startUpdates() {
        startHueCycle()
        if (updateJob?.isActive == true) return
        updateJob = scope.launch {
            while (isActive) {
                val stats = frames
                val now = System.nanoTime()
                val collected = withContext(Dispatchers.IO) {
                    Pair(metrics.snapshot(), stats?.lows(now) ?: FrameStats.Lows.EMPTY)
                }
                snap = collected.first
                lows = collected.second
                fpsNow = stats?.fps ?: 0f
                fpsAvg = stats?.averageFps() ?: 0f
                graphMinMs = stats?.graphMinMs() ?: 0f
                graphMaxMs = stats?.graphMaxMs() ?: 0f
                onRefresh?.run()
                rebuildAndInvalidate()
                withTimeoutOrNull(REFRESH_MS) { periodDone.receive() }
            }
        }
    }

    var onRefresh: Runnable? = null

    private fun applyTheme() {
        applyPalette(Themes.current(context))
        if (isAttachedToWindow && isShown) startHueCycle()
    }

    private fun applyPalette(palette: Palette) {
        accent = palette.primary.toArgb()
        surfaceRgb = palette.surface.toArgb() and 0xFFFFFF
        colFps = accent
        colGraph = lighten(accent, 0.3f)
    }

    private fun startHueCycle() {
        if (hueJob?.isActive == true) return
        if (SessionPrefs.theme(context) != Themes.INNER_GAMER) return
        hueJob = scope.launch {
            var hue = Themes.rgbHue
            var shown = Float.NaN
            var last = SystemClock.uptimeMillis()
            while (isActive && SessionPrefs.theme(context) == Themes.INNER_GAMER) {
                val now = SystemClock.uptimeMillis()
                val cycle = Themes.rgbCycleMillis(Themes.rgbSpeed).toFloat()
                if (Motion.scale == 0f) {
                    last = now
                    delay(HUE_IDLE_MS)
                    continue
                }
                hue = (hue + (now - last) / cycle * 360f) % 360f
                last = now
                val stepped = (hue / Themes.RGB_HUE_STEP).toInt() * Themes.RGB_HUE_STEP
                if (stepped != shown) {
                    shown = stepped
                    Themes.moveRgbHue(stepped)
                    applyPalette(Themes.innerGamer(stepped))
                    rebuildAndInvalidate()
                }
                delay((cycle * Themes.RGB_HUE_STEP / 360f).toLong().coerceAtLeast(16L))
            }
        }
    }

    private fun lighten(color: Int, amount: Float): Int = Color.rgb(
        (Color.red(color) + (255 - Color.red(color)) * amount).roundToInt(),
        (Color.green(color) + (255 - Color.green(color)) * amount).roundToInt(),
        (Color.blue(color) + (255 - Color.blue(color)) * amount).roundToInt(),
    )

    private fun rebuildAndInvalidate() {
        rebuild()
        requestLayout()
        invalidate()
    }

    private fun blend(color: Int): Int {
        val i = colorIntensity.coerceIn(0f, 1f)
        if (i >= 0.999f) return color
        val hsv = FloatArray(3)
        Color.colorToHSV(color, hsv)
        hsv[1] *= i
        return Color.HSVToColor(Color.alpha(color), hsv)
    }

    private fun sp(v: Float) = v * density * scale
    private fun measure(text: String, sizePx: Float): Float { measurePaint.textSize = sizePx; return measurePaint.measureText(text) }
    private fun lineH(sizePx: Float): Float { measurePaint.textSize = sizePx; val fm = measurePaint.fontMetrics; return fm.descent - fm.ascent }
    private fun ascent(sizePx: Float): Float { measurePaint.textSize = sizePx; return measurePaint.fontMetrics.ascent }

    private fun runWidth(spans: List<Span>): Float {
        var w = 0f
        for (s in spans) w += measure(s.text, s.sizePx)
        return w
    }

    private fun placeRun(startX: Float, baseline: Float, spans: List<Span>, glow: Boolean = false): Float {
        var x = startX
        for (s in spans) {
            glyphs.add(Glyph(x, baseline, s.text, blend(s.color), s.sizePx, glow = glow || s.glow))
            x += measure(s.text, s.sizePx)
        }
        return x
    }

    private fun one(v: Float): String =
        if (fpsDecimal) String.format(Locale.US, "%.1f", v) else v.roundToInt().toString()

    private fun fpsText(v: Float): String =
        if (differs(v)) "${one(v)}\u2192${one(presentedFps)}" else one(v)

    private fun differs(v: Float): Boolean =
        presentedFps > 0f && kotlin.math.abs(presentedFps - v) > maxOf(1f, v * 0.10f)

    private fun fmt1(v: Float): String = String.format(Locale.US, "%.1f", v)
    private fun lowText(v: Float): String? = if (v <= 0f) null else fmt1(v)

    private fun numUnit(num: String?, unit: String, numPx: Float, unitPx: Float): List<Span> =
        if (num == null) listOf(Span("—", colDim, numPx), Span(unit, colDim, unitPx))
        else listOf(Span(num, colValue, numPx), Span(unit, colDim, unitPx))

    private fun valueUnit(text: String, numPx: Float, unitPx: Float): List<Span> {
        val i = text.indexOfFirst { !(it.isDigit() || it == '.' || it == '-') }
        return if (i <= 0) listOf(Span(text, colValue, numPx))
        else listOf(Span(text.substring(0, i), colValue, numPx), Span(text.substring(i), colDim, unitPx))
    }

    private fun tempSpans(c: Int?, sensor: HudMetrics.TempSensor, numPx: Float, unitPx: Float): List<Span> {
        if (c == null) return emptyList()
        val txt = HudMetrics.formatTemp(c.toFloat(), tempDisplay, false)
        val col = HudMetrics.tempColor(
            c.toFloat(), metrics.resolveThresholds(sensor, tempDisplay), tempDisplay, colValue)
        val i = txt.indexOf('°')
        return if (i <= 0) listOf(Span(txt, col, numPx))
        else listOf(Span(txt.substring(0, i), col, numPx), Span(txt.substring(i), colDim, unitPx))
    }

    private fun gap(unitPx: Float) = Span("  ", colDim, unitPx)

    private fun glowing(spans: List<Span>): List<Span> =
        spans.mapIndexed { i, s -> if (i == 0) Span(s.text, s.color, s.sizePx, glow = true) else s }

    private fun hdrLine(px: Float): List<Span> = when (hdrState) {
        DroidDeckHudHdr.ON -> listOf(Span("HDR", colHdr, px))
        DroidDeckHudHdr.NO_HEADROOM -> listOf(Span("HDR", colHdr, px), Span(" (no headroom)", colBat, px))
        DroidDeckHudHdr.OFF -> listOf(Span("HDR off", colDim, px))
        DroidDeckHudHdr.READY -> listOf(Span("HDR ready", colDim, px))
        DroidDeckHudHdr.TONEMAPPED -> listOf(Span("HDR", colDim, px), Span(" tone-mapped", colBat, px))
        DroidDeckHudHdr.NOT_ON_THIS_SCREEN -> listOf(Span("HDR", colDim, px), Span(" not on this screen", colBat, px))
        else -> emptyList()
    }
    private fun hdrValue(px: Float): List<Span> = when (hdrState) {
        DroidDeckHudHdr.ON -> listOf(Span("on", colHdr, px))
        DroidDeckHudHdr.NO_HEADROOM -> listOf(Span("no headroom", colBat, px))
        DroidDeckHudHdr.OFF -> listOf(Span("off", colDim, px))
        DroidDeckHudHdr.READY -> listOf(Span("ready", colDim, px))
        DroidDeckHudHdr.TONEMAPPED -> listOf(Span("tone-mapped", colBat, px))
        DroidDeckHudHdr.NOT_ON_THIS_SCREEN -> listOf(Span("not on this screen", colBat, px))
        else -> emptyList()
    }
    private fun hdrText(): String = hdrLine(1f).joinToString("") { it.text }

    private fun gpuNameText(): String = gpuSpoofName.ifBlank { gpuModel }
    private fun gpuModelSpans(px: Float, color: Int, compact: Boolean = false): List<Span> {
        val name = Span(gpuNameText(), color, px)
        if (gpuSpoofName.isBlank()) return listOf(name)
        return listOf(name, Span(if (compact) " spoof" else " (spoof)", colBat, px))
    }

    private val inkRect = android.graphics.Rect()

    private fun rebuild() {
        glyphs.clear(); tileRects.clear(); graphRect = null
        headerMark = null; chipRect = null; dPanel = null; dDividerTop = -1f; tabRect = null; stripRect = null
        val s = snap
        if (s == null) { contentW = 0f; contentH = 0f; return }
        if (dock != DroidDeckHudDock.NONE) { buildDockTab(); return }
        when (size) {
            DroidDeckHudSize.FULL -> { buildFull(s); intoPanel(sp(10f), header = true) }
            DroidDeckHudSize.TILES -> { buildTiles(s); intoPanel(sp(9f), header = true) }
            DroidDeckHudSize.PILL -> { buildPill(s); intoPanel(sp(10f), header = false) }
            DroidDeckHudSize.MINIMAL -> buildMinimal(s)
            DroidDeckHudSize.MEGA -> { buildMega(s); intoPanel(sp(10f), header = true) }
            DroidDeckHudSize.STRIP -> buildStrip(s)
        }
    }

    private fun buildDockTab() {
        val numPx = sp(TAB_NUM_SP)
        val spans = tabSpans()
        val padX = sp(TAB_PAD_X_SP); val padY = sp(TAB_PAD_Y_SP)
        placeRun(padX, padY + capHeight(numPx), spans)
        val (w, h) = dockTabSize()
        contentW = w; contentH = h
        tabRect = RectF(0f, 0f, contentW, contentH)
    }

    private fun tabSpans() = listOf(Span(one(fpsNow), colValue, sp(TAB_NUM_SP)), Span("fps", colDim, sp(TAB_LABEL_SP)))

    internal fun dockTabSize(): Pair<Float, Float> =
        Pair(runWidth(tabSpans()) + 2f * sp(TAB_PAD_X_SP), capHeight(sp(TAB_NUM_SP)) + 2f * sp(TAB_PAD_Y_SP))

    private fun drawTab(canvas: Canvas, rect: RectF, edgeOf: DroidDeckHudDock = dock) {
        val r = sp(8f); val stroke = outlineWidth(); val h = stroke / 2f
        val round = floatArrayOf(r, r, r, r, r, r, r, r)
        val edge = when (edgeOf) {
            DroidDeckHudDock.LEFT -> intArrayOf(0, 3)
            DroidDeckHudDock.RIGHT -> intArrayOf(1, 2)
            DroidDeckHudDock.TOP -> intArrayOf(0, 1)
            DroidDeckHudDock.BOTTOM -> intArrayOf(2, 3)
            DroidDeckHudDock.NONE -> intArrayOf()
        }
        for (corner in edge) { round[corner * 2] = 0f; round[corner * 2 + 1] = 0f }
        tabPath.rewind()
        tabPath.addRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, round, Path.Direction.CW)
        headerPaint.shader = null
        headerPaint.color = withAlpha(panelColor(), max(Color.alpha(panelColor()), 200))
        canvas.drawPath(tabPath, headerPaint)
        if (stroke <= 0f) return
        strokePaint.strokeWidth = stroke
        strokePaint.color = outlineColor()
        canvas.drawPath(tabPath, strokePaint)
    }

    private fun footerClock(): Boolean = showClockTime && size == DroidDeckHudSize.PILL

    private fun measureSans(text: String, sizePx: Float): Float { wordmarkPaint.textSize = sizePx; return wordmarkPaint.measureText(text) }

    private fun panelInset() = sp(8f)
    private fun panelLeft() = sp(13f)
    private fun contentLeft() = panelLeft() + sp(16f)

    private fun addPanelHeader(left: Float, inner: Float): Pair<Float, Float> {
        val wordPx = sp(10f); val clockPx = sp(9.5f); val gapH = sp(7f)
        wordmarkPaint.textSize = wordPx
        val markH = wordmarkPaint.fontMetrics.let { it.descent - it.ascent } * 0.95f
        val markTop = inner + gapH
        val markLeft = left - DroidDeckMark.inkLeftFor(markH)
        headerMark = RectF(markLeft, markTop, markLeft + DroidDeckMark.widthFor(markH), markTop + markH)
        val midY = markTop + markH / 2f
        var x = markLeft + DroidDeckMark.inkRightFor(markH) + sp(6f)
        wordmarkPaint.getTextBounds("DroidDeck", 0, 9, inkRect)
        glyphs.add(Glyph(x, midY - inkRect.exactCenterY(), "DroidDeck", blend(colValue), wordPx, sans = true))
        x += measureSans("DroidDeck", wordPx)
        if (showClockTime) {
            measurePaint.textSize = clockPx
            measurePaint.getTextBounds("0", 0, 1, inkRect)
            val base = midY - inkRect.exactCenterY()
            x += sp(6f)
            glyphs.add(Glyph(x, base, "·", blend(colDim), clockPx))
            x += measure("·", clockPx) + sp(6f)
            val t = clockTimeString()
            glyphs.add(Glyph(x, base, t, blend(colDim), clockPx))
            x += measure(t, clockPx)
        }
        dDividerTop = markTop + markH + gapH
        return Pair(dDividerTop + max(1f, density), x)
    }

    private fun intoPanel(pad: Float, header: Boolean) {
        if (contentW <= 0f || contentH <= 0f) return
        for (i in glyphs.indices) {
            val g = glyphs[i]
            if (kotlin.math.abs(g.x - pad) < 0.5f) glyphs[i] = g.moved(-inkLeft(g.text, g.sizePx), 0f)
        }
        val stroke = outlineWidth(); val inner = panelInset() + stroke
        var left = contentLeft()
        val content = glyphs.size
        var top = inner + sp(10f)
        var headerRight = 0f
        if (header) {
            val (bottom, right) = addPanelHeader(left, inner)
            top = bottom + sp(8f); headerRight = right
        } else {
            val chip = sp(22f)
            chipRect = RectF(left, 0f, left + chip, chip)
            left += chip + sp(8f)
        }
        val dx = left - pad; val dy = top - pad
        for (i in 0 until content) glyphs[i] = glyphs[i].moved(dx, dy)
        for (r in tileRects) r.offset(dx, dy)
        graphRect?.offset(dx, dy)
        val panel = RectF(panelLeft(), panelInset(), max(contentW - pad + dx, headerRight) + sp(18f),
            contentH - pad + dy + sp(10f) + stroke)
        chipRect?.let { it.offsetTo(it.left, panel.centerY() - it.height() / 2f) }
        finishPanel(panel, halfRound = !header)
    }

    private fun finishPanel(panel: RectF, halfRound: Boolean) {
        val r = if (halfRound) panel.height() / 2f else min(panel.height() / 2f, sp(56f))
        val margin = outlineWidth() + sp(4f)
        fun curveIn(top: Float, bottom: Float): Float {
            val d = max(max(panel.top + r - top, bottom - (panel.bottom - r)), 0f)
            return if (d <= 0f) 0f else if (d >= r) r else r - sqrt(r * r - d * d)
        }
        var right = panel.right
        for (g in glyphs) {
            val paint = if (g.sans) wordmarkPaint else measurePaint
            paint.textSize = g.sizePx
            paint.getTextBounds(g.text, 0, g.text.length, inkRect)
            if (inkRect.isEmpty) continue
            right = max(right, g.x + inkRect.right + curveIn(g.baseline + inkRect.top, g.baseline + inkRect.bottom) + margin)
        }
        graphRect?.let { right = max(right, it.right + curveIn(it.top, it.bottom) + margin) }
        for (t in tileRects) right = max(right, t.right + curveIn(t.top, t.bottom) + margin)
        panel.right = max(right, panel.left + sp(6f) + r)
        dPanel = panel; dRadius = r
        contentW = panel.right + panelInset()
        contentH = panel.bottom + panelInset()
    }

    private fun clockTimeString(): String = DateFormat.getTimeFormat(context).format(Date())

    private fun elapsedString(): String {
        val sec = (frames?.sessionSeconds(System.nanoTime()) ?: 0f).toInt().coerceAtLeast(0)
        val h = sec / 3600; val m = (sec % 3600) / 60; val s = sec % 60
        return if (h > 0) String.format(Locale.US, "%d:%02d:%02d", h, m, s)
        else String.format(Locale.US, "%02d:%02d", m, s)
    }

    private fun apiLabel(): String =
        if (showEngine && engineLabel.isNotBlank()) engineLabel else "FPS"

    private fun dxVersionLabeled(): String = when {
        engineLabel.contains("VKD3D", true) && vkd3dVersion.isNotBlank() -> "VKD3D $vkd3dVersion"
        engineLabel.contains("DXVK", true) && dxvkVersion.isNotBlank() -> "DXVK $dxvkVersion"
        else -> ""
    }

    private fun dxVersion(): String = when {
        engineLabel.contains("VKD3D", true) -> vkd3dVersion
        engineLabel.contains("DXVK", true) -> dxvkVersion
        else -> ""
    }

    private fun resolutionString(): String {
        if (resolution.isNotBlank()) return resolution
        val dm = resources.displayMetrics
        val hz = (display?.refreshRate ?: 0f)
        val base = "${dm.widthPixels}x${dm.heightPixels}"
        return if (hz >= 1f) "$base @ ${hz.roundToInt()}Hz" else base
    }

    private fun addSubtleClock(pad: Float, leftAligned: Boolean = false) {
        if (!footerClock()) return
        val px = sp(9.5f)
        val t = clockTimeString()
        val w = measure(t, px)
        val footerTop = contentH - pad
        val baseline = footerTop - ascent(px)
        val x = if (leftAligned) pad else (contentW - pad - w).coerceAtLeast(pad)
        glyphs.add(Glyph(x, baseline, t, blend(colDim), px))
        contentH = footerTop + lineH(px) + pad * 0.5f
        contentW = max(contentW, pad + w + pad)
    }

    private fun buildFull(s: HudMetrics.Snapshot) {
        val rowPx = sp(12f); val unitPx = rowPx * 0.62f
        val pad = sp(10f); val lineGap = sp(4f); val lvGap = sp(8f)
        val rows = ArrayList<HudRow>()

        if (showGpuModel && gpuNameText().isNotBlank())
            rows.add(HudRow(Span("GPU", colGpu, rowPx), gpuModelSpans(rowPx, colValue)))
        if (displayServer.isNotBlank())
            rows.add(HudRow(Span("DISP", colDisp, rowPx), listOf(Span(displayServer, colValue, rowPx))))
        if (hdrState != DroidDeckHudHdr.NONE)
            rows.add(HudRow(Span("HDR", colHdr, rowPx), hdrValue(rowPx)))
        if (showGPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.gpuPercent?.toString(), "%", rowPx, unitPx)
            if (showGpuTemp) tempSpans(s.gpuTempC, HudMetrics.TempSensor.GPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.gpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            rows.add(HudRow(Span("GPU", colGpu, rowPx), v))
        }
        if (showCPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.cpuPercent?.toString(), "%", rowPx, unitPx)
            if (showCpuTemp) tempSpans(s.cpuTempC, HudMetrics.TempSensor.CPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.cpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            rows.add(HudRow(Span("CPU", colCpu, rowPx), v))
        }
        if (showVram && s.vramText() != null)
            rows.add(HudRow(Span("VRAM", colVram, rowPx), valueUnit(s.vramText()!!, rowPx, unitPx)))
        if (showRAM) {
            val v = ArrayList<Span>()
            v += valueUnit(s.ramUsedText(), rowPx, unitPx)
            v += gap(unitPx); v += numUnit(s.ramPercent.roundToInt().toString(), "%", rowPx, unitPx)
            rows.add(HudRow(Span("RAM", colRam, rowPx), v))
        }
        if (showBattery || showPower || showBatteryTemp) {
            val v = ArrayList<Span>(); var any = false
            if (showBattery && s.battery.percent != null) { v += numUnit(s.battery.percent.toString(), "%", rowPx, unitPx); any = true }
            if (showBatteryTemp) tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { if (any) v += gap(unitPx); v += it; any = true } }
            if (showPower && s.battery.watts > 0f) { if (any) v += gap(unitPx); v += numUnit(fmt1(s.battery.watts), "W", rowPx, unitPx); any = true }
            if (any) rows.add(HudRow(Span("BAT", colBat, rowPx), v))
        }
        if (showFPS) {
            val v = ArrayList<Span>()
            v += glowing(numUnit(fpsText(fpsNow), "FPS", rowPx, unitPx))
            v += gap(unitPx); v += numUnit(fmt1(1000f / max(fpsNow, 1f)), "ms", rowPx, unitPx)
            rows.add(HudRow(Span(apiLabel(), colFps, rowPx), v))
            rows.add(HudRow(Span("AVG", colLo, rowPx), numUnit(fmt1(fpsAvg), "FPS", rowPx, unitPx)))
            rows.add(HudRow(Span("1%", colLo, rowPx), numUnit(lowText(lows.low1Fps), "FPS", rowPx, unitPx)))
            rows.add(HudRow(Span("0.1%", colLo, rowPx), numUnit(lowText(lows.low01Fps), "FPS", rowPx, unitPx)))
            if (showLow001)
                rows.add(HudRow(Span("0.01%", colLo, rowPx), numUnit(lowText(lows.low001Fps), "FPS", rowPx, unitPx)))
        }

        var labelCol = 0f
        for (r in rows) if (!r.inline) labelCol = max(labelCol, measure(r.label.text, rowPx))
        val h = lineH(rowPx); val asc = ascent(rowPx)
        var y = pad; var maxRight = pad
        for (r in rows) {
            val baseline = y - asc
            placeRun(pad, baseline, listOf(r.label))
            val valX = if (r.inline) pad + measure(r.label.text, rowPx) + lvGap
                       else pad + labelCol + lvGap
            val end = placeRun(valX, baseline, r.vals)
            maxRight = max(maxRight, end)
            y += h + lineGap
        }

        if (showFPS && showGraph) {
            val ftPx = unitPx * 1.15f
            val baseline = y - asc
            placeRun(pad, baseline, listOf(Span("Frametime", colFps, ftPx)))
            val stat = "min:${fmt1(graphMinMs)} max:${fmt1(graphMaxMs)}"
            val statX = pad + max(labelCol, measure("Frametime", ftPx)) + lvGap
            val end = placeRun(statX, baseline, listOf(Span(stat, colDim, unitPx)))
            maxRight = max(maxRight, end)
            y += h + lineGap
            val gh = sp(22f)
            val right = max(maxRight, pad + sp(160f))
            graphRect = RectF(pad, y, right, y + gh)
            maxRight = max(maxRight, right)
            y += gh
        }

        contentW = maxRight + pad
        contentH = y + pad
        addSubtleClock(pad)
    }

    private fun buildTiles(s: HudMetrics.Snapshot) {
        val keyPx = sp(10f); val valPx = sp(18f); val subPx = sp(10f); val unitPx = sp(11f)
        val pad = sp(9f); val innerPad = sp(8f); val tileGap = sp(6f); val lineGap = sp(4f)

        class Tile(val key: String, val keyColor: Int, val value: List<Span>, val sub: String?, val wide: Boolean)
        val tiles = ArrayList<Tile>()

        if (showFPS) tiles.add(Tile("FPS", colFps, listOf(Span(fpsText(fpsNow), colValue, valPx, glow = true)),
            "${fmt1(fpsAvg)} avg · ${lowText(lows.low1Fps) ?: "—"} 1%", false))
        if (showFPS) tiles.add(Tile("FRAME", colDim, numUnit(fmt1(1000f / max(fpsNow, 1f)), "ms", valPx, unitPx),
            "${fmt1(graphMinMs)} – ${fmt1(graphMaxMs)}", false))
        if (showGPU) tiles.add(Tile("GPU", colGpu, numUnit(s.gpuPercent?.toString(), "%", valPx, unitPx),
            if (showGpuTemp && s.gpuTempC != null) HudMetrics.formatTemp(s.gpuTempC.toFloat(), tempDisplay, false)
            else s.gpuClockMhz?.let { "${it}MHz" }, false))
        if (showCPU) tiles.add(Tile("CPU", colCpu, numUnit(s.cpuPercent?.toString(), "%", valPx, unitPx),
            listOfNotNull(if (showCpuTemp) s.cpuTempC?.let { HudMetrics.formatTemp(it.toFloat(), tempDisplay, false) } else null,
                s.cpuClockMhz?.toString()).joinToString(" · ").ifBlank { null }, false))
        if (showVram && s.vramText() != null) tiles.add(Tile("VRAM", colVram, valueUnit(s.vramText()!!, valPx, unitPx), null, false))
        if (showRAM) tiles.add(Tile("RAM", colRam, numUnit(s.ramPercent.roundToInt().toString(), "%", valPx, unitPx),
            "${s.ramUsedText()} / ${s.ramTotalText()}", false))
        if (showEngine && engineLabel.isNotBlank())
            tiles.add(Tile("API", colFps, listOf(Span(engineLabel, colValue, valPx)),
                dxVersion().ifBlank { null }, false))
        if (displayServer.isNotBlank())
            tiles.add(Tile("DISPLAY", colDisp, listOf(Span(displayServer, colValue, valPx)),
                if (hdrState != DroidDeckHudHdr.NONE) hdrText() else null, false))
        if (showGpuModel && gpuNameText().isNotBlank())
            tiles.add(Tile("GPU", colGpu, gpuModelSpans(valPx, colValue), null, true))
        if (showBattery || showPower || showBatteryTemp) {
            val parts = ArrayList<Span>(); var any = false
            if (showBattery && s.battery.percent != null) { parts += numUnit(s.battery.percent.toString(), "%", valPx, unitPx); any = true }
            if (showBatteryTemp && s.battery.tempC != null) { if (any) parts += Span(" · ", colDim, subPx); parts += tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, valPx, unitPx); any = true }
            if (showPower && s.battery.watts > 0f) { if (any) parts += Span(" · ", colDim, subPx); parts += numUnit(fmt1(s.battery.watts), "W", valPx, unitPx); any = true }
            if (any) tiles.add(Tile("BAT", colBat, parts, null, true))
        }
        if (tiles.isEmpty()) { contentW = 0f; contentH = 0f; return }

        val keyH = lineH(keyPx); val valH = lineH(valPx); val subH = lineH(subPx)
        fun tileW(t: Tile) = max(max(measure(t.key, keyPx), runWidth(t.value)),
            t.sub?.let { measure(it, subPx) } ?: 0f) + innerPad * 2
        var normalW = 0f
        for (t in tiles) if (!t.wide) normalW = max(normalW, tileW(t))
        if (normalW == 0f) for (t in tiles) normalW = max(normalW, tileW(t) / 2f)
        val tileH = innerPad * 2 + keyH + lineGap + valH + lineGap + subH
        val fullW = normalW * 2 + tileGap

        var x = pad; var y = pad; var col = 0
        fun place(t: Tile, tx: Float, ty: Float, tw: Float) {
            tileRects.add(RectF(tx, ty, tx + tw, ty + tileH))
            var by = ty + innerPad - ascent(keyPx)
            placeRun(tx + innerPad, by, listOf(Span(t.key, t.keyColor, keyPx)))
            by = ty + innerPad + keyH + lineGap - ascent(valPx)
            placeRun(tx + innerPad, by, t.value)
            if (t.sub != null) {
                by = ty + innerPad + keyH + lineGap + valH + lineGap - ascent(subPx)
                placeRun(tx + innerPad, by, listOf(Span(t.sub, colDim, subPx)))
            }
        }
        for (t in tiles) {
            if (t.wide) {
                if (col != 0) { y += tileH + tileGap; col = 0; x = pad }
                place(t, pad, y, fullW)
                y += tileH + tileGap; col = 0; x = pad
            } else {
                place(t, x, y, normalW)
                col++; x += normalW + tileGap
                if (col == 2) { col = 0; x = pad; y += tileH + tileGap }
            }
        }
        if (col != 0) y += tileH + tileGap
        if (showFPS && showGraph) {
            graphRect = RectF(pad, y, pad + fullW, y + sp(22f))
            y += sp(22f) + tileGap
        }
        contentW = pad + fullW + pad
        contentH = y + (pad - tileGap)
        addSubtleClock(pad)
    }

    private fun buildPill(s: HudMetrics.Snapshot) {
        val bigPx = sp(30f); val bigUnitPx = bigPx * 0.36f; val stkPx = sp(11.5f)
        val pad = sp(10f); val midGap = sp(12f); val stkLineGap = sp(3f)

        val left = ArrayList<Span>()
        val generating = differs(fpsNow)
        val fpsPx = if (generating) bigPx * 0.55f else bigPx
        left += Span(fpsText(fpsNow), colValue, fpsPx, glow = true)
        left += Span("fps", colDim, if (generating) bigUnitPx * 0.8f else bigUnitPx)

        val stack = ArrayList<List<Span>>()
        val gpuName = if (showGpuModel && gpuNameText().isNotBlank()) gpuModelSpans(stkPx, colDim, compact = true) else null
        run {
            val l = ArrayList<Span>()
            if (showGPU) { l += Span("GPU ${s.gpuPercent ?: "—"}%", colGpu, stkPx) }
            if (showCPU) { if (l.isNotEmpty()) l += Span(" · ", colDim, stkPx); l += Span("CPU ${s.cpuPercent ?: "—"}%", colCpu, stkPx) }
            if (l.isNotEmpty()) stack.add(l)
        }
        if (showRAM) stack.add(listOf(Span("RAM ${s.ramPercent.roundToInt()}%", colRam, stkPx)))
        if (showBattery || showPower || showBatteryTemp) {
            val l = ArrayList<Span>()
            fun part(spans: List<Span>) {
                l += if (l.isEmpty()) Span("BAT ", colBat, stkPx) else Span(" · ", colDim, stkPx)
                l += spans
            }
            if (showBattery && s.battery.percent != null) part(listOf(Span("${s.battery.percent}%", colBat, stkPx)))
            if (showBatteryTemp && s.battery.tempC != null)
                part(tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, stkPx, stkPx * 0.62f))
            if (showPower && s.battery.watts > 0f) part(listOf(Span("${fmt1(s.battery.watts)}W", colDim, stkPx)))
            if (l.isNotEmpty()) stack.add(l)
        }
        run {
            val l = ArrayList<Span>()
            l += Span("${fmt1(1000f / max(fpsNow, 1f))}ms", colDim, stkPx)
            if (displayServer.isNotBlank()) { l += Span(" · ", colDim, stkPx); l += Span(displayServer, colDisp, stkPx) }
            if (showVram && s.vramText() != null) { l += Span(" · ", colDim, stkPx); l += Span("${s.vramText()} vram", colDim, stkPx) }
            stack.add(l)
        }
        if (hdrState != DroidDeckHudHdr.NONE) stack.add(hdrLine(stkPx))

        var statsW = 0f
        for (l in stack) statsW = max(statsW, runWidth(l))
        val nameOnTop = gpuName != null && runWidth(gpuName) > statsW
        if (gpuName != null && !nameOnTop) stack.add(0, gpuName)

        val apiStr = apiLabel()
        val hasApi = apiStr != "FPS"
        val apiH = if (hasApi) lineH(stkPx) else 0f
        val apiGap = if (hasApi) stkLineGap else 0f

        val leftW = runWidth(left); val leftH = lineH(bigPx)
        val apiW = if (hasApi) measure(apiStr, stkPx) else 0f
        val leftBlockW = max(leftW, apiW)
        val leftColH = apiH + apiGap + leftH
        val stkH = lineH(stkPx)
        var stackW = 0f
        for (l in stack) stackW = max(stackW, runWidth(l))
        val stackTotalH = stack.size * stkH + (stack.size - 1).coerceAtLeast(0) * stkLineGap
        val innerH = max(leftColH, stackTotalH)
        val topLineH = if (nameOnTop) stkH + stkLineGap else 0f
        contentW = pad + leftBlockW + midGap + stackW + pad
        contentH = pad + topLineH + innerH + pad

        var ly = pad + topLineH + (innerH - leftColH) / 2f
        if (hasApi) {
            placeRun(pad, ly - ascent(stkPx), listOf(Span(apiStr, colFps, stkPx)))
            ly += apiH + apiGap
        }
        placeRun(pad, ly - ascent(bigPx), left)
        var sy = pad + topLineH + (innerH - stackTotalH) / 2f
        for (l in stack) {
            placeRun(pad + leftBlockW + midGap, sy - ascent(stkPx), l)
            sy += stkH + stkLineGap
        }
        addSubtleClock(pad, leftAligned = true)
        if (nameOnTop && gpuName != null) {
            val end = placeRun(pad, pad - ascent(stkPx), gpuName)
            contentW = max(contentW, end + pad)
        }
    }

    private fun buildMinimal(s: HudMetrics.Snapshot) {
        val bigPx = sp(34f); val bigUnitPx = bigPx * 0.32f; val subPx = sp(11.5f); val footPx = sp(9.5f)
        val stroke = outlineWidth()
        val left = contentLeft()
        val inner = panelInset() + stroke

        val minGenerating = differs(fpsNow)
        val big = listOf(
            Span(fpsText(fpsNow), colValue, if (minGenerating) bigPx * 0.55f else bigPx),
            Span("fps", colDim, if (minGenerating) bigUnitPx * 0.8f else bigUnitPx))
        val sub = ArrayList<Span>()
        sub += Span("1% ", colDim, subPx); sub += Span(lowText(lows.low1Fps) ?: "—", colFps, subPx)
        sub += Span("  ·  0.1% ", colDim, subPx); sub += Span(lowText(lows.low01Fps) ?: "—", colFps, subPx)
        if (showLow001) {
            sub += Span("  ·  0.01% ", colDim, subPx); sub += Span(lowText(lows.low001Fps) ?: "—", colFps, subPx)
        }
        val footerLabel = listOf(if (showEngine) engineLabel else "", displayServer)
            .filter { it.isNotBlank() }.joinToString(" · ")
        val hdr = if (hdrState != DroidDeckHudHdr.NONE) hdrLine(footPx) else emptyList()

        val (headerBottom, headerRight) = addPanelHeader(left, inner)
        val x = headerRight

        val colW = maxOf(runWidth(big), runWidth(sub), x - left,
            max(measure(footerLabel, footPx), runWidth(hdr)), if (showGraph) sp(120f) else 0f)
        val bigBase = headerBottom + sp(8f) + capHeight(big[0].sizePx)
        placeRun(left - inkLeft(big[0].text, big[0].sizePx), bigBase, big, glow = true)
        val subBase = bigBase + sp(10f) + capHeight(subPx)
        placeRun(left - inkLeft(sub[0].text, subPx), subBase, sub)
        var last = subBase
        if (showGraph) {
            val top = subBase + sp(9f)
            graphRect = RectF(left, top, left + colW, top + sp(24f))
            last = top + sp(24f)
        }
        if (footerLabel.isNotBlank()) {
            val base = last + sp(8f) + capHeight(footPx)
            glyphs.add(Glyph(left - inkLeft(footerLabel, footPx), base, footerLabel, blend(colDim), footPx))
            last = base
        }
        if (hdr.isNotEmpty()) {
            val base = last + sp(6f) + capHeight(footPx)
            placeRun(left - inkLeft(hdr[0].text, footPx), base, hdr)
            last = base
        }
        measurePaint.textSize = footPx
        val panel = RectF(panelLeft(), panelInset(), left + colW + sp(34f), last + measurePaint.fontMetrics.descent + sp(9f) + stroke)

        finishPanel(panel, halfRound = true)
    }

    private fun buildStrip(s: HudMetrics.Snapshot) {
        val rowPx = sp(11.5f); val unitPx = rowPx * 0.8f; val fpsPx = sp(15f)
        val padX = sp(10f); val groupGap = sp(13f)
        val h = sp(26f)
        val w = ((parent as? View)?.width?.takeIf { it > 0 } ?: resources.displayMetrics.widthPixels).toFloat()
        val mid = h / 2f
        val baseline = mid + capHeight(fpsPx) / 2f

        val markH = sp(13f)
        val markLeft = padX - DroidDeckMark.inkLeftFor(markH)
        headerMark = RectF(markLeft, mid - markH / 2f, markLeft + DroidDeckMark.widthFor(markH), mid + markH / 2f)
        var x = markLeft + DroidDeckMark.inkRightFor(markH) + sp(8f)

        var right = w - padX
        if (showClockTime) {
            val t = clockTimeString()
            right -= measure(t, rowPx)
            glyphs.add(Glyph(right, baseline, t, blend(colDim), rowPx))
            right -= groupGap
        }

        val generating = differs(fpsNow)
        val fps = ArrayList<Span>()
        if (apiLabel() != "FPS") fps += Span("${apiLabel()} ", colDim, rowPx)
        fps += Span(fpsText(fpsNow), colValue, if (generating) fpsPx * 0.8f else fpsPx, glow = true)
        fps += Span("fps", colDim, unitPx)
        x = placeRun(x, baseline, fps) + groupGap

        if (showGraph) {
            val gw = sp(72f); val gh = h * 0.55f
            if (x + gw <= right) {
                graphRect = RectF(x, mid - gh / 2f, x + gw, mid + gh / 2f)
                x += gw + groupGap
            }
        }

        val groups = ArrayList<List<Span>>()
        run {
            val l = ArrayList<Span>()
            l += Span("1% ", colDim, rowPx); l += Span(lowText(lows.low1Fps) ?: "—", colFps, rowPx)
            l += Span(" 0.1% ", colDim, rowPx); l += Span(lowText(lows.low01Fps) ?: "—", colFps, rowPx)
            if (showLow001) { l += Span(" 0.01% ", colDim, rowPx); l += Span(lowText(lows.low001Fps) ?: "—", colFps, rowPx) }
            groups += l
        }
        if (showGPU || showGpuTemp) {
            val l = arrayListOf(Span("GPU", colGpu, rowPx))
            if (showGPU) l += Span(" ${s.gpuPercent ?: "—"}%", colGpu, rowPx)
            if (showGpuTemp && s.gpuTempC != null) { l += Span(" ", colDim, rowPx); l += tempSpans(s.gpuTempC, HudMetrics.TempSensor.GPU, rowPx, unitPx) }
            groups += l
        }
        if (showCPU || showCpuTemp) {
            val l = arrayListOf(Span("CPU", colCpu, rowPx))
            if (showCPU) l += Span(" ${s.cpuPercent ?: "—"}%", colCpu, rowPx)
            if (showCpuTemp && s.cpuTempC != null) { l += Span(" ", colDim, rowPx); l += tempSpans(s.cpuTempC, HudMetrics.TempSensor.CPU, rowPx, unitPx) }
            groups += l
        }
        if (showVram) s.vramText()?.let { groups += listOf(Span("VRAM $it", colVram, rowPx)) }
        if (showRAM) groups += listOf(Span("RAM ${s.ramPercent.roundToInt()}%", colRam, rowPx))
        if (showBattery || showPower || showBatteryTemp) {
            val l = ArrayList<Span>()
            fun part(spans: List<Span>) {
                l += if (l.isEmpty()) Span("BAT ", colBat, rowPx) else Span(" ", colDim, rowPx)
                l += spans
            }
            if (showBattery && s.battery.percent != null) part(listOf(Span("${s.battery.percent}%", colBat, rowPx)))
            if (showBatteryTemp && s.battery.tempC != null)
                part(tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, rowPx, unitPx))
            if (showPower && s.battery.watts > 0f) part(listOf(Span("${fmt1(s.battery.watts)}W", colDim, rowPx)))
            if (l.isNotEmpty()) groups += l
        }
        if (displayServer.isNotBlank()) groups += listOf(Span(displayServer, colDisp, rowPx))
        if (hdrState != DroidDeckHudHdr.NONE) groups += hdrLine(rowPx)
        if (showGpuModel && gpuNameText().isNotBlank()) groups += gpuModelSpans(rowPx, colDim, compact = true)

        for (g in groups) {
            if (x + runWidth(g) > right) break
            x = placeRun(x, baseline, g) + groupGap
        }

        stripRect = RectF(0f, 0f, w, h)
        contentW = w; contentH = h
    }

    private fun capHeight(px: Float): Float {
        measurePaint.textSize = px
        measurePaint.getTextBounds("0", 0, 1, inkRect)
        return -inkRect.top.toFloat()
    }

    private fun inkLeft(text: String, px: Float): Float {
        if (text.isEmpty()) return 0f
        measurePaint.textSize = px
        measurePaint.getTextBounds(text, 0, text.length, inkRect)
        return inkRect.left.toFloat()
    }

    private fun layoutColumn(rows: List<HudRow>, x: Float, y0: Float,
                             rowPx: Float, lineGap: Float, lvGap: Float): Pair<Float, Float> {
        if (rows.isEmpty()) return Pair(y0, x)
        var labelCol = 0f
        for (r in rows) if (!r.inline) labelCol = max(labelCol, measure(r.label.text, rowPx))
        val h = lineH(rowPx); val asc = ascent(rowPx)
        var y = y0; var right = x
        for (r in rows) {
            val baseline = y - asc
            placeRun(x, baseline, listOf(r.label))
            val valX = if (r.inline) x + measure(r.label.text, rowPx) + lvGap
                       else x + labelCol + lvGap
            val end = placeRun(valX, baseline, r.vals)
            right = max(right, end)
            y += h + lineGap
        }
        return Pair(y - lineGap, right)
    }

    private fun layoutWrap(fragments: List<List<Span>>, x: Float, y0: Float, maxWidth: Float,
                           px: Float, lineGap: Float): Pair<Float, Float> {
        if (fragments.isEmpty()) return Pair(y0, x)
        val h = lineH(px); val asc = ascent(px)
        val sepW = measure(" · ", px)
        var cx = x; var y = y0; var right = x; var firstOnLine = true
        for (frag in fragments) {
            val w = runWidth(frag)
            if (!firstOnLine && (cx + sepW + w - x) > maxWidth) { y += h + lineGap; cx = x; firstOnLine = true }
            if (!firstOnLine) { placeRun(cx, y - asc, listOf(Span(" · ", colDim, px))); cx += sepW }
            val end = placeRun(cx, y - asc, frag)
            cx = end; right = max(right, end); firstOnLine = false
        }
        return Pair(y + h, right)
    }

    private fun buildMega(s: HudMetrics.Snapshot) {
        val rowPx = sp(11.5f); val unitPx = rowPx * 0.62f; val bandPx = sp(9.5f)
        val pad = sp(10f); val lineGap = sp(3.5f); val lvGap = sp(7f); val gutter = sp(16f)

        val left = ArrayList<HudRow>()
        if (showGpuModel && gpuNameText().isNotBlank())
            left.add(HudRow(Span("GPU", colGpu, rowPx), gpuModelSpans(rowPx, colValue)))
        if (showGPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.gpuPercent?.toString(), "%", rowPx, unitPx)
            if (showGpuTemp) tempSpans(s.gpuTempC, HudMetrics.TempSensor.GPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.gpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            left.add(HudRow(Span("GPU", colGpu, rowPx), v))
        }
        if (showCPU) {
            val v = ArrayList<Span>()
            v += numUnit(s.cpuPercent?.toString(), "%", rowPx, unitPx)
            if (showCpuTemp) tempSpans(s.cpuTempC, HudMetrics.TempSensor.CPU, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { v += gap(unitPx); v += it } }
            v += gap(unitPx); v += numUnit(s.cpuClockMhz?.toString(), "MHz", rowPx, unitPx)
            left.add(HudRow(Span("CPU", colCpu, rowPx), v))
        }
        if (showPerCore) {
            val pct = s.perCorePercent; val clk = s.perCoreClockMhz
            val n = max(pct.size, clk.size)
            for (i in 0 until n) {
                val v = ArrayList<Span>()
                v += numUnit(if (i < pct.size && pct[i] >= 0) pct[i].toString() else null, "%", rowPx, unitPx)
                v += gap(unitPx)
                v += numUnit(if (i < clk.size && clk[i] > 0) clk[i].toString() else null, "MHz", rowPx, unitPx)
                left.add(HudRow(Span("C$i", colCpu, rowPx), v))
            }
        }

        val right = ArrayList<HudRow>()
        if (showVram && s.vramText() != null)
            right.add(HudRow(Span("VRAM", colVram, rowPx), valueUnit(s.vramText()!!, rowPx, unitPx)))
        if (showRAM) {
            val v = ArrayList<Span>()
            v += valueUnit(s.ramUsedText(), rowPx, unitPx)
            v += Span("/", colDim, unitPx); v += valueUnit(s.ramTotalText(), rowPx, unitPx)
            v += gap(unitPx); v += numUnit(s.ramPercent.roundToInt().toString(), "%", rowPx, unitPx)
            right.add(HudRow(Span("RAM", colRam, rowPx), v))
        }
        if (showSwap && s.swapUsedText() != null) {
            val v = ArrayList<Span>()
            v += valueUnit(s.swapUsedText()!!, rowPx, unitPx)
            s.swapTotalText()?.let { v += Span("/", colDim, unitPx); v += valueUnit(it, rowPx, unitPx) }
            right.add(HudRow(Span("SWP", colRam, rowPx), v))
        }
        if (showNet && s.netDownBps != null) {
            val d = (s.netDownBps ?: 0L) / 1024L
            val u = (s.netUpBps ?: 0L) / 1024L
            val v = ArrayList<Span>()
            v += Span("↓", colDim, unitPx); v += Span(d.toString(), colValue, rowPx)
            v += Span(" ↑", colDim, unitPx); v += Span(u.toString(), colValue, rowPx)
            v += Span("KB/s", colDim, unitPx)
            right.add(HudRow(Span("NET", colFps, rowPx), v))
        }
        if (showBattery || showPower || showBatteryTemp) {
            val v = ArrayList<Span>(); var any = false
            if (showBattery && s.battery.percent != null) { v += numUnit(s.battery.percent.toString(), "%", rowPx, unitPx); any = true }
            if (showBatteryTemp) tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, rowPx, unitPx)
                .let { if (it.isNotEmpty()) { if (any) v += gap(unitPx); v += it; any = true } }
            if (showPower && s.battery.watts > 0f) { if (any) v += gap(unitPx); v += numUnit(fmt1(s.battery.watts), "W", rowPx, unitPx); any = true }
            if (any) right.add(HudRow(Span("BAT", colBat, rowPx), v))
        }
        if (showFPS) {
            val v = ArrayList<Span>()
            v += glowing(numUnit(fpsText(fpsNow), "FPS", rowPx, unitPx))
            v += gap(unitPx); v += numUnit(fmt1(1000f / max(fpsNow, 1f)), "ms", rowPx, unitPx)
            right.add(HudRow(Span(apiLabel(), colFps, rowPx), v))
            right.add(HudRow(Span("AVG", colLo, rowPx), numUnit(fmt1(fpsAvg), "FPS", rowPx, unitPx)))
            right.add(HudRow(Span("1%", colLo, rowPx), numUnit(lowText(lows.low1Fps), "FPS", rowPx, unitPx)))
            right.add(HudRow(Span("0.1%", colLo, rowPx), numUnit(lowText(lows.low01Fps), "FPS", rowPx, unitPx)))
            if (showLow001)
                right.add(HudRow(Span("0.01%", colLo, rowPx), numUnit(lowText(lows.low001Fps), "FPS", rowPx, unitPx)))
        }

        val (leftBottom, leftRight) = layoutColumn(left, pad, pad, rowPx, lineGap, lvGap)
        val rightX = (if (left.isEmpty()) pad else leftRight + gutter)
        val (rightBottom, rightRight) = layoutColumn(right, rightX, pad, rowPx, lineGap, lvGap)
        var y = max(leftBottom, rightBottom)
        var maxRight = max(leftRight, rightRight)

        val band = ArrayList<List<Span>>()
        if (showResolution) band.add(listOf(Span("RES ", colDim, bandPx), Span(resolutionString(), colValue, bandPx)))
        if (displayServer.isNotBlank()) band.add(listOf(Span("DISP ", colDim, bandPx), Span(displayServer, colDisp, bandPx)))
        if (hdrState != DroidDeckHudHdr.NONE) band.add(listOf(Span("HDR ", colDim, bandPx)) + hdrValue(bandPx))
        if (showProton && wineVersion.isNotBlank()) band.add(listOf(Span(wineVersion, colVram, bandPx)))
        if (showSession) band.add(listOf(Span("elapsed ", colDim, bandPx), Span(elapsedString(), colValue, bandPx)))
        if (band.isNotEmpty()) {
            y += sp(4f)
            val (bBottom, bRight) = layoutWrap(band, pad, y, max(maxRight - pad, sp(180f)), bandPx, lineGap)
            y = bBottom; maxRight = max(maxRight, bRight)
        }

        if (showGraph) {
            y += sp(3f)
            val gh = sp(22f)
            val gr = max(maxRight, pad + sp(220f))
            graphRect = RectF(pad, y, gr, y + gh)
            maxRight = max(maxRight, gr)
            y += gh
        }

        val dxLine = dxVersionLabeled()
        val hasDx = showDxVer && dxLine.isNotBlank()
        if (hasDx) {
            y += sp(4f)
            maxRight = max(maxRight, placeRun(pad, y - ascent(bandPx), listOf(Span(dxLine, colDim, bandPx))))
            y += lineH(bandPx)
        }
        if (showWrapper && graphicsWrapper.isNotBlank()) {
            y += if (hasDx) sp(1f) else sp(4f)
            maxRight = max(maxRight, placeRun(pad, y - ascent(bandPx), listOf(Span(graphicsWrapper, colDim, bandPx))))
            y += lineH(bandPx)
        }

        contentW = maxRight + pad
        contentH = y + pad
        addSubtleClock(pad)
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        if (stripRect != null) { setMeasuredDimension(contentW.roundToInt(), resolveSize(contentH.roundToInt(), heightMeasureSpec)); return }
        setMeasuredDimension(
            resolveSize(contentW.roundToInt(), widthMeasureSpec),
            resolveSize(contentH.roundToInt(), heightMeasureSpec),
        )
    }

    override fun onDraw(canvas: Canvas) {
        if (contentW <= 0f || contentH <= 0f) { lockController.drawBadge(canvas); return }
        val blend = if (dock == DroidDeckHudDock.NONE && previewEdge != DroidDeckHudDock.NONE) previewBlend else 0f
        if (blend <= 0f) { drawContent(canvas); return }
        if (blend < 1f) {
            canvas.saveLayerAlpha(0f, 0f, width.toFloat(), height.toFloat(), ((1f - blend) * 255f).roundToInt())
            drawContent(canvas)
            canvas.restore()
        }
        drawTabPreview(canvas, blend)
    }

    private fun drawTabPreview(canvas: Canvas, blend: Float) {
        val (w, h) = dockTabSize()
        if (scaleX <= 0f || scaleY <= 0f) return
        canvas.save()
        canvas.translate(width / 2f, height / 2f)
        canvas.scale(1f / scaleX, 1f / scaleY)
        canvas.translate(-w / 2f, -h / 2f)
        canvas.saveLayerAlpha(0f, 0f, w, h, (blend * 255f).roundToInt())
        drawTab(canvas, RectF(0f, 0f, w, h), previewEdge)
        var x = sp(TAB_PAD_X_SP)
        val baseline = sp(TAB_PAD_Y_SP) + capHeight(sp(TAB_NUM_SP))
        for (span in tabSpans()) {
            drawPaint.textSize = span.sizePx
            drawPaint.color = blend(span.color)
            canvas.drawText(span.text, x, baseline, drawPaint)
            x += measure(span.text, span.sizePx)
        }
        canvas.restore()
        canvas.restore()
    }

    private fun drawContent(canvas: Canvas) {

        dPanel?.let { panel -> panelFrame(panel)?.let { canvas.drawBitmap(it, 0f, 0f, null) } }

        if (tileRects.isNotEmpty()) {
            tilePaint.color = Color.argb((14 * bgOpacity).roundToInt().coerceIn(8, 40), 255, 255, 255)
            val tr = sp(6f)
            for (rect in tileRects) canvas.drawRoundRect(rect, tr, tr, tilePaint)
        }

        chipRect?.let { drawChip(canvas, it) }
        tabRect?.let { drawTab(canvas, it) }
        stripRect?.let { drawStrip(canvas, it) }

        for (g in glyphs) {
            val paint = if (g.sans) wordmarkPaint else drawPaint
            paint.textSize = g.sizePx
            paint.color = g.color
            if (g.glow) paint.setShadowLayer(sp(6f), 0f, 0f, withAlpha(colGraph, 217))
            canvas.drawText(g.text, g.x, g.baseline, paint)
            if (g.glow) paint.clearShadowLayer()
        }
        headerMark?.let { DroidDeckMark.draw(canvas, it.left, it.top, it.height(), blend(colValue), accent) }

        graphRect?.let { drawGraph(canvas, it) }

        lockController.drawBadge(canvas)
    }

    private fun drawGraph(canvas: Canvas, rect: RectF) {
        val stats = frames ?: return
        stats.graph(graphBuf)
        val step = rect.width() / (graphBuf.size - 1)
        graphPath.rewind()
        for (i in graphBuf.indices) {
            val px = rect.left + i * step
            val py = rect.bottom - min(graphBuf[i], GRAPH_CLIP_MS) / FrameStats.GRAPH_MAX_MS * rect.height()
            if (i == 0) graphPath.moveTo(px, py) else graphPath.lineTo(px, py)
        }
        if (dPanel != null) {
            graphPaint.strokeWidth = sp(4.5f)
            graphPaint.color = withAlpha(blend(colGraph), 56)
            canvas.drawPath(graphPath, graphPaint)
        }
        graphPaint.strokeWidth = sp(1.6f)
        graphPaint.color = blend(colGraph)
        canvas.drawPath(graphPath, graphPaint)
    }

    private fun drawStrip(canvas: Canvas, rect: RectF) {
        headerPaint.shader = null
        headerPaint.color = panelColor()
        canvas.drawRect(rect, headerPaint)
        val stroke = outlineWidth()
        if (stroke <= 0f) return
        val line = max(stroke / 2f, density)
        val p = parent as? View
        val atBottom = p != null && p.height > 0 && y + height / 2f > p.height / 2f
        headerPaint.color = withAlpha(outlineColor(), (110 + 145 * outlineIntensity).roundToInt().coerceAtMost(255))
        if (atBottom) canvas.drawRect(rect.left, rect.top, rect.right, rect.top + line, headerPaint)
        else canvas.drawRect(rect.left, rect.bottom - line, rect.right, rect.bottom, headerPaint)
    }

    private fun outlineWidth(): Float = if (outlineIntensity > 0f) outlineIntensity * sp(3.5f) else 0f
    private fun outlineColor(): Int = if (outlineFollowAccent) accent else Color.rgb(200, 200, 200)
    private fun panelColor(): Int = Color.argb((bgOpacity.coerceIn(0f, 1f) * 255f).roundToInt(),
        Color.red(surfaceRgb), Color.green(surfaceRgb), Color.blue(surfaceRgb))
    private fun withAlpha(color: Int, alpha: Int): Int =
        Color.argb(alpha, Color.red(color), Color.green(color), Color.blue(color))

    private fun dShape(l: Float, t: Float, r: Float, b: Float, rad: Float, corner: Float): Path {
        return Path().apply {
            moveTo(l, t + corner)
            lineTo(l, b - corner); quadTo(l, b, l + corner, b)
            lineTo(r - rad, b)
            arcTo(r - 2f * rad, b - 2f * rad, r, b, 90f, -90f, false)
            lineTo(r, t + rad)
            arcTo(r - 2f * rad, t, r, t + 2f * rad, 0f, -90f, false)
            lineTo(l + corner, t); quadTo(l, t, l, t + corner)
            close()
        }
    }

    private fun roundEdgeX(p: RectF, y: Float, stroke: Float): Float {
        val r = dRadius - stroke
        val cx = p.right - dRadius
        val dy = p.top + dRadius - y
        return if (dy <= 0f) p.right - stroke else if (dy >= r) cx else cx + sqrt(r * r - dy * dy)
    }

    private fun panelFrame(panel: RectF): Bitmap? {
        if (width <= 0 || height <= 0) return null
        val stroke = outlineWidth()
        val key = "$width,$height,$panel,$dRadius,$dDividerTop,$stroke,$accent,$surfaceRgb,$bgOpacity,${outlineColor()}"
        if (key == dFrameKey) dFrame?.let { return it }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val corner = sp(6f)
        val body = dShape(panel.left, panel.top, panel.right, panel.bottom, dRadius, corner)
        bgPaint.color = panelColor()
        c.drawPath(body, bgPaint)
        c.save()
        c.clipPath(body)
        if (dDividerTop >= 0f) {
            val dividerBottom = dDividerTop + max(1f, density)
            headerPaint.shader = android.graphics.LinearGradient(panel.left, 0f, panel.right, 0f,
                withAlpha(accent, 46), withAlpha(accent, 0), android.graphics.Shader.TileMode.CLAMP)
            c.drawRect(panel.left, panel.top, panel.right, dividerBottom, headerPaint)
            headerPaint.shader = null
            headerPaint.color = withAlpha(accent, 90)
            c.drawRect(panel.left, dDividerTop, roundEdgeX(panel, dDividerTop, stroke), dividerBottom, headerPaint)
        }
        val halfRound = dRadius >= panel.height() / 2f - 0.5f
        val wmH = if (halfRound) panel.height() * 0.92f else min(panel.height(), panel.width()) * 0.55f
        DroidDeckMark.draw(c, panel.right - DroidDeckMark.widthFor(wmH) - (if (halfRound) panel.height() * 0.02f else sp(10f)),
            panel.centerY() - wmH / 2f, wmH, Color.argb(20, 255, 255, 255), withAlpha(accent, 20))
        c.restore()
        if (stroke > 0f) {
            val h = stroke / 2f
            val outline = dShape(panel.left + h, panel.top + h, panel.right - h, panel.bottom - h, dRadius - h, corner)
            val blur = BlurMaskFilter((sp(3.2f) - 0.5f).coerceAtLeast(0.5f) / 0.57735f, BlurMaskFilter.Blur.NORMAL)
            glowPaint.strokeWidth = stroke
            for (pass in 0..2) {
                glowPaint.maskFilter = if (pass < 2) blur else null
                glowPaint.style = Paint.Style.STROKE; glowPaint.color = outlineColor()
                c.drawPath(outline, glowPaint)
            }
            glowPaint.maskFilter = null
        }
        dFrame = bmp; dFrameKey = key
        return bmp
    }

    @VisibleForTesting
    internal fun showForTest(snapshot: HudMetrics.Snapshot, engine: String, nowNs: Long) {
        engineLabel = engine
        snap = snapshot
        lows = frames?.lows(nowNs) ?: FrameStats.Lows.EMPTY
        fpsNow = frames?.fps ?: 0f
        rebuild()
    }

    @VisibleForTesting internal fun panelForTest(): RectF? = dPanel
    @VisibleForTesting internal fun panelRadiusForTest(): Float = dRadius
    @VisibleForTesting internal fun graphForTest(): RectF? = graphRect
    @VisibleForTesting internal fun linesForTest(): List<String> =
        glyphs.groupBy { it.baseline.roundToInt() }.toSortedMap().values.map { line -> line.sortedBy { it.x }.joinToString("") { it.text } }

    private fun drawChip(canvas: Canvas, rect: RectF) {
        val r = sp(6f)
        headerPaint.shader = null
        headerPaint.color = Color.argb(235, Color.red(surfaceRgb), Color.green(surfaceRgb), Color.blue(surfaceRgb))
        canvas.drawRoundRect(rect, r, r, headerPaint)
        strokePaint.strokeWidth = sp(1f)
        strokePaint.color = accent
        val h = strokePaint.strokeWidth / 2f
        canvas.drawRoundRect(rect.left + h, rect.top + h, rect.right - h, rect.bottom - h, r, r, strokePaint)
        val markH = rect.height() * 0.6f
        val markW = DroidDeckMark.widthFor(markH)
        DroidDeckMark.draw(canvas, rect.centerX() - markW / 2f, rect.centerY() - markH / 2f, markH, blend(colValue), accent)
    }

    internal var passesTouchAt: ((rawX: Float, rawY: Float) -> Boolean)? = null

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (dock != DroidDeckHudDock.NONE) return onTabTouch(event)
        if (event.actionMasked == MotionEvent.ACTION_DOWN && lockController.isLocked() &&
            passesTouchAt?.invoke(event.rawX, event.rawY) == true) return false
        return lockController.onTouchEvent(event)
    }

    internal val isStrip: Boolean get() = stripRect != null

    internal val contentWidth: Float get() = contentW
    internal val contentHeight: Float get() = contentH

    internal val isDragging: Boolean get() = lockController.isDragging() || tabDragging

    private companion object {
        const val TAB_NUM_SP = 10f
        const val TAB_LABEL_SP = 6f
        const val TAB_PAD_X_SP = 5f
        const val TAB_PAD_Y_SP = 4f
        const val REFRESH_MS = 500L
        private const val HUE_IDLE_MS = 500L
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
        const val GRAPH_CLIP_MS = 49.9f

        fun parseIntOr(s: String?, d: Int): Int = try { s?.trim()?.toInt() ?: d } catch (e: Exception) { d }

        fun parseOutline(v: String?): Int = when (v?.trim()?.lowercase(Locale.US)) {
            null -> 40; "off" -> 0; "soft" -> 40; "strong" -> 70
            else -> v.trim().toIntOrNull()?.coerceIn(0, 100) ?: 40
        }
    }
}
