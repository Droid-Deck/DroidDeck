package com.droiddeck.launcher.ui

import android.graphics.BlurMaskFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Shader
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawWithCache
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.nativeCanvas
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.hud.DroidDeckMark
import com.droiddeck.launcher.hud.FrameStats
import com.droiddeck.launcher.hud.HudMetrics
import com.droiddeck.launcher.session.StatsHud
import java.io.File
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Cores that share a top clock (one cluster of the CPU). */
internal class CoreGroup(val cores: List<Int>, val maxGhz: Double?)

/** What the bottom-screen HUD draws; histories hold one reading a second, oldest first. */
internal class HudData(
    val fps: Float?,
    val avgFps: Float?,
    val lows: FrameStats.Lows,
    /** The last FrameStats.GRAPH_FRAMES frame times in ms, oldest first, zeros until filled. */
    val graph: FloatArray,
    val slowPerMinute: Int?,
    val engine: String,
    val resolution: String,
    val refreshHz: Int,
    val proton: String?,
    val device: HudMetrics.Snapshot?,
    val gpuName: String,
    val fanRpm: Long?,
    val title: String?,
    val cover: File?,
    val played: String?,
    val clock: String,
    val fpsHistory: List<Float>,
    val cpuHistory: List<Float>,
    val gpuHistory: List<Float>,
    val coreGroups: List<CoreGroup>,
    val gameCores: Set<Int>,
    val fahrenheit: Boolean,
    val tempColor: (HudMetrics.TempSensor, Int) -> Color,
    val redC: (HudMetrics.TempSensor) -> Int?,
) {
    val frametimeMs: Float? get() = fps?.takeIf { it > 0f }?.let { 1000f / it }
    val hz: Int get() = if (refreshHz > 0) refreshHz else 60
}

/** FusionHUD's text colours on its dark panel. */
private object HudInk {
    val value = Color(0xFFF2F5F9)
    val dim = Color(0xFF9AA4B2)
    val low = Color(0xFFE4E8EE)
    val vram = Color(0xFFC98BFF)
    val sep = Color(0xFF5D6876)
    val header = Color(0xFFEAF4FF)
    val amber = Color(0xFFFFB74D)
    val tile = Color(0x0EFFFFFF)
}

private val Mono = FontFamily.Monospace
private val HEADER = 40.dp
private const val UNIT = 0.62f

/** The HUD for the bottom screen, in the top-screen HUD's D-shaped panel; a tap moves to the next style. */
@Composable
internal fun StatsHudView(style: StatsHud, d: HudData, modifier: Modifier, onTap: () -> Unit) {
    val accent = LocalPalette.current.primary
    val night = style == StatsHud.NIGHT && !nightAwake(d)
    Box(modifier.clickable(onClick = onTap)) {
        HudPanel(d.clock, if (style == StatsHud.GRAPHS) stringResource(R.string.stats_hud_last_seconds, d.fpsHistory.size) else null, night, accent) {
            when (style) {
                StatsHud.BIG_FPS -> BigFps(d, accent)
                StatsHud.FULL -> Full(d, accent)
                StatsHud.TILES -> Tiles(d, accent)
                StatsHud.RINGS -> Rings(d, accent)
                StatsHud.GRAPHS -> Graphs(d, accent)
                StatsHud.CORES -> Cores(d, accent)
                StatsHud.HEAT -> Heat(d)
                StatsHud.GAME -> GameCard(d, accent)
                StatsHud.PACING -> Pacing(d, accent)
                StatsHud.NIGHT -> Night(d, night)
                StatsHud.MEGA -> Mega(d, accent)
            }
        }
    }
}

private fun nightAwake(d: HudData): Boolean = d.fps?.let { it > 0f && it < nightFloor(d) } == true

private fun nightFloor(d: HudData): Int = (d.hz * 0.83f).roundToInt()

// ---- panel ----------------------------------------------------------------------------------

