package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.DroidDeckMark
import com.droiddeck.launcher.hud.FrameStats
import com.droiddeck.launcher.hud.HudMetrics
import com.droiddeck.launcher.session.StatsStrip
import com.droiddeck.launcher.session.StripMetric
import kotlin.math.max
import kotlin.math.roundToInt

/** FusionHUD's metric colors, so the bottom screen reads like the HUD on the top screen. */
internal object StatsColors {
    val cpu = Color(0xFF58A6FF)
    val gpu = Color(0xFF5EE08A)
    val ram = Color(0xFFFF7BC0)
    val bat = Color(0xFFFFAB5E)
    // Throttle and brake stay apart for red-green colour blindness (checked against the dark surface).
    val throttle = Color(0xFF14A39A)
    val brake = Color(0xFFE5484D)
    val speed = Color(0xFF2E8FD6)
    val warn = Color(0xFFF2C94C)
    val shiftGreen = Color(0xFF2FB36A)
    val badge = Color(0xFF0B0D10)
}

/** The last [capacity] readings, oldest first. */
internal class History(private val capacity: Int) {
    private val values = ArrayDeque<Float>(capacity)
    fun add(v: Float) {
        if (values.size == capacity) values.removeFirst()
        values.addLast(v)
    }
    fun snapshot(): List<Float> = values.toList()
}

private val MARK_RATIO = DroidDeckMark.widthFor(1f)

/** The DroidDeck mark (the split D with the blue orb), the same artwork the HUD draws. */
@Composable
internal fun DroidDeckLogo(height: Dp, modifier: Modifier = Modifier, body: Color = Color.White, orb: Color = LocalPalette.current.primary) {
    Canvas(modifier.size(height * MARK_RATIO, height)) {
        val b = body.toArgb()
        val o = orb.toArgb()
        drawIntoCanvas { DroidDeckMark.draw(it.nativeCanvas, 0f, 0f, size.height, b, o) }
    }
}

@Composable
private fun LogoBadge(size: Dp) {
    val shape = RoundedCornerShape(size * 0.22f)
    Box(Modifier.size(size).clip(shape).background(StatsColors.badge).border(2.dp, LocalPalette.current.primary, shape), contentAlignment = Alignment.Center) {
        DroidDeckLogo(size * 0.62f)
    }
}

