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

    private var cfg: KeyValueSet = KeyValueSet()
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
    private var showLow1 = true
    private var showLow01 = true
    private var showLow001 = true
    private var fpsDecimal = true
    private var showClockTime = true
    private var showLatency = true
    private var showAvg = true
    private var showDisplay = true
    private var showHdr = true
    private var storedOrder: List<String> = emptyList()
    private var showPerCore = true
    private var showSwap = true
    private var showNet = true
    private var showResolution = true
    private var showProton = true
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
    private var headerWordmark: RectF? = null
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
    private var sFrame: Bitmap? = null
    private var sFrameKey = ""
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

    private class Glyph(val x: Float, val baseline: Float, val text: String, val color: Int, val sizePx: Float, val glow: Boolean = false) {
        fun moved(dx: Float, dy: Float) = Glyph(x + dx, baseline + dy, text, color, sizePx, glow)
    }
    private class Span(val text: String, val color: Int, val sizePx: Float, val glow: Boolean = false)
    private class HudRow(val key: String, val label: Span, val vals: List<Span>, val inline: Boolean = false)
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
        if (graphRect == null) return
        if (stripRect != null) {
            val now = SystemClock.uptimeMillis()
            if (now - lastGraphDrawMs < STRIP_GRAPH_REDRAW_MS) return
            lastGraphDrawMs = now
        }
        invalidate()
    }

    private var lastGraphDrawMs = 0L

    private fun cycleSize() {
        size = size.next()
        refreshOrder()
        onSizeCycledListener?.accept(size.token)
        rebuildAndInvalidate()
    }

    private fun refreshOrder() {
        storedOrder = DroidDeckHudConfig.storedOrder(mapOf("hudOrder" to cfg.get("hudOrder", "")), size)
    }

    private fun switchOn(key: String): Boolean =
        cfg.get(key, DroidDeckHudConfig.DEFAULTS[key] ?: "1") == "1"

    fun applyConfig(configString: String?) {
        if (configString.isNullOrEmpty()) return
        cfg = KeyValueSet(configString)

        size = DroidDeckHudSize.from(cfg.get("hudSize", "minimal"))
        showFPS = switchOn("showFPS")
        showEngine = switchOn("showEngine")
        showGpuModel = switchOn("showGpuModel")
        showCPU = switchOn("showCPUUsage")
        showGPU = switchOn("showGPULoad")
        showCpuTemp = switchOn("showTemp")
        showGpuTemp = switchOn("showGpuTemp")
        showVram = switchOn("showVram")
        showRAM = switchOn("showRAM")
        showBattery = switchOn("showBattery")
        showPower = switchOn("showPower")
        showBatteryTemp = switchOn("showBatteryTemp")
        showGraph = switchOn("showFPSGraph")
        showLow001 = switchOn("showLow001")
        fpsDecimal = cfg.get("fpsDecimal", "1") == "1"
        showClockTime = switchOn("showClock")
        showLatency = switchOn("showLatency")
        showAvg = switchOn("showAvg")
        showLow1 = switchOn("showLow1")
        showLow01 = switchOn("showLow01")
        showDisplay = switchOn("showDisplay")
        showHdr = switchOn("showHdr")
        refreshOrder()
        showPerCore = switchOn("showPerCore")
        showSwap = switchOn("showSwap")
        showNet = switchOn("showNet")
        showResolution = switchOn("showResolution")
        showProton = switchOn("showProton")
        showSession = switchOn("showSession")
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

    private fun showHdrLine(): Boolean = showHdr && hdrState != DroidDeckHudHdr.NONE

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
        headerMark = null; headerWordmark = null; chipRect = null; dPanel = null; dDividerTop = -1f; tabRect = null; stripRect = null
        val s = snap
        if (s == null) { contentW = 0f; contentH = 0f; return }
        if (dock != DroidDeckHudDock.NONE) { buildDockTab(); return }
        if (size == DroidDeckHudSize.STRIP) { buildStrip(s); return }
        buildRows(s)
        if (contentW <= 0f || contentH <= 0f) return
        if (size == DroidDeckHudSize.PILL) addSubtleClock(sp(10f), leftAligned = true)
        val pad = sp(size.style.pad)
        when (size) {
            DroidDeckHudSize.MINIMAL -> intoPanel(pad, header = true, halfRound = true)
            DroidDeckHudSize.PILL -> intoPanel(pad, header = false)
            else -> intoPanel(pad, header = true)
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

    private fun panelInset() = sp(8f)
    private fun panelLeft() = sp(13f)
    private fun contentLeft() = panelLeft() + sp(16f)

    private fun addPanelHeader(left: Float, inner: Float): Pair<Float, Float> {
        val clockPx = sp(9.5f); val gapH = sp(7f)
        val wordH = sp(12f)
        val markTop = inner + gapH
        val wordW = WordmarkArt.widthFor(wordH)
        headerWordmark = RectF(left, markTop, left + wordW, markTop + wordH)
        val midY = markTop + wordH / 2f
        var x = left + wordW
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
        dDividerTop = markTop + wordH + gapH
        return Pair(dDividerTop + max(1f, density), x)
    }

    /**
     * Moves a size's content, laid out from [pad] at the origin, into the panel: under the header, or after
     * the logo chip on the Pill. Lines that start on the content's left edge are lined up by their ink, so
     * the logo, labels and numbers share one edge.
     */
    private fun intoPanel(pad: Float, header: Boolean, halfRound: Boolean = !header) {
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
        finishPanel(panel, halfRound = halfRound)
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
            val paint = measurePaint
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

    private fun switchLabel(key: String): Pair<String, Int> = when (key) {
        "showGpuModel" -> "MODEL" to colGpu
        "showGPULoad" -> "GPU" to colGpu
        "showGpuTemp" -> "GPU.T" to colGpu
        "showCPUUsage" -> "CPU" to colCpu
        "showTemp" -> "CPU.T" to colCpu
        "showBatteryTemp" -> "BAT.T" to colBat
        "showPower" -> "PWR" to colBat
        "showBattery" -> "BAT" to colBat
        "showVram" -> "VRAM" to colVram
        "showRAM" -> "RAM" to colRam
        "showSwap" -> "SWP" to colRam
        "showNet" -> "NET" to colFps
        "showPerCore" -> "CORE" to colCpu
        "showFPS" -> "FPS" to colFps
        "showLatency" -> "MS" to colDim
        "showAvg" -> "AVG" to colLo
        "showLow1" -> "1%" to colLo
        "showLow01" -> "0.1%" to colLo
        "showLow001" -> "0.01%" to colLo
        "showEngine" -> "API" to colFps
        "showDisplay" -> "DISP" to colDisp
        "showHdr" -> "HDR" to colHdr
        "showResolution" -> "RES" to colDim
        "showProton" -> "PROTON" to colVram
        "showSession" -> "TIME" to colDim
        else -> key.removePrefix("show") to colDim
    }

    private fun reading(key: String, s: HudMetrics.Snapshot, labelPx: Float, valuePx: Float, unitPx: Float): HudRow {
        if (key == "showFPSGraph") return HudRow(key, Span("", colDim, 0f), emptyList())
        val (label, color) = switchLabel(key)
        val values: List<Span> = when (key) {
            "showGpuModel" -> if (gpuNameText().isBlank()) numUnit(null, "", valuePx, unitPx)
                else gpuModelSpans(valuePx, colValue)
            "showGPULoad" -> numUnit(s.gpuPercent?.toString(), "%", valuePx, unitPx)
            "showGpuTemp" -> tempSpans(s.gpuTempC, HudMetrics.TempSensor.GPU, valuePx, unitPx)
            "showCPUUsage" -> numUnit(s.cpuPercent?.toString(), "%", valuePx, unitPx)
            "showTemp" -> tempSpans(s.cpuTempC, HudMetrics.TempSensor.CPU, valuePx, unitPx)
            "showBatteryTemp" -> tempSpans(s.battery.tempC, HudMetrics.TempSensor.BATTERY, valuePx, unitPx)
            "showPower" -> numUnit(if (s.battery.watts > 0f) fmt1(s.battery.watts) else null, "W", valuePx, unitPx)
            "showBattery" -> numUnit(s.battery.percent?.toString(), "%", valuePx, unitPx)
            "showVram" -> valueUnit(s.vramText() ?: "—", valuePx, unitPx)
            "showRAM" -> numUnit(s.ramPercent.roundToInt().toString(), "%", valuePx, unitPx)
            "showSwap" -> valueUnit(s.swapUsedText() ?: "—", valuePx, unitPx)
            "showNet" -> listOf(
                Span("↓", colDim, unitPx), Span(((s.netDownBps ?: 0L) / 1024L).toString(), colValue, valuePx),
                Span(" ↑", colDim, unitPx), Span(((s.netUpBps ?: 0L) / 1024L).toString(), colValue, valuePx),
            )
            "showPerCore" -> listOf(Span(s.perCorePercent.size.toString(), colValue, valuePx))
            "showFPS" -> glowing(numUnit(fpsText(fpsNow), "", valuePx, unitPx))
            "showLatency" -> numUnit(fmt1(1000f / max(fpsNow, 1f)), "ms", valuePx, unitPx)
            "showAvg" -> numUnit(fmt1(fpsAvg), "", valuePx, unitPx)
            "showLow1" -> numUnit(lowText(lows.low1Fps), "", valuePx, unitPx)
            "showLow01" -> numUnit(lowText(lows.low01Fps), "", valuePx, unitPx)
            "showLow001" -> numUnit(lowText(lows.low001Fps), "", valuePx, unitPx)
            "showEngine" -> listOf(Span(engineLabel.ifBlank { "—" }, colValue, valuePx))
            "showDisplay" -> listOf(Span(displayServer.ifBlank { "—" }, colDisp, valuePx))
            "showHdr" -> if (showHdrLine()) hdrValue(valuePx) else listOf(Span("off", colDim, valuePx))
            "showResolution" -> listOf(Span(resolutionString(), colValue, valuePx))
            "showProton" -> listOf(Span(wineVersion.ifBlank { "—" }, colVram, valuePx))
            "showSession" -> listOf(Span(elapsedString(), colValue, valuePx))
            else -> listOf(Span("—", colDim, valuePx))
        }
        return HudRow(key, Span(label, color, labelPx), values.ifEmpty { listOf(Span("—", colDim, valuePx)) })
    }

    private fun buildRows(s: HudMetrics.Snapshot) {
        val st = size.style
        val pad = sp(st.pad); val lineGap = sp(st.lineGap); val lvGap = sp(st.labelGap)
        val gutter = sp(if (st.gutter > 0f) st.gutter else 14f)
        val unitPx = sp(if (st.unit > 0f) st.unit else st.row * 0.62f)
        val basePx = sp(if (st.sub > 0f) st.sub else st.row)

        val keys = size.rowsIn(storedOrder).filter { switchOn(it) }
        if (keys.isEmpty()) { contentW = 0f; contentH = 0f; return }
        val hero = (size == DroidDeckHudSize.PILL || size == DroidDeckHudSize.MINIMAL) && keys.firstOrNull() == "showFPS"
        val tiles = size == DroidDeckHudSize.TILES
        val bigPx = sp(st.row); val bigUnitPx = sp(st.unit)
        val heroSpans = listOf(
            Span(fpsText(fpsNow), colValue, bigPx, glow = true),
            Span("fps", colDim, bigUnitPx),
        )
        val listed = keys.filter { !hero || it != "showFPS" }.flatMap { key ->
            val labelPx = if (tiles) sp(st.key) else basePx
            val valuePx = if (tiles) sp(st.row) else basePx
            if (key == "showPerCore" && size == DroidDeckHudSize.MEGA)
                perCoreRows(s, labelPx, valuePx, unitPx)
            else listOf(reading(key, s, labelPx, valuePx, unitPx))
        }
        val maxCols = if (size == DroidDeckHudSize.MEGA) 3 else 2
        val cols = when {
            listed.size >= 9 -> maxCols
            listed.size >= 4 -> minOf(2, maxCols)
            else -> 1
        }

        if (tiles) {
            val innerPad = sp(st.innerPad); val tileGap = sp(st.tileGap)
            val keyH = lineH(sp(st.key)); val valH = lineH(sp(st.row))
            val cellW = listed.maxOf { max(measure(it.label.text, it.label.sizePx), runWidth(it.vals)) } + innerPad * 2f
            val graphMin = if (listed.any { it.key == "showFPSGraph" }) sp(GRAPH_MIN_WIDTH_SP) else 0f
            val cellMin = graphMin + innerPad * 2f
            val cellH = innerPad * 2f + keyH + lineGap + valH
            for ((i, r) in listed.withIndex()) {
                val x = pad + (i % cols) * (cellW + tileGap)
                val y = pad + (i / cols) * (cellH + tileGap)
                tileRects.add(RectF(x, y, x + cellW, y + cellH))
                if (r.key == "showFPSGraph") {
                    graphRect = RectF(x + innerPad, y + innerPad, x + max(cellW, cellMin) - innerPad, y + cellH - innerPad)
                    continue
                }
                placeRun(x + innerPad, y + innerPad - ascent(r.label.sizePx), listOf(r.label))
                placeRun(x + innerPad, y + innerPad + keyH + lineGap - ascent(sp(st.row)), r.vals)
            }
            val lines = (listed.size + cols - 1) / cols
            contentW = pad + cols * (cellW + tileGap) - tileGap + pad
            contentH = pad + lines * (cellH + tileGap) - tileGap + pad
        } else {
            val columns = (0 until cols).map { c -> listed.filterIndexed { i, _ -> i % cols == c } }
            val labelW = columns.map { col -> col.maxOfOrNull { measure(it.label.text, it.label.sizePx) } ?: 0f }
            val colW = columns.mapIndexed { c, col ->
                val body = if (col.isEmpty()) 0f else labelW[c] + lvGap + (col.maxOfOrNull { runWidth(it.vals) } ?: 0f)
                val graph = if (col.any { it.key == "showFPSGraph" }) sp(GRAPH_MIN_WIDTH_SP) + labelW[c] + lvGap else 0f
                max(body, graph)
            }
            val gaps = (0 until cols).count { columns[it].isNotEmpty() }.coerceAtLeast(1) - 1
            val bodyW = colW.sum() + gutter * gaps
            var bodyH = 0f
            for (col in columns) {
                var y = 0f
                for (r in col) y += rowHeight(r) + lineGap
                if (col.isNotEmpty()) bodyH = max(bodyH, y - lineGap)
            }
            val heroW = if (hero) runWidth(heroSpans) else 0f
            val heroH = if (hero) lineH(bigPx) else 0f
            val x0 = pad + if (hero && listed.isNotEmpty()) heroW + gutter else 0f
            val top = pad + (max(bodyH, heroH) - bodyH) / 2f
            var x = x0
            for (c in 0 until cols) {
                if (columns[c].isEmpty()) continue
                var y = top
                for (r in columns[c]) {
                    if (r.key == "showFPSGraph") {
                        val left = x + labelW[c] + lvGap
                        graphRect = RectF(left, y, max(x + colW[c], left + sp(GRAPH_MIN_WIDTH_SP)), y + sp(GRAPH_HEIGHT_SP))
                        y += sp(GRAPH_HEIGHT_SP) + lineGap
                        continue
                    }
                    val px = maxOf(r.label.sizePx, r.vals.maxOfOrNull { it.sizePx } ?: 0f)
                    val baseline = y - ascent(px)
                    placeRun(x, baseline, listOf(r.label))
                    placeRun(x + labelW[c] + lvGap, baseline, r.vals)
                    y += lineH(px) + lineGap
                }
                x += colW[c] + gutter
            }
            if (hero) {
                val baseline = pad + (max(bodyH, heroH) - heroH) / 2f - ascent(bigPx)
                placeRun(pad, baseline, heroSpans, glow = true)
            }
            contentW = max(x0 + bodyW, pad + heroW) + pad
            contentH = pad + max(bodyH, heroH) + pad
        }
    }

    private fun rowHeight(r: HudRow): Float =
        if (r.key == "showFPSGraph") sp(GRAPH_HEIGHT_SP)
        else lineH(maxOf(r.label.sizePx, r.vals.maxOfOrNull { it.sizePx } ?: 0f))

    private fun perCoreRows(s: HudMetrics.Snapshot, labelPx: Float, valuePx: Float, unitPx: Float): List<HudRow> {
        val pct = s.perCorePercent
        val clk = s.perCoreClockMhz
        return (0 until max(pct.size, clk.size)).map { i ->
            val vals = ArrayList<Span>()
            vals += numUnit(if (i < pct.size && pct[i] >= 0) pct[i].toString() else null, "%", valuePx, unitPx)
            vals += Span(" ", colDim, unitPx)
            vals += numUnit(if (i < clk.size && clk[i] > 0) clk[i].toString() else null, "MHz", valuePx, unitPx)
            HudRow("showPerCore", Span("C$i", colCpu, labelPx), vals)
        }
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
        for (key in size.rowsIn(storedOrder)) {
            if (!switchOn(key)) continue
            if (key == "showFPSGraph") {
                val gw = sp(72f); val gh = h * 0.55f
                if (x + gw > right) break
                graphRect = RectF(x, mid - gh / 2f, x + gw, mid + gh / 2f)
                x += gw + groupGap
                continue
            }
            val spans = if (key == "showFPS") {
                val fps = ArrayList<Span>()
                if (apiLabel() != "FPS") fps += Span("${apiLabel()} ", colDim, rowPx)
                fps += Span(fpsText(fpsNow), colValue, if (generating) fpsPx * 0.8f else fpsPx, glow = true)
                fps += Span("fps", colDim, unitPx)
                fps
            } else {
                val row = reading(key, s, rowPx, rowPx, unitPx)
                listOf(row.label, Span(" ", colDim, rowPx)) + row.vals
            }
            if (x + runWidth(spans) > right) break
            x = placeRun(x, baseline, spans) + groupGap
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
            val paint = drawPaint
            paint.textSize = g.sizePx
            paint.color = g.color
            if (g.glow) paint.setShadowLayer(sp(6f), 0f, 0f, withAlpha(colGraph, 217))
            canvas.drawText(g.text, g.x, g.baseline, paint)
            if (g.glow) paint.clearShadowLayer()
        }
        headerMark?.let {
            if (stripRect != null && layerType != LAYER_TYPE_SOFTWARE) drawCachedMark(canvas, it)
            else DroidDeckMark.draw(canvas, it.left, it.top, it.height(), blend(colValue), accent)
        }
        headerWordmark?.let { WordmarkArt.draw(canvas, it.left, it.top, it.height(), blend(colValue), accent) }

        graphRect?.let {
            if (stripRect != null && layerType != LAYER_TYPE_SOFTWARE) drawCachedGraph(canvas, it) else drawGraph(canvas, it)
        }

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

    private var cachedGraphBitmap: Bitmap? = null
    private var cachedGraphCanvas: Canvas? = null
    private val cachedGraphRect = RectF()
    private var cachedMarkBitmap: Bitmap? = null
    private var cachedMarkKey = ""

    private fun drawCachedGraph(canvas: Canvas, rect: RectF) {
        val w = rect.width().roundToInt().coerceAtLeast(1)
        val h = rect.height().roundToInt().coerceAtLeast(1)
        var bitmap = cachedGraphBitmap
        if (bitmap == null || bitmap.width != w || bitmap.height != h) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            cachedGraphBitmap = bitmap
            cachedGraphCanvas = Canvas(bitmap)
            cachedGraphRect.set(0f, 0f, w.toFloat(), h.toFloat())
        }
        bitmap.eraseColor(Color.TRANSPARENT)
        drawGraph(cachedGraphCanvas!!, cachedGraphRect)
        canvas.drawBitmap(bitmap, rect.left, rect.top, null)
    }

    private fun drawCachedMark(canvas: Canvas, rect: RectF) {
        val w = rect.width().roundToInt().coerceAtLeast(1)
        val h = rect.height().roundToInt().coerceAtLeast(1)
        val key = "$w,$h,${blend(colValue)},$accent"
        var bitmap = cachedMarkBitmap
        if (bitmap == null || key != cachedMarkKey) {
            bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            DroidDeckMark.draw(Canvas(bitmap), 0f, 0f, h.toFloat(), blend(colValue), accent)
            cachedMarkBitmap = bitmap
            cachedMarkKey = key
        }
        canvas.drawBitmap(bitmap, rect.left, rect.top, null)
    }

    private fun drawStrip(canvas: Canvas, rect: RectF) {
        val p = parent as? View
        val atBottom = p != null && p.height > 0 && y + height / 2f > p.height / 2f
        stripFrame(rect, atBottom)?.let { canvas.drawBitmap(it, 0f, 0f, null) }
    }

    private fun stripFrame(rect: RectF, atBottom: Boolean): Bitmap? {
        val w = rect.width().roundToInt(); val h = rect.height().roundToInt()
        if (w <= 0 || h <= 0) return null
        val stroke = outlineWidth()
        val key = "$w,$h,$stroke,$atBottom,$surfaceRgb,$bgOpacity,$outlineIntensity,${outlineColor()}"
        if (key == sFrameKey) sFrame?.let { return it }
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        headerPaint.shader = null
        headerPaint.color = panelColor()
        c.drawRect(rect, headerPaint)
        if (stroke > 0f) {
            val line = max(stroke / 2f, density)
            headerPaint.color = withAlpha(outlineColor(), (110 + 145 * outlineIntensity).roundToInt().coerceAtMost(255))
            if (atBottom) c.drawRect(rect.left, rect.top, rect.right, rect.top + line, headerPaint)
            else c.drawRect(rect.left, rect.bottom - line, rect.right, rect.bottom, headerPaint)
        }
        sFrame = bmp; sFrameKey = key
        return bmp
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
        const val TAB_NUM_SP = 15f
        const val TAB_LABEL_SP = 7.25f
        const val TAB_PAD_X_SP = 7f
        const val TAB_PAD_Y_SP = 5.5f
        const val REFRESH_MS = 500L
        const val STRIP_GRAPH_REDRAW_MS = 66L
        private const val HUE_IDLE_MS = 500L
        const val MIN_SCALE = 0.5f
        const val MAX_SCALE = 2f
        const val GRAPH_CLIP_MS = 49.9f
        const val GRAPH_HEIGHT_SP = 22f
        const val GRAPH_MIN_WIDTH_SP = 160f

        fun parseIntOr(s: String?, d: Int): Int = try { s?.trim()?.toInt() ?: d } catch (e: Exception) { d }

        fun parseOutline(v: String?): Int = when (v?.trim()?.lowercase(Locale.US)) {
            null -> 40; "off" -> 0; "soft" -> 40; "strong" -> 70
            else -> v.trim().toIntOrNull()?.coerceIn(0, 100) ?: 40
        }
    }
}