@Composable
private fun HudPanel(clock: String, right: String?, dim: Boolean, accent: Color, content: @Composable ColumnScope.() -> Unit) {
    val headerInk = if (dim) Color(0xFF7D8794) else HudInk.header
    Column(
        Modifier.fillMaxSize()
            .drawWithCache {
                val w = size.width
                val h = size.height
                val stroke = 2.dp.toPx()
                val corner = 7.dp.toPx()
                val radius = min(h / 2f, 64.dp.toPx())
                val headerPx = HEADER.toPx()
                val a = accent.toArgb()
                val body = dPath(0f, 0f, w, h, radius, corner)
                val half = stroke / 2f
                val outline = dPath(half, half, w - half, h - half, radius - half, corner)
                val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = if (dim) argb(245, 0xFF040507.toInt()) else argb(240, 0xFF0E1014.toInt()) }
                val band = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    shader = LinearGradient(0f, 0f, w, 0f, argb(if (dim) 20 else 56, a), argb(0, a), Shader.TileMode.CLAMP)
                }
                val divider = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = argb(if (dim) 38 else 90, a) }
                // Android before 9 skips blur on hardware-drawn shapes; there the outline is drawn without its glow.
                val blur = BlurMaskFilter(4.dp.toPx(), BlurMaskFilter.Blur.NORMAL)
                val line = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeWidth = stroke; color = argb(if (dim) 90 else 255, a) }
                val markH = min(h, w) * 0.55f
                val markX = w - DroidDeckMark.widthFor(markH) - 16.dp.toPx()
                val markBody = android.graphics.Color.argb(if (dim) 9 else 20, 255, 255, 255)
                val markOrb = argb(if (dim) 9 else 20, a)
                onDrawBehind {
                    drawIntoCanvas { canvas ->
                        val c = canvas.nativeCanvas
                        c.drawPath(body, fill)
                        c.save()
                        c.clipPath(body)
                        c.drawRect(0f, 0f, w, headerPx + 1f, band)
                        c.drawRect(0f, headerPx, w, headerPx + max(1f, density), divider)
                        DroidDeckMark.draw(c, markX, (h - markH) / 2f, markH, markBody, markOrb)
                        c.restore()
                        for (pass in 0..2) {
                            line.maskFilter = if (pass < 2) blur else null
                            c.drawPath(outline, line)
                        }
                        line.maskFilter = null
                    }
                }
            }
            .padding(start = 24.dp, end = 34.dp, bottom = 14.dp),
    ) {
        Row(Modifier.fillMaxWidth().height(HEADER), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            DroidDeckLogo(16.dp, body = headerInk, orb = if (dim) accent.copy(alpha = 0.5f) else accent)
            Text("DroidDeck", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = headerInk, maxLines = 1)
            if (clock.isNotEmpty()) {
                Text("·", fontSize = 13.sp, fontFamily = Mono, color = HudInk.sep)
                Text(clock, fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, color = HudInk.dim, maxLines = 1)
            }
            if (right != null) {
                Spacer(Modifier.weight(1f))
                Text(right, fontSize = 13.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, color = HudInk.dim, maxLines = 1)
            }
        }
        Spacer(Modifier.height(12.dp))
        content()
    }
}

private fun argb(alpha: Int, rgb: Int): Int = (alpha shl 24) or (rgb and 0xFFFFFF)

/** The top-screen HUD's D: straight on the left, round on the right. */
private fun dPath(l: Float, t: Float, r: Float, b: Float, rad: Float, corner: Float) = android.graphics.Path().apply {
    moveTo(l, t + corner)
    lineTo(l, b - corner); quadTo(l, b, l + corner, b)
    lineTo(r - rad, b)
    arcTo(r - 2f * rad, b - 2f * rad, r, b, 90f, -90f, false)
    lineTo(r, t + rad)
    arcTo(r - 2f * rad, t, r, t + 2f * rad, 0f, -90f, false)
    lineTo(l + corner, t); quadTo(l, t, l, t + corner)
    close()
}

// ---- text and pieces ------------------------------------------------------------------------

private fun f1(v: Float?): String = v?.takeIf { it > 0f }?.let { "%.1f".format(it) } ?: "–"

private fun gib(bytes: Long?): String = bytes?.let { "%.1f".format(it / 1073741824.0) } ?: "–"

private fun AnnotatedString.Builder.num(value: String, unit: String, size: TextUnit, color: Color = HudInk.value, unitScale: Float = UNIT) {
    withStyle(SpanStyle(color = color)) { append(value) }
    if (unit.isNotEmpty()) withStyle(SpanStyle(color = HudInk.dim, fontSize = size * unitScale)) { append(unit) }
}

private fun AnnotatedString.Builder.gap() = append("  ")

private fun AnnotatedString.Builder.temp(d: HudData, sensor: HudMetrics.TempSensor, c: Int?, size: TextUnit) {
    if (c == null) { num("–", "", size); return }
    val shown = if (d.fahrenheit) (c * 9 / 5 + 32) else c
    num(shown.toString(), if (d.fahrenheit) "°F" else "°C", size, d.tempColor(sensor, c))
}

@Composable
private fun Big(value: String, unit: String, size: TextUnit, color: Color, glow: Boolean, unitScale: Float = 0.27f, modifier: Modifier = Modifier) {
    Text(
        buildAnnotatedString { num(value, unit, size, color, unitScale) },
        modifier = modifier, fontSize = size, fontFamily = Mono, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false,
        style = if (glow) TextStyle(shadow = Shadow(color.copy(alpha = 0.85f), Offset.Zero, 18f)) else TextStyle.Default,
    )
}