/** The mark and "DroidDeck" on the left, the running game's name in the middle, the clock on the right. */
@Composable
private fun LogoHeader(clock: String, game: String?) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().padding(horizontal = 2.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        DroidDeckLogo(16.dp)
        Text("DroidDeck", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
        Text(game.orEmpty(), fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, textAlign = TextAlign.Center,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        if (clock.isNotEmpty()) Text(clock, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
    }
}

@Composable
internal fun Sparkline(values: List<Float>, min: Float, max: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2 || max <= min) return@Canvas
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i / (values.size - 1f) * size.width
            val y = size.height - ((v - min) / (max - min)).coerceIn(0f, 1f) * (size.height - 2f) - 1f
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

/** A readout card: dark surface, a thin accent edge and the DroidDeck mark before the title ("" = mark only). */
@Composable
internal fun StatsCard(title: String?, modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(14.dp)
    Column(
        modifier.clip(shape).background(pal.surface).border(1.dp, pal.primary.copy(alpha = 0.22f), shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (title != null) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            DroidDeckLogo(12.dp, body = MaterialTheme.colorScheme.onSurfaceVariant)
            if (title.isNotEmpty()) Text(title.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = pal.primary, letterSpacing = 0.6.sp,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        content()
    }
}

/** What the top strip shows; histories hold one reading a second, oldest first. */
internal class StripData(
    val fps: Float?,
    val device: HudMetrics.Snapshot?,
    val fpsHistory: List<Float>,
    val cpuHistory: List<Float>,
    val gpuHistory: List<Float>,
    val wattsHistory: List<Float>,
    val clock: String,
    val game: String? = null,
    /** The Line strip's extras: graphics API, frame-time lows, the frame-time graph (ms, oldest first). */
    val engine: String = "",
    val lows: FrameStats.Lows? = null,
    val graph: FloatArray? = null,
    val metrics: Set<StripMetric> = StripMetric.defaults,
    val fahrenheit: Boolean = false,
    val tempColor: ((HudMetrics.TempSensor, Int) -> Color)? = null,
)

private fun percentTemp(percent: Int?, temp: Int?): String =
    listOfNotNull(percent?.let { "$it%" }, temp?.let { "$it°" }).joinToString(" · ").ifEmpty { "–" }

private fun batteryText(s: HudMetrics.Snapshot?): String =
    listOfNotNull(s?.battery?.percent?.let { "$it%" }, s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1f W".format(it) }).joinToString(" · ").ifEmpty { "–" }

private fun fpsText(fps: Float?): String = fps?.takeIf { it > 0f }?.let { "%.1f".format(it) } ?: "–"

/** The top strip; a tap moves to the next style. */
@Composable
internal fun StatsStripView(style: StatsStrip, d: StripData, onTap: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onTap)) {
        when (style) {
            StatsStrip.LINE -> LineStrip(d)
            StatsStrip.PANEL -> PanelStrip(d)
            StatsStrip.TILES -> TilesStrip(d)
            StatsStrip.RINGS -> RingsStrip(d)
        }
    }
}

/** The top-screen HUD's text colours on its dark panel. */
private object LineInk {
    val value = Color(0xFFF2F5F9)
    val dim = Color(0xFF9AA4B2)
    val vram = Color(0xFFC98BFF)
}

private class LineItem(val priority: Int, val content: @Composable () -> Unit)

/**
 * One line like the top-screen HUD's Strip: API and FPS, frame-time graph, lows, GPU, CPU, VRAM, RAM and the clock.
 * When the line is too narrow the least important numbers are left out (the FPS stays); the clock sits at the right.
 */
@Composable
internal fun LineStrip(d: StripData, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(12.dp)
    val m = d.metrics
    val s = d.device
    val items = ArrayList<LineItem>()
    items += LineItem(0) { DroidDeckLogo(16.dp) }
    if (StripMetric.ENGINE in m && d.engine.isNotEmpty()) items += LineItem(70) { LineText(d.engine, LineInk.dim, 12.sp) }
    items += LineItem(0) {
        Row(verticalAlignment = Alignment.Bottom) {
            LineText(fpsText(d.fps), LineInk.value, 17.sp)
            LineText("fps", LineInk.dim, 10.sp, Modifier.padding(start = 2.dp, bottom = 2.dp))
        }
    }
    val graph = d.graph
    if (StripMetric.GRAPH in m && graph != null) items += LineItem(60) {
        Sparkline(graph.asList(), 0f, FrameStats.GRAPH_MAX_MS, lerp(pal.primary, Color.White, 0.3f), Modifier.width(56.dp).height(16.dp))
    }
    if (StripMetric.LOWS in m) {
        items += LineItem(10) { LinePair("1%", LineInk.dim, lowText(d.lows?.low1Fps), StatsColors.cpu) }
        items += LineItem(90) { LinePair("0.1%", LineInk.dim, lowText(d.lows?.low01Fps), StatsColors.cpu) }
    }
    if (StripMetric.LOW001 in m) items += LineItem(100) { LinePair("0.01%", LineInk.dim, lowText(d.lows?.low001Fps), StatsColors.cpu) }
    if (StripMetric.GPU in m || StripMetric.GPU_TEMP in m) items += LineItem(20) {
        LineMetric("GPU", StatsColors.gpu, if (StripMetric.GPU in m) s?.gpuPercent else null,
            if (StripMetric.GPU_TEMP in m) s?.gpuTempC else null, HudMetrics.TempSensor.GPU, d)
    }
    if (StripMetric.CPU in m || StripMetric.CPU_TEMP in m) items += LineItem(30) {
        LineMetric("CPU", StatsColors.cpu, if (StripMetric.CPU in m) s?.cpuPercent else null,
            if (StripMetric.CPU_TEMP in m) s?.cpuTempC else null, HudMetrics.TempSensor.CPU, d)
    }
    if (StripMetric.VRAM in m) items += LineItem(80) { LinePair("VRAM", LineInk.vram, gibText(s?.vramUsedBytes), LineInk.vram) }
    if (StripMetric.RAM in m) items += LineItem(40) { LinePair("RAM", StatsColors.ram, s?.let { "${it.ramPercent.roundToInt()}%" } ?: "–", StatsColors.ram) }
    val clock = StripMetric.CLOCK in m && d.clock.isNotEmpty()
    if (clock) items += LineItem(50) { LineText(d.clock, LineInk.dim, 12.sp) }
    PriorityRow(
        IntArray(items.size) { items[it].priority }, 10.dp, pinLast = clock,
        modifier = modifier.fillMaxWidth().clip(shape).background(pal.surface).border(1.dp, pal.primary.copy(alpha = 0.35f), shape)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        items.forEach { it.content() }
    }
}

/** A row that leaves out the children that do not fit, by [priorities] (see [fitByPriority]); [pinLast] right-aligns the last one. */
@Composable
private fun PriorityRow(priorities: IntArray, spacing: Dp, pinLast: Boolean, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Layout(content, modifier) { measurables, constraints ->
        val gap = spacing.roundToPx()
        val loose = Constraints(maxHeight = constraints.maxHeight)
        val placeables = measurables.map { it.measure(loose) }
        val max = if (constraints.hasBoundedWidth) constraints.maxWidth else Int.MAX_VALUE
        val keep = fitByPriority(IntArray(placeables.size) { placeables[it].width }, priorities.copyOf(placeables.size), gap, max)
        val height = maxOf(constraints.minHeight, placeables.filterIndexed { i, _ -> keep[i] }.maxOfOrNull { it.height } ?: 0)
        val used = placeables.filterIndexed { i, _ -> keep[i] }.sumOf { it.width } + gap * maxOf(0, keep.count { it } - 1)
        val width = if (constraints.hasBoundedWidth) constraints.maxWidth else used
        layout(width, height) {
            var x = 0
            val last = placeables.lastIndex
            placeables.forEachIndexed { i, p ->
                if (!keep[i]) return@forEachIndexed
                val px = if (pinLast && i == last) width - p.width else x
                p.placeRelative(px, (height - p.height) / 2)
                x += p.width + gap
            }
        }
    }
}

@Composable
private fun LineText(text: String, color: Color, size: TextUnit = 13.sp, modifier: Modifier = Modifier) {
    Text(text, fontSize = size, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color, maxLines = 1, softWrap = false,
        modifier = modifier)
}

@Composable
private fun LinePair(label: String, labelColor: Color, value: String, valueColor: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LineText(label, labelColor, 11.sp)
        LineText(value, valueColor)
    }
}

@Composable
private fun LineMetric(label: String, color: Color, percent: Int?, tempC: Int?, sensor: HudMetrics.TempSensor, d: StripData) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        LineText(label, color, 11.sp)
        if (percent == null && tempC == null) LineText("–", color)
        if (percent != null) LineText("$percent%", color)
        if (tempC != null) LineText(tempText(tempC, d.fahrenheit), d.tempColor?.invoke(sensor, tempC) ?: LineInk.value)
    }
}