@Composable
private fun Line(text: AnnotatedString, size: TextUnit, modifier: Modifier = Modifier, align: TextAlign = TextAlign.Start) {
    Text(text, modifier = modifier, fontSize = size, fontFamily = Mono, fontWeight = FontWeight.Bold, color = HudInk.value, maxLines = 1,
        softWrap = false, overflow = TextOverflow.Clip, textAlign = align)
}

@Composable
private fun Small(text: String, size: TextUnit = 12.sp, color: Color = HudInk.dim, modifier: Modifier = Modifier) {
    Text(text, modifier = modifier, fontSize = size, fontFamily = Mono, fontWeight = FontWeight.Bold, color = color, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

private fun lowsText(d: HudData, size: TextUnit, accent: Color, deep: Boolean = true): AnnotatedString = buildAnnotatedString {
    fun part(label: String, v: Float) {
        withStyle(SpanStyle(color = HudInk.dim)) { append("$label ") }
        withStyle(SpanStyle(color = accent)) { append(f1(v)) }
    }
    part("1%", d.lows.low1Fps)
    withStyle(SpanStyle(color = HudInk.sep)) { append(" · ") }
    part("0.1%", d.lows.low01Fps)
    if (deep) {
        withStyle(SpanStyle(color = HudInk.sep)) { append(" · ") }
        part("0.01%", d.lows.low001Fps)
    }
}

/** A rolling line with a soft halo under it, like the HUD's frame-time graph. */
@Composable
private fun Graph(values: List<Float>, lo: Float, hi: Float, color: Color, modifier: Modifier) {
    Canvas(modifier) {
        if (values.size < 2 || hi <= lo) return@Canvas
        val path = Path()
        values.forEachIndexed { i, v ->
            val x = i / (values.size - 1f) * size.width
            val y = size.height - ((v - lo) / (hi - lo)).coerceIn(0f, 1f) * size.height
            if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
        }
        drawPath(path, color.copy(alpha = 0.22f), style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        drawPath(path, color, style = Stroke(width = 2.2f.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }
}

@Composable
private fun FrameGraph(d: HudData, accent: Color, height: Dp) {
    Graph(d.graph.toList(), 0f, FrameStats.GRAPH_MAX_MS, lerp(accent, Color.White, 0.3f), Modifier.fillMaxWidth().height(height))
}

@Composable
private fun Rows(rows: List<Pair<Pair<String, Color>, AnnotatedString>>, size: TextUnit, labelWidth: Dp, gap: Dp) {
    Column(verticalArrangement = Arrangement.spacedBy(gap)) {
        for ((label, value) in rows) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(label.first, modifier = Modifier.width(labelWidth), fontSize = size, fontFamily = Mono, fontWeight = FontWeight.Bold,
                    color = label.second, maxLines = 1, softWrap = false)
                Line(value, size)
            }
        }
    }
}

private fun row(label: String, color: Color, value: AnnotatedString) = (label to color) to value

@Composable
private fun RowScope.Tile(key: String, keyColor: Color, value: AnnotatedString, sub: String, glow: Color? = null) {
    Column(
        Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(9.dp)).background(HudInk.tile).padding(horizontal = 11.dp, vertical = 6.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Small(key, 12.sp, keyColor)
        Text(value, fontSize = 28.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, color = HudInk.value, maxLines = 1, softWrap = false,
            style = if (glow != null) TextStyle(shadow = Shadow(glow.copy(alpha = 0.85f), Offset.Zero, 16f)) else TextStyle.Default)
        Small(sub)
    }
}

@Composable
private fun Ring(ringSize: Dp, fraction: Float?, color: Color, label: String, sub: String?, center: @Composable () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(ringSize), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val w = size.minDimension * 0.085f
                val tl = Offset(w, w)
                val sz = Size(size.width - 2 * w, size.height - 2 * w)
                drawArc(Color.White.copy(alpha = 0.08f), 0f, 360f, false, topLeft = tl, size = sz, style = Stroke(width = w))
                if (fraction != null) {
                    val sweep = (fraction.coerceIn(0f, 1f) * 360f).coerceAtLeast(1f)
                    drawArc(color.copy(alpha = 0.25f), -90f, sweep, false, topLeft = tl, size = sz, style = Stroke(width = w * 1.9f, cap = StrokeCap.Round))
                    drawArc(color, -90f, sweep, false, topLeft = tl, size = sz, style = Stroke(width = w, cap = StrokeCap.Round))
                }
            }
            center()
        }
        Small(label, 13.sp, color)
        if (sub != null) Small(sub, 11.5.sp)
    }
}

// ---- the styles -----------------------------------------------------------------------------

@Composable
private fun ColumnScope.BigFps(d: HudData, accent: Color) {
    Big(f1(d.fps), "fps", 104.sp, accent, glow = true, modifier = Modifier.offset(x = (-4).dp))
    Line(lowsText(d, 16.sp, accent), 16.sp, Modifier.padding(top = 10.dp))
    Spacer(Modifier.weight(1f))
    FrameGraph(d, accent, 72.dp)
    Small(listOfNotNull(d.engine.ifEmpty { null }, d.title, d.played).joinToString(" · "), modifier = Modifier.padding(top = 8.dp))
}

@Composable
private fun ColumnScope.Full(d: HudData, accent: Color) {
    val s = d.device
    val z = 16.sp
    val left = listOf(
        row("GPU", StatsColors.gpu, AnnotatedString(d.gpuName)),
        row("GPU", StatsColors.gpu, buildAnnotatedString { num(s?.gpuPercent?.toString() ?: "–", "%", z); gap(); temp(d, HudMetrics.TempSensor.GPU, s?.gpuTempC, z) }),
        row("", StatsColors.gpu, buildAnnotatedString { num(s?.gpuClockMhz?.toString() ?: "–", "MHz", z) }),
        row("CPU", StatsColors.cpu, buildAnnotatedString { num(s?.cpuPercent?.toString() ?: "–", "%", z); gap(); temp(d, HudMetrics.TempSensor.CPU, s?.cpuTempC, z) }),
        row("", StatsColors.cpu, buildAnnotatedString { num(s?.cpuClockMhz?.toString() ?: "–", "MHz", z) }),
        row("VRAM", HudInk.vram, buildAnnotatedString { num(gib(s?.vramUsedBytes), "GiB", z) }),
        row("RAM", StatsColors.ram, buildAnnotatedString { num(gib(s?.ramUsedBytes), "GiB", z); gap(); num(s?.ramPercent?.roundToInt()?.toString() ?: "–", "%", z) }),
        row("BAT", StatsColors.bat, buildAnnotatedString { num(s?.battery?.percent?.toString() ?: "–", "%", z); gap(); num(s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1f".format(it) } ?: "–", "W", z) }),
    )
    val right = listOf(
        row(d.engine.ifEmpty { "FPS" }, accent, buildAnnotatedString { num(f1(d.fps), "FPS", z, accent) }),
        row("", accent, buildAnnotatedString { num(f1(d.frametimeMs), "ms", z) }),
        row("AVG", HudInk.low, buildAnnotatedString { num(f1(d.avgFps), "FPS", z) }),
        row("1%", HudInk.low, buildAnnotatedString { num(f1(d.lows.low1Fps), "FPS", z) }),
        row("0.1%", HudInk.low, buildAnnotatedString { num(f1(d.lows.low01Fps), "FPS", z) }),
        row("0.01%", HudInk.low, buildAnnotatedString { num(f1(d.lows.low001Fps), "FPS", z) }),
        row("TEMP", StatsColors.bat, buildAnnotatedString { temp(d, HudMetrics.TempSensor.BATTERY, s?.battery?.tempC, z) }),
        row("FAN", HudInk.dim, buildAnnotatedString { num(d.fanRpm?.toString() ?: "–", "rpm", z) }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(28.dp)) {
        Rows(left, z, 52.dp, 6.dp)
        Rows(right, z, 62.dp, 6.dp)
    }
    Spacer(Modifier.weight(1f))
    val frametime = stringResource(R.string.stats_hud_frametime)
    Line(buildAnnotatedString {
        withStyle(SpanStyle(color = accent)) { append(frametime) }
        withStyle(SpanStyle(color = HudInk.dim)) { append("  min %.1f · max %.1f ms".format(d.graph.filter { it > 0f }.minOrNull() ?: 0f, d.graph.maxOrNull() ?: 0f)) }
    }, 12.5.sp, Modifier.padding(bottom = 6.dp))
    FrameGraph(d, accent, 44.dp)
}

@Composable
private fun ColumnScope.Tiles(d: HudData, accent: Color) {
    val s = d.device
    val z = 28.sp
    val u = 0.5f
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("FPS", accent, buildAnnotatedString { num(f1(d.fps), "", z) }, "${f1(d.avgFps)} avg · ${f1(d.lows.low1Fps)} 1%", glow = accent)
            Tile("FRAME", HudInk.dim, buildAnnotatedString { num(f1(d.frametimeMs), "ms", z, unitScale = u) }, "max %.1f ms".format(d.graph.maxOrNull() ?: 0f))
            Tile("GPU", StatsColors.gpu, buildAnnotatedString { num(s?.gpuPercent?.toString() ?: "–", "%", z, unitScale = u) },
                listOfNotNull(s?.gpuTempC?.let { "$it°C" }, s?.gpuClockMhz?.let { "$it MHz" }).joinToString(" · "))
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("CPU", StatsColors.cpu, buildAnnotatedString { num(s?.cpuPercent?.toString() ?: "–", "%", z, unitScale = u) },
                listOfNotNull(s?.cpuTempC?.let { "$it°C" }, s?.cpuClockMhz?.let { "$it MHz" }).joinToString(" · "))
            Tile("RAM", StatsColors.ram, buildAnnotatedString { num(s?.ramPercent?.roundToInt()?.toString() ?: "–", "%", z, unitScale = u) },
                "${gib(s?.ramUsedBytes)} / ${gib(s?.ramTotalBytes)} GiB")
            Tile("VRAM", HudInk.vram, buildAnnotatedString { num(gib(s?.vramUsedBytes), "GiB", z, unitScale = u) }, d.gpuName)
        }
        Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Tile("BAT TEMP", StatsColors.bat, buildAnnotatedString { temp(d, HudMetrics.TempSensor.BATTERY, s?.battery?.tempC, z) }, stringResource(R.string.stats_hud_battery_temp))
            Tile("FAN", HudInk.dim, buildAnnotatedString { num(d.fanRpm?.toString() ?: "–", "", z) }, "rpm")
            Tile("BATTERY", StatsColors.bat, buildAnnotatedString { num(s?.battery?.percent?.toString() ?: "–", "%", z, unitScale = u) },
                listOfNotNull(s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1f W".format(it) }, s?.battery?.runtimeText).joinToString(" · "))
        }
    }
}

@Composable
private fun ColumnScope.Rings(d: HudData, accent: Color) {
    val s = d.device
    Row(Modifier.weight(1f).fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(26.dp)) {
        Ring(200.dp, d.fps?.let { it / d.hz }, accent, stringResource(R.string.stats_hud_fps_of, d.hz), "1% ${f1(d.lows.low1Fps)} · 0.1% ${f1(d.lows.low01Fps)}") {
            Big(f1(d.fps), "fps", 40.sp, HudInk.value, glow = true, unitScale = 0.4f)
        }
        Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Ring(96.dp, s?.gpuPercent?.let { it / 100f }, StatsColors.gpu, "GPU", s?.gpuTempC?.let { "$it°C" }) { Big(s?.gpuPercent?.toString() ?: "–", "%", 20.sp, HudInk.value, false, 0.6f) }
                Ring(96.dp, s?.cpuPercent?.let { it / 100f }, StatsColors.cpu, "CPU", s?.cpuTempC?.let { "$it°C" }) { Big(s?.cpuPercent?.toString() ?: "–", "%", 20.sp, HudInk.value, false, 0.6f) }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(22.dp)) {
                Ring(96.dp, s?.ramPercent?.let { it / 100f }, StatsColors.ram, "RAM", s?.let { "${gib(it.ramUsedBytes)} GiB" }) {
                    Big(s?.ramPercent?.roundToInt()?.toString() ?: "–", "%", 20.sp, HudInk.value, false, 0.6f)
                }
                Ring(96.dp, s?.battery?.percent?.let { it / 100f }, StatsColors.bat, "BAT", s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1f W".format(it) }) {
                    Big(s?.battery?.percent?.toString() ?: "–", "%", 20.sp, HudInk.value, false, 0.6f)
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Graphs(d: HudData, accent: Color) {
    val s = d.device
    val frameMs = d.fpsHistory.map { 1000f / max(it, 1f) }
    @Composable
    fun graphRow(label: String, color: Color, values: List<Float>, hi: Float, value: AnnotatedString) {
        Row(Modifier.fillMaxWidth().height(56.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small(label, 14.sp, color, Modifier.width(62.dp))
            Graph(values, 0f, hi, color, Modifier.weight(1f).height(52.dp))
            Line(value, 24.sp, Modifier.width(128.dp), TextAlign.End)
        }
    }
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.SpaceEvenly) {
        graphRow("FPS", accent, d.fpsHistory, max(d.hz.toFloat(), d.fpsHistory.maxOrNull() ?: 0f) * 1.1f, buildAnnotatedString { num(f1(d.fps), "fps", 24.sp, accent, 0.5f) })
        graphRow("FRAME", lerp(accent, Color.White, 0.3f), frameMs, max(40f, (frameMs.maxOrNull() ?: 0f) * 1.2f), buildAnnotatedString { num(f1(d.frametimeMs), "ms", 24.sp, unitScale = 0.5f) })
        graphRow("CPU", StatsColors.cpu, d.cpuHistory, 100f, buildAnnotatedString { num(s?.cpuPercent?.toString() ?: "–", "%", 24.sp, unitScale = 0.5f) })
        graphRow("GPU", StatsColors.gpu, d.gpuHistory, 100f, buildAnnotatedString { num(s?.gpuPercent?.toString() ?: "–", "%", 24.sp, unitScale = 0.5f) })
    }
}

@Composable
private fun ColumnScope.Cores(d: HudData, accent: Color) {
    val s = d.device
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
        Big(f1(d.fps), "fps", 40.sp, accent, glow = true, unitScale = 0.38f)
        Small(listOfNotNull(d.engine.ifEmpty { null }, d.frametimeMs?.let { "%.1f ms".format(it) },
            s?.cpuPercent?.let { "CPU $it%" }, s?.cpuTempC?.let { "$it°C" }).joinToString(" · "), 15.sp, modifier = Modifier.padding(bottom = 6.dp))
    }
    Spacer(Modifier.height(10.dp))
    val gameTag = stringResource(R.string.stats_hud_game_cores)
    Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
        for (group in d.coreGroups) {
            for (core in group.cores) {
                val p = s?.perCorePercent?.getOrNull(core)?.takeIf { it >= 0 }
                val mhz = s?.perCoreClockMhz?.getOrNull(core)?.takeIf { it > 0 }
                Row(Modifier.fillMaxWidth().height(20.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Small("C$core", 14.sp, StatsColors.cpu, Modifier.width(40.dp))
                    Box(Modifier.weight(1f).height(14.dp).clip(RoundedCornerShape(7.dp)).background(Color.White.copy(alpha = 0.07f))) {
                        if (p != null && p > 0) Box(
                            Modifier.fillMaxHeight().fillMaxWidth(p / 100f).clip(RoundedCornerShape(7.dp))
                                .background(Brush.horizontalGradient(listOf(Color(0xFF2B6FC9), if (p >= 80) HudInk.amber else StatsColors.cpu))),
                        )
                    }
                    Line(buildAnnotatedString { num(p?.toString() ?: "–", "%", 14.sp); append(" "); num(mhz?.toString() ?: "–", "MHz", 14.sp) },
                        14.sp, Modifier.width(118.dp), TextAlign.End)
                }
            }
            val label = group.maxGhz?.let { stringResource(R.string.stats_hud_core_group, group.cores.size, "%.2f".format(it)) }
                ?: stringResource(R.string.stats_hud_core_group_unknown, group.cores.size)
            Row(Modifier.padding(start = 50.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Small(label, 11.5.sp)
                if (d.gameCores.isNotEmpty() && group.cores.any { it in d.gameCores }) {
                    Text(gameTag.uppercase(), fontSize = 10.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, color = Color(0xFF0A0B0D),
                        modifier = Modifier.clip(RoundedCornerShape(4.dp)).background(StatsColors.cpu).padding(horizontal = 4.dp))
                }
            }
        }
    }
}

@Composable
private fun ColumnScope.Heat(d: HudData) {
    val s = d.device
    @Composable
    fun bar(label: String, color: Color, sensor: HudMetrics.TempSensor, c: Int?) {
        val red = d.redC(sensor)
        Row(Modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Small(label, 15.sp, color, Modifier.width(48.dp))
            BoxWithConstraints(Modifier.weight(1f).height(20.dp).clip(RoundedCornerShape(10.dp)).background(Color.White.copy(alpha = 0.07f))) {
                if (c != null && c > 0) Box(Modifier.fillMaxHeight().fillMaxWidth((c / 100f).coerceIn(0f, 1f)).clip(RoundedCornerShape(10.dp))
                    .background(Brush.horizontalGradient(listOf(color.copy(alpha = 0.55f), color))))
                if (red != null && red in 1..99) Box(Modifier.offset(x = maxWidth * (red / 100f)).width(2.dp).fillMaxHeight().background(Color(0xFFEF5350)))
            }
            Line(buildAnnotatedString { temp(d, sensor, c, 24.sp) }, 24.sp, Modifier.width(92.dp), TextAlign.End)
        }
    }
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        bar("CPU", StatsColors.cpu, HudMetrics.TempSensor.CPU, s?.cpuTempC)
        bar("GPU", StatsColors.gpu, HudMetrics.TempSensor.GPU, s?.gpuTempC)
        bar("BAT", StatsColors.bat, HudMetrics.TempSensor.BATTERY, s?.battery?.tempC)
    }
    Small(stringResource(R.string.stats_hud_red_mark), 12.sp, modifier = Modifier.padding(top = 8.dp))
    Spacer(Modifier.weight(1f))
    Row(Modifier.fillMaxWidth().height(96.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Tile("FAN", HudInk.dim, buildAnnotatedString { num(d.fanRpm?.toString() ?: "–", "", 28.sp) }, "rpm")
        Tile("POWER", StatsColors.bat, buildAnnotatedString { num(s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1f".format(it) } ?: "–", "W", 28.sp, unitScale = 0.5f) },
            stringResource(R.string.stats_hud_whole_device))
        Tile("BATTERY", StatsColors.bat, buildAnnotatedString { num(s?.battery?.percent?.toString() ?: "–", "%", 28.sp, unitScale = 0.5f) }, s?.battery?.runtimeText ?: "")
    }
}

@Composable
private fun ColumnScope.GameCard(d: HudData, accent: Color) {
    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Box(
            Modifier.width(150.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp))
                .background(Brush.linearGradient(listOf(lerp(accent, Color.Black, 0.55f), Color(0xFF0B0D10)))),
            contentAlignment = Alignment.BottomStart,
        ) {
            if (d.cover != null) AsyncImage(model = d.cover, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
            else Text(d.title.orEmpty(), fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.85f), maxLines = 3,
                modifier = Modifier.padding(12.dp))
        }
        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(d.title.orEmpty(), fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2, overflow = TextOverflow.Ellipsis)
            d.played?.let { Small(stringResource(R.string.stats_hud_playing_for, it), 13.5.sp) }
            Line(buildAnnotatedString {
                withStyle(SpanStyle(color = accent)) { append(d.engine) }
                if (d.engine.isNotEmpty() && d.proton != null) withStyle(SpanStyle(color = HudInk.dim)) { append(" · ") }
                d.proton?.let { withStyle(SpanStyle(color = HudInk.vram)) { append(it) } }
            }, 13.5.sp)
            if (d.resolution.isNotEmpty()) Small(d.resolution, 13.5.sp)
            Big(f1(d.fps), "fps", 60.sp, accent, glow = true, unitScale = 0.3f, modifier = Modifier.padding(top = 6.dp))
            Line(lowsText(d, 14.sp, accent, deep = false), 14.sp)
        }
    }
    Spacer(Modifier.height(10.dp))
    FrameGraph(d, accent, 40.dp)
}

@Composable
private fun ColumnScope.Pacing(d: HudData, accent: Color) {
    @Composable
    fun stat(label: String, value: String, color: Color = HudInk.value) {
        Column {
            Small(label, 13.sp)
            Text(value, fontSize = 22.sp, fontFamily = Mono, fontWeight = FontWeight.Bold, color = color, maxLines = 1)
        }
    }
    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Big(f1(d.fps), "fps", 52.sp, accent, glow = true, unitScale = 0.3f)
        stat(stringResource(R.string.stats_hud_avg_frame), d.frametimeMs?.let { "%.1f ms".format(it) } ?: "–")
        stat(stringResource(R.string.stats_hud_low), f1(d.lows.low1Fps))
        val slow = d.slowPerMinute
        stat(stringResource(R.string.stats_hud_slow_frames), slow?.let { stringResource(R.string.stats_hud_per_minute, it) } ?: "–",
            if ((slow ?: 0) > 0) HudInk.amber else HudInk.value)
    }
    Spacer(Modifier.height(12.dp))
    val target = 1000f / d.hz
    BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
        val graphColor = lerp(accent, Color.White, 0.3f)
        Canvas(Modifier.fillMaxSize()) {
            val axis = Color.White.copy(alpha = 0.12f)
            drawLine(axis, Offset(0f, 0f), Offset(0f, size.height), 1.dp.toPx())
            drawLine(axis, Offset(0f, size.height), Offset(size.width, size.height), 1.dp.toPx())
            val dash = PathEffect.dashPathEffect(floatArrayOf(6.dp.toPx(), 5.dp.toPx()))
            for ((ms, color) in listOf(target to Color.White.copy(alpha = 0.25f), target * 2 to HudInk.amber.copy(alpha = 0.5f))) {
                val y = size.height * (1f - ms / FrameStats.GRAPH_MAX_MS)
                drawLine(color, Offset(0f, y), Offset(size.width, y), 1.dp.toPx(), pathEffect = dash)
            }
        }
        Graph(d.graph.toList(), 0f, FrameStats.GRAPH_MAX_MS, graphColor, Modifier.fillMaxSize())
        for ((ms, color) in listOf(target to HudInk.dim, target * 2 to HudInk.amber)) {
            Small("%.1f ms · %d fps".format(ms, (1000f / ms).roundToInt()), 11.sp, color,
                Modifier.align(Alignment.TopEnd).offset(y = maxHeight * (1f - ms / FrameStats.GRAPH_MAX_MS) - 17.dp))
        }
    }
}

@Composable
private fun ColumnScope.Night(d: HudData, dim: Boolean) {
    val clockInk = if (dim) Color(0xFF6F7A87) else HudInk.value
    val fpsInk = if (dim) Color(0xFF4D7FA8) else LocalPalette.current.primary
    Column(Modifier.weight(1f), verticalArrangement = Arrangement.Center) {
        Text(d.clock, fontSize = 88.sp, fontWeight = FontWeight.Light, color = clockInk, maxLines = 1, softWrap = false)
        Line(buildAnnotatedString {
            num(f1(d.fps), "fps", 30.sp, fpsInk)
            d.device?.battery?.percent?.let {
                withStyle(SpanStyle(color = HudInk.sep)) { append(" · ") }
                num(it.toString(), "%", 30.sp, if (dim) Color(0xFF6B5A44) else StatsColors.bat)
            }
        }, 30.sp, Modifier.padding(top = 14.dp))
        Small(stringResource(R.string.stats_hud_brightens, nightFloor(d)), 12.5.sp, Color(0xFF56606C), Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun ColumnScope.Mega(d: HudData, accent: Color) {
    val s = d.device
    val z = 13.5.sp
    val left = mutableListOf(
        row("GPU", StatsColors.gpu, AnnotatedString(d.gpuName)),
        row("GPU", StatsColors.gpu, buildAnnotatedString {
            num(s?.gpuPercent?.toString() ?: "–", "%", z); gap(); temp(d, HudMetrics.TempSensor.GPU, s?.gpuTempC, z); gap(); num(s?.gpuClockMhz?.toString() ?: "–", "MHz", z)
        }),
        row("CPU", StatsColors.cpu, buildAnnotatedString {
            num(s?.cpuPercent?.toString() ?: "–", "%", z); gap(); temp(d, HudMetrics.TempSensor.CPU, s?.cpuTempC, z); gap(); num(s?.cpuClockMhz?.toString() ?: "–", "MHz", z)
        }),
    )
    val cores = s?.perCorePercent?.size ?: 0
    for (i in 0 until cores) {
        left += row("C$i", StatsColors.cpu, buildAnnotatedString {
            num(s?.perCorePercent?.getOrNull(i)?.takeIf { it >= 0 }?.toString() ?: "–", "%", z); gap()
            num(s?.perCoreClockMhz?.getOrNull(i)?.takeIf { it > 0 }?.toString() ?: "–", "MHz", z)
        })
    }
    val right = listOf(
        row("VRAM", HudInk.vram, buildAnnotatedString { num(gib(s?.vramUsedBytes), "GiB", z) }),
        row("RAM", StatsColors.ram, buildAnnotatedString { num("${gib(s?.ramUsedBytes)}/${gib(s?.ramTotalBytes)}", "GiB", z); gap(); num(s?.ramPercent?.roundToInt()?.toString() ?: "–", "%", z) }),
        row("SWP", StatsColors.ram, buildAnnotatedString { num("${gib(s?.swapUsedBytes)}/${gib(s?.swapTotalBytes)}", "GiB", z) }),
        row("NET", accent, buildAnnotatedString {
            withStyle(SpanStyle(color = HudInk.dim)) { append("↓") }; num(s?.netDownBps?.let { (it / 1024).toString() } ?: "–", "", z)
            append(" "); withStyle(SpanStyle(color = HudInk.dim)) { append("↑") }; num(s?.netUpBps?.let { (it / 1024).toString() } ?: "–", "KB/s", z)
        }),
        row("BAT", StatsColors.bat, buildAnnotatedString {
            num(s?.battery?.percent?.toString() ?: "–", "%", z); gap(); temp(d, HudMetrics.TempSensor.BATTERY, s?.battery?.tempC, z); gap()
            num(s?.battery?.watts?.takeIf { it > 0f }?.let { "%.1f".format(it) } ?: "–", "W", z)
        }),
        row("FAN", HudInk.dim, buildAnnotatedString { num(d.fanRpm?.toString() ?: "–", "rpm", z) }),
        row(d.engine.ifEmpty { "FPS" }, accent, buildAnnotatedString { num(f1(d.fps), "FPS", z, accent); gap(); num(f1(d.frametimeMs), "ms", z) }),
        row("AVG", HudInk.low, buildAnnotatedString { num(f1(d.avgFps), "FPS", z) }),
        row("1%", HudInk.low, buildAnnotatedString { num(f1(d.lows.low1Fps), "FPS", z) }),
        row("0.1%", HudInk.low, buildAnnotatedString { num(f1(d.lows.low01Fps), "FPS", z) }),
        row("0.01%", HudInk.low, buildAnnotatedString { num(f1(d.lows.low001Fps), "FPS", z) }),
    )
    Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Rows(left, z, 44.dp, 3.dp)
        Rows(right, z, 58.dp, 3.dp)
    }
    Spacer(Modifier.weight(1f))
    Line(buildAnnotatedString {
        withStyle(SpanStyle(color = HudInk.dim)) { append("RES ") }; append(d.resolution.ifEmpty { "–" })
        d.proton?.let { withStyle(SpanStyle(color = HudInk.dim)) { append(" · ") }; withStyle(SpanStyle(color = HudInk.vram)) { append(it) } }
        d.played?.let { withStyle(SpanStyle(color = HudInk.dim)) { append(" · ") }; append(it) }
    }, 12.5.sp, Modifier.padding(bottom = 6.dp))
    FrameGraph(d, accent, 30.dp)
}