private fun lowText(fps: Float?): String = fps?.takeIf { it > 0f }?.let { "%.1f".format(it) } ?: "–"

private fun gibText(bytes: Long?): String = bytes?.let { "%.1fGiB".format(it / 1073741824.0) } ?: "–"

private fun tempText(c: Int, fahrenheit: Boolean): String = if (fahrenheit) "${(c * 9f / 5f + 32f).roundToInt()}°F" else "$c°C"

@Composable
private fun PanelStrip(d: StripData) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val accent = pal.primary
    val shape = RoundedCornerShape(18.dp)
    Row(
        Modifier.fillMaxWidth().clip(shape).background(pal.surface).border(2.dp, accent, shape).padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        LogoBadge(40.dp)
        Column(Modifier.widthIn(min = 84.dp)) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(fpsText(d.fps), fontSize = 30.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = accent, maxLines = 1)
                Text(" fps", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 4.dp))
            }
            Text(d.fps?.takeIf { it > 0f }?.let { "%.1f ms".format(1000f / it) } ?: stringResource(R.string.stats_fps_needs_hud),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, maxLines = 1)
        }
        Sparkline(d.fpsHistory.map { 1000f / max(it, 1f) }, 8f, 34f, lerp(accent, Color.White, 0.3f), Modifier.weight(1f).height(44.dp))
        Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
            MetricRow("CPU", percentTemp(d.device?.cpuPercent, d.device?.cpuTempC), StatsColors.cpu)
            MetricRow("GPU", percentTemp(d.device?.gpuPercent, d.device?.gpuTempC), StatsColors.gpu)
            MetricRow("RAM", d.device?.let { "${it.ramPercent.roundToInt()}%" } ?: "–", StatsColors.ram)
            MetricRow("BAT", batteryText(d.device), StatsColors.bat)
        }
    }
}

@Composable
private fun MetricRow(label: String, value: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = color, modifier = Modifier.width(36.dp))
        Text(value, fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

@Composable
private fun TilesStrip(d: StripData) {
    val accent = LocalPalette.current.primary
    val s = d.device
    val gpuPercent: Int? = s?.gpuPercent
    val gpuTemp: Int? = s?.gpuTempC
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LogoHeader(d.clock, d.game)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HistoryTile("FPS", accent, fpsText(d.fps), "fps", d.fpsHistory, 75f)
            HistoryTile("CPU", StatsColors.cpu, s?.cpuPercent?.let { "$it%" } ?: "–", s?.cpuTempC?.let { "$it°" } ?: "", d.cpuHistory, 100f)
            HistoryTile("GPU", StatsColors.gpu, gpuPercent?.let { "$it%" } ?: gpuTemp?.let { "$it°" } ?: "–",
                if (gpuPercent != null) gpuTemp?.let { "$it°" } ?: "" else "", d.gpuHistory, 100f)
            HistoryTile(stringResource(R.string.bottom_telemetry_battery).uppercase(), StatsColors.bat, s?.battery?.percent?.let { "$it%" } ?: "–",
                s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1fW".format(it) } ?: "", d.wattsHistory, 10f)
        }
    }
}

@Composable
private fun RowScope.HistoryTile(label: String, color: Color, value: String, unit: String, history: List<Float>, max: Float) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(pal.surface)
            .drawBehind { drawRect(color, topLeft = Offset.Zero, size = Size(size.width, 3.dp.toPx())) }
            .padding(start = 10.dp, end = 10.dp, top = 9.dp, bottom = 6.dp),
    ) {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.8.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = colors.onBackground, maxLines = 1)
            if (unit.isNotEmpty()) Text(" $unit", fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, modifier = Modifier.padding(bottom = 3.dp))
        }
        Sparkline(history, 0f, max, color, Modifier.fillMaxWidth().height(24.dp))
    }
}

@Composable
private fun RingsStrip(d: StripData) {
    val s = d.device
    val gpuPercent: Int? = s?.gpuPercent
    val gpuTemp: Int? = s?.gpuTempC
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        LogoHeader(d.clock, d.game)
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            RingTile("CPU", StatsColors.cpu, s?.cpuPercent?.let { it / 100f }, s?.cpuPercent?.let { "$it%" } ?: "–", s?.cpuTempC?.let { "$it °C" } ?: "")
            RingTile("GPU", StatsColors.gpu, gpuPercent?.let { it / 100f }, gpuPercent?.let { "$it%" } ?: gpuTemp?.let { "$it°" } ?: "–",
                if (gpuPercent != null) gpuTemp?.let { "$it °C" } ?: "" else "")
            RingTile("RAM", StatsColors.ram, s?.ramPercent?.let { it / 100f }, s?.let { "${it.ramPercent.roundToInt()}%" } ?: "–",
                s?.let { "%.1f of %.1f GB".format(it.ramUsedBytes / 1e9, it.ramTotalBytes / 1e9) } ?: "")
            RingTile(stringResource(R.string.bottom_telemetry_battery).uppercase(), StatsColors.bat, s?.battery?.percent?.let { it / 100f },
                s?.battery?.percent?.let { "$it%" } ?: "–", s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1f W".format(it) } ?: "")
        }
    }
}

@Composable
private fun RowScope.RingTile(label: String, color: Color, fraction: Float?, center: String, sub: String) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Row(
        Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).background(pal.surface).padding(8.dp),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box(Modifier.size(52.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = 6.dp.toPx()
                val tl = Offset(w / 2, w / 2)
                val sz = Size(size.width - w, size.height - w)
                drawArc(pal.line, 0f, 360f, false, topLeft = tl, size = sz, style = Stroke(width = w))
                if (fraction != null) drawArc(color, -90f, (fraction.coerceIn(0f, 1f) * 360f).coerceAtLeast(1f), false, topLeft = tl, size = sz,
                    style = Stroke(width = w, cap = StrokeCap.Round))
            }
            Text(center, fontSize = 12.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = colors.onBackground, maxLines = 1)
        }
        Column {
            Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = color, letterSpacing = 0.8.sp, maxLines = 1)
            if (sub.isNotEmpty()) Text(sub, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}
