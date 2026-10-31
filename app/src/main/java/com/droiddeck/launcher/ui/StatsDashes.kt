package com.droiddeck.launcher.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.session.GameStatusFiles
import com.droiddeck.launcher.session.GameTelemetry
import com.droiddeck.launcher.session.StatsDash
import com.droiddeck.launcher.session.Telemetry
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

private val DASH_H = 290.dp
/** Pedal and speed trace length: 150 readings at the page's 66 ms draw rate, about 10 s. */
internal const val TRACE_N = 150

internal class TraceData(val throttle: List<Float>, val brake: List<Float>, val speed: List<Float>)

/** The racing dash in [style]; a tap moves to the next one. */
@Composable
internal fun RaceDashView(style: StatsDash, t: Telemetry, trace: TraceData, onTap: () -> Unit) {
    Box(Modifier.fillMaxWidth().clickable(onClick = onTap)) {
        when (style) {
            StatsDash.AUTO, StatsDash.SPEEDOMETER -> SpeedometerDash(t)
            StatsDash.CIRCUIT -> CircuitDash(t)
            StatsDash.TRACE -> TraceDash(t, trace)
            StatsDash.DIALS -> DialsDash(t)
            StatsDash.GLANCE -> GlanceDash(t)
        }
    }
}

private fun polar(cx: Float, cy: Float, r: Float, deg: Float): Offset {
    val a = Math.toRadians((deg - 90f).toDouble())
    return Offset(cx + r * cos(a).toFloat(), cy + r * sin(a).toFloat())
}

private const val DIAL_START = -125f
private const val DIAL_SWEEP = 250f

private fun dialAngle(v: Float, max: Float) = DIAL_START + DIAL_SWEEP * (v / max).coerceIn(0f, 1f)

/** A 250° dial face: track, optional red zone from [red], ticks and numbers every [major]. */
private fun DrawScope.dialFace(
    cx: Float, cy: Float, r: Float, max: Float, major: Float, label: (Float) -> String,
    measurer: TextMeasurer, labelStyle: TextStyle, tick: Color, minor: Color, track: Color, red: Float?, redColor: Color,
) {
    val box = Size(2 * r, 2 * r)
    val tl = Offset(cx - r, cy - r)
    drawArc(track, DIAL_START - 90f, DIAL_SWEEP, false, topLeft = tl, size = box, style = Stroke(6.dp.toPx(), cap = StrokeCap.Round))
    if (red != null && red < max) {
        drawArc(redColor, dialAngle(red, max) - 90f, dialAngle(max, max) - dialAngle(red, max), false, topLeft = tl, size = box, style = Stroke(6.dp.toPx()))
    }
    val steps = (max / (major / 2f)).roundToInt()
    for (i in 0..steps) {
        val v = i * major / 2f
        val isMajor = i % 2 == 0
        val deg = dialAngle(v, max)
        drawLine(if (isMajor) tick else minor, polar(cx, cy, r - (if (isMajor) 14.dp else 7.dp).toPx(), deg), polar(cx, cy, r - 2.dp.toPx(), deg),
            strokeWidth = (if (isMajor) 2.5f else 1.2f).dp.toPx())
        if (isMajor) {
            val layout = measurer.measure(label(v), labelStyle)
            val p = polar(cx, cy, r - 27.dp.toPx(), deg)
            drawText(layout, topLeft = Offset(p.x - layout.size.width / 2f, p.y - layout.size.height / 2f))
        }
    }
}

private fun DrawScope.needle(cx: Float, cy: Float, len: Float, deg: Float, color: Color, hub: Color) {
    drawLine(color, Offset(cx, cy), polar(cx, cy, len, deg), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
    drawCircle(hub, radius = 7.dp.toPx(), center = Offset(cx, cy))
}

private fun speedScale(speed: Float?): Float = if ((speed ?: 0f) > 210f) 320f else 220f

private fun rpmScale(t: Telemetry): Float = max(t.maxRpm ?: 9000f, t.rpm ?: 0f)

private fun kmh(t: Telemetry) = t.speedKmh?.let { "%.0f".format(it) } ?: "–"

@Composable
private fun SpeedometerDash(t: Telemetry) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalPalette.current.primary
    Row(Modifier.fillMaxWidth().height(DASH_H), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsCard(stringResource(R.string.stats_speed), Modifier.weight(1.55f).fillMaxHeight()) {
            Box(Modifier.fillMaxWidth().weight(1f)) {
                SpeedDial(t, Modifier.fillMaxSize())
                Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t.gear ?: "–", fontSize = 44.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = accent)
                    Text("${kmh(t)} km/h", fontSize = 16.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = colors.onBackground)
                    Text(t.rpm?.let { "%,.0f rpm".format(it) } ?: "", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                }
            }
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsCard(stringResource(R.string.stats_pedals), Modifier.fillMaxWidth().weight(1f)) { Pedals(t, Modifier.fillMaxWidth().weight(1f)) }
            if (t.gLat != null && t.gLon != null) {
                StatsCard(stringResource(R.string.stats_gforce), Modifier.fillMaxWidth().weight(1f)) { GForce(t, Modifier.fillMaxWidth().weight(1f)) }
            }
        }
    }
}

@Composable
private fun SpeedDial(t: Telemetry, modifier: Modifier) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val vmax = speedScale(t.speedKmh)
    val rpmFraction = ((t.rpm ?: 0f) / rpmScale(t)).coerceIn(0f, 1f)
    Canvas(modifier) {
        val r = min(size.width * 0.42f, size.height * 0.47f)
        val cx = size.width / 2f
        val cy = r + 12.dp.toPx()
        dialFace(cx, cy, r, vmax, 20f, { "%.0f".format(it) }, measurer, labelStyle, colors.onBackground, colors.onSurfaceVariant, pal.line, null, StatsColors.brake)
        val rr = r + 9.dp.toPx()
        val box = Size(2 * rr, 2 * rr)
        val tl = Offset(cx - rr, cy - rr)
        drawArc(pal.line, DIAL_START - 90f, DIAL_SWEEP, false, topLeft = tl, size = box, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        if (rpmFraction > 0f) drawArc(if (rpmFraction > 0.9f) StatsColors.brake else pal.primary, DIAL_START - 90f, DIAL_SWEEP * rpmFraction, false,
            topLeft = tl, size = box, style = Stroke(5.dp.toPx(), cap = StrokeCap.Round))
        needle(cx, cy, r - 16.dp.toPx(), dialAngle(t.speedKmh ?: 0f, vmax), colors.onBackground, pal.primary)
    }
}

@Composable
private fun Pedals(t: Telemetry, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.SpaceEvenly) {
        PedalBar("THR", t.throttle, StatsColors.throttle)
        PedalBar("BRK", t.brake, StatsColors.brake)
    }
}

@Composable
private fun PedalBar(label: String, value: Float?, color: Color) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxHeight(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(3.dp)) {
        Text(value?.let { "${(it * 100).roundToInt()}%" } ?: "–", fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.onBackground)
        Box(Modifier.weight(1f).width(26.dp).clip(RoundedCornerShape(6.dp)).background(pal.line)) {
            Box(Modifier.fillMaxWidth().fillMaxHeight((value ?: 0f).coerceIn(0f, 1f)).align(Alignment.BottomCenter).background(color))
        }
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun GForce(t: Telemetry, modifier: Modifier) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val g = kotlin.math.hypot(t.gLat ?: 0f, t.gLon ?: 0f)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val steerH = if (t.steer != null) 10.dp.toPx() else 0f
            val r = min(size.width / 2f, (size.height - steerH) / 2f) - 2.dp.toPx()
            val cx = size.width / 2f
            val cy = (size.height - steerH) / 2f
            for (k in listOf(1f, 0.62f, 0.24f)) drawCircle(pal.line, radius = r * k, center = Offset(cx, cy), style = Stroke(1.dp.toPx()))
            drawLine(pal.line, Offset(cx - r, cy), Offset(cx + r, cy), strokeWidth = 1.dp.toPx())
            drawLine(pal.line, Offset(cx, cy - r), Offset(cx, cy + r), strokeWidth = 1.dp.toPx())
            val gx = (t.gLat ?: 0f).coerceIn(-2f, 2f) / 2f * r
            val gy = (t.gLon ?: 0f).coerceIn(-2f, 2f) / 2f * r
            drawCircle(colors.onBackground, radius = 6.dp.toPx(), center = Offset(cx + gx, cy - gy))
            t.steer?.let { s ->
                val y = size.height - 3.dp.toPx()
                drawLine(pal.line, Offset(cx - r, y), Offset(cx + r, y), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
                drawLine(pal.primary, Offset(cx, y), Offset(cx + s * r, y), strokeWidth = 5.dp.toPx(), cap = StrokeCap.Round)
            }
        }
        Text("%.1f G".format(g), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, modifier = Modifier.align(Alignment.TopEnd))
    }
}

@Composable
private fun CircuitDash(t: Telemetry) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalPalette.current.primary
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsCard(null, Modifier.fillMaxWidth()) { ShiftLights(t, Modifier.fillMaxWidth().height(20.dp)) }
        Row(Modifier.fillMaxWidth().height(DASH_H - 48.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsCard(stringResource(R.string.stats_timing), Modifier.weight(1f).fillMaxHeight()) {
                KvRow(stringResource(R.string.bottom_telemetry_lap), t.lap?.let { lap -> t.laps?.let { "$lap / $it" } ?: "$lap" } ?: "–")
                KvRow(stringResource(R.string.bottom_telemetry_position), t.position?.let { "P$it" } ?: "–")
                KvRow(stringResource(R.string.bottom_telemetry_this_lap), t.currentLapS?.let { lapTime(it) } ?: "–")
                KvRow(stringResource(R.string.bottom_telemetry_last_lap), t.lastLapS?.let { lapTime(it) } ?: "–")
                t.bestLapS?.let { KvRow(stringResource(R.string.stats_best_lap), lapTime(it), accent) }
            }
            StatsCard("", Modifier.weight(1.2f).fillMaxHeight()) {
                Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                    Text(t.gear ?: "–", fontSize = 72.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = accent)
                    Text("${kmh(t)} km/h", fontSize = 24.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = colors.onBackground)
                    Text(t.rpm?.let { rpm -> t.maxRpm?.let { "%,.0f / %,.0f rpm".format(rpm, it) } ?: "%,.0f rpm".format(rpm) } ?: "",
                        fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                }
            }
            Column(Modifier.weight(0.9f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                StatsCard(stringResource(R.string.bottom_telemetry_fuel), Modifier.fillMaxWidth().weight(1f)) {
                    val fuel = t.fuel
                    if (fuel != null) {
                        Bar(fuel, colors.onBackground, Modifier.fillMaxWidth().height(14.dp))
                        Text("${(fuel * 100).roundToInt()}%", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
                    } else Text("–", fontSize = 13.sp, color = colors.onSurfaceVariant)
                }
                t.tireTempsC?.takeIf { it.size == 4 }?.let { tires ->
                    StatsCard(stringResource(R.string.stats_tires), Modifier.fillMaxWidth().weight(1.3f)) { Tires(tires, Modifier.fillMaxWidth().weight(1f)) }
                }
            }
        }
    }
}

@Composable
private fun ShiftLights(t: Telemetry, modifier: Modifier) {
    val pal = LocalPalette.current
    val ratio = ((t.rpm ?: 0f) / rpmScale(t)).coerceIn(0f, 1f)
    val lit = (((ratio - 0.6f) / 0.35f).coerceIn(0f, 1f) * 15).roundToInt()
    val flash = ratio > 0.95f && (System.currentTimeMillis() / 120) % 2 == 0L
    Canvas(modifier) {
        val step = size.width / 15f
        val r = min(size.height / 2f, step / 2f - 2.dp.toPx())
        for (i in 0 until 15) {
            val c = when {
                flash -> pal.primary
                i >= lit -> pal.line
                i < 5 -> StatsColors.shiftGreen
                i < 10 -> StatsColors.warn
                i < 13 -> StatsColors.brake
                else -> pal.primary
            }
            drawCircle(c, radius = r, center = Offset(step * i + step / 2f, size.height / 2f))
        }
    }
}

@Composable
private fun KvRow(label: String, value: String, valueColor: Color = MaterialTheme.colorScheme.onBackground) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f), maxLines = 1)
        Text(value, fontSize = 15.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = valueColor, maxLines = 1)
    }
}

@Composable
private fun Bar(fraction: Float, color: Color, modifier: Modifier) {
    Box(modifier.clip(RoundedCornerShape(5.dp)).background(LocalPalette.current.line)) {
        Box(Modifier.fillMaxWidth(fraction.coerceIn(0f, 1f)).fillMaxHeight().background(color))
    }
}

@Composable
private fun Tires(tires: List<Float>, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TireBox(tires[0], Modifier.weight(1f).fillMaxHeight())
            TireBox(tires[1], Modifier.weight(1f).fillMaxHeight())
        }
        Row(Modifier.fillMaxWidth().weight(1f), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            TireBox(tires[2], Modifier.weight(1f).fillMaxHeight())
            TireBox(tires[3], Modifier.weight(1f).fillMaxHeight())
        }
    }
}

@Composable
private fun TireBox(c: Float, modifier: Modifier) {
    val bg = when {
        c > 100f -> Color(0xFF5A2A2C)
        c < 70f -> Color(0xFF1D3550)
        else -> Color(0xFF23352B)
    }
    Box(modifier.clip(RoundedCornerShape(6.dp)).background(bg), contentAlignment = Alignment.Center) {
        Text("%.0f".format(c), fontSize = 13.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground)
    }
}

@Composable
private fun TraceDash(t: Telemetry, trace: TraceData) {
    val colors = MaterialTheme.colorScheme
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsCard(stringResource(R.string.stats_trace), Modifier.fillMaxWidth().height(168.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(StatsColors.throttle, "${stringResource(R.string.stats_throttle)} ${t.throttle?.let { (it * 100).roundToInt() } ?: 0}%")
                Legend(StatsColors.brake, "${stringResource(R.string.stats_brake)} ${t.brake?.let { (it * 100).roundToInt() } ?: 0}%")
            }
            TraceChart(listOf(trace.throttle to StatsColors.throttle, trace.brake to StatsColors.brake), 1f, Modifier.fillMaxWidth().weight(1f))
        }
        val top = max(200f, (trace.speed.maxOrNull() ?: 0f) * 1.1f)
        StatsCard(stringResource(R.string.stats_speed_chart), Modifier.fillMaxWidth().height(114.dp)) {
            Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Legend(StatsColors.speed, "${stringResource(R.string.stats_speed)} ${kmh(t)}")
                Text("0 – %.0f".format(top), fontSize = 11.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
            }
            TraceChart(listOf(trace.speed to StatsColors.speed), top, Modifier.fillMaxWidth().weight(1f))
        }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
        Box(Modifier.size(8.dp).clip(CircleShape).background(color))
        Text(text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = MaterialTheme.colorScheme.onBackground, maxLines = 1)
    }
}

@Composable
private fun TraceChart(series: List<Pair<List<Float>, Color>>, max: Float, modifier: Modifier) {
    val pal = LocalPalette.current
    Canvas(modifier) {
        for (k in listOf(0f, 0.5f, 1f)) {
            val y = size.height - k * size.height
            drawLine(pal.line, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
        }
        for ((values, color) in series) {
            if (values.size < 2) continue
            val path = Path()
            val offset = TRACE_N - values.size
            values.forEachIndexed { i, v ->
                val x = (i + offset) / (TRACE_N - 1f) * size.width
                val y = size.height - (v / max).coerceIn(0f, 1f) * size.height
                if (i == 0) path.moveTo(x, y) else path.lineTo(x, y)
            }
            drawPath(path, color, style = Stroke(width = 2.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
    }
}

@Composable
private fun DialsDash(t: Telemetry) {
    val colors = MaterialTheme.colorScheme
    val rpmMax = (rpmScale(t) / 1000f).let { kotlin.math.ceil(it) * 1000f }
    val hasExtras = t.lights != null || t.turboBar != null || t.engineTempC != null || t.oilTempC != null
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth().height(if (hasExtras) DASH_H - 56.dp else DASH_H), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsCard("", Modifier.weight(1f).fillMaxHeight()) {
                ArcDial(t.speedKmh ?: 0f, speedScale(t.speedKmh), 40f, { "%.0f".format(it) }, null, kmh(t), "km/h", Modifier.fillMaxWidth().weight(1f))
            }
            StatsCard("", Modifier.weight(1f).fillMaxHeight()) {
                ArcDial(t.rpm ?: 0f, rpmMax, 1000f, { "%.0f".format(it / 1000f) }, t.maxRpm?.let { it * 0.9f },
                    t.rpm?.let { "%,.0f".format(it) } ?: "–", "rpm × 1000 · ${t.gear ?: "–"}", Modifier.fillMaxWidth().weight(1f))
            }
        }
        if (hasExtras) StatsCard(stringResource(R.string.stats_lights), Modifier.fillMaxWidth()) {
            Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                t.lights?.let { on ->
                    Lamp(stringResource(R.string.stats_lamp_shift), on and GameTelemetry.DL_SHIFT != 0, LocalPalette.current.primary)
                    Lamp(stringResource(R.string.stats_lamp_handbrake), on and GameTelemetry.DL_HANDBRAKE != 0, StatsColors.brake)
                    Lamp(stringResource(R.string.stats_lamp_pit), on and GameTelemetry.DL_PITSPEED != 0, StatsColors.warn)
                    Lamp(stringResource(R.string.stats_lamp_tc), on and GameTelemetry.DL_TC != 0, StatsColors.warn)
                    Lamp(stringResource(R.string.stats_lamp_abs), on and GameTelemetry.DL_ABS != 0, StatsColors.warn)
                }
                t.turboBar?.let { Lamp(stringResource(R.string.stats_turbo, "%.1f".format(it)), false, colors.onBackground) }
                t.engineTempC?.let { Lamp(stringResource(R.string.stats_engine_temp, "%.0f".format(it)), false, colors.onBackground) }
                t.oilTempC?.let { Lamp(stringResource(R.string.stats_oil_temp, "%.0f".format(it)), false, colors.onBackground) }
            }
        }
    }
}

@Composable
private fun ArcDial(value: Float, max: Float, major: Float, label: (Float) -> String, red: Float?, readout: String, unit: String, modifier: Modifier) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    Box(modifier) {
        Canvas(Modifier.fillMaxSize()) {
            val r = min(size.width * 0.46f, size.height * 0.5f)
            val cx = size.width / 2f
            val cy = r + 6.dp.toPx()
            dialFace(cx, cy, r, max, major, label, measurer, labelStyle, colors.onBackground, colors.onSurfaceVariant, pal.line, red, StatsColors.brake)
            needle(cx, cy, r - 18.dp.toPx(), dialAngle(value, max), colors.onBackground, pal.primary)
        }
        Column(Modifier.align(Alignment.BottomCenter), horizontalAlignment = Alignment.CenterHorizontally) {
            Text(readout, fontSize = 22.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = colors.onBackground)
            Text(unit, fontSize = 11.sp, color = colors.onSurfaceVariant, maxLines = 1)
        }
    }
}

@Composable
private fun Lamp(label: String, on: Boolean, color: Color) {
    val pal = LocalPalette.current
    val shape = RoundedCornerShape(8.dp)
    Text(label, fontSize = 12.sp, fontFamily = FontFamily.Monospace, fontWeight = if (on) FontWeight.Bold else FontWeight.Normal,
        color = if (on) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
        modifier = Modifier.clip(shape).background(if (on) color else Color.Transparent).border(1.dp, if (on) color else pal.line, shape)
            .padding(horizontal = 10.dp, vertical = 4.dp))
}

@Composable
private fun GlanceDash(t: Telemetry) {
    val colors = MaterialTheme.colorScheme
    val accent = LocalPalette.current.primary
    val ratio = ((t.rpm ?: 0f) / rpmScale(t)).coerceIn(0f, 1f)
    Row(Modifier.fillMaxWidth().height(DASH_H), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        EdgeBar(t.throttle, StatsColors.throttle)
        StatsCard("", Modifier.weight(1f).fillMaxHeight()) {
            Bar(ratio, if (ratio > 0.9f) StatsColors.brake else accent, Modifier.fillMaxWidth().height(14.dp))
            Column(Modifier.fillMaxWidth().weight(1f), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Text(t.gear ?: "–", fontSize = 110.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = accent, lineHeight = 112.sp)
                Text("${kmh(t)} km/h", fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace, color = colors.onBackground)
            }
        }
        EdgeBar(t.brake, StatsColors.brake)
    }
}

@Composable
private fun EdgeBar(value: Float?, color: Color) {
    Box(Modifier.width(14.dp).fillMaxHeight().clip(RoundedCornerShape(5.dp)).background(LocalPalette.current.line)) {
        Box(Modifier.fillMaxWidth().fillMaxHeight((value ?: 0f).coerceIn(0f, 1f)).align(Alignment.BottomCenter).background(color))
    }
}

/** War Thunder's own labels the Flight layout places; others stay in the list. */
internal object FlightLabels {
    const val IAS = "IAS, km/h"
    const val ALTITUDE = "H, m"
    const val G = "Ny"
    const val FUEL = "Mfuel, kg"
    const val THROTTLE = "throttle 1, %"

    fun drawable(rows: List<Pair<String, String>>): Boolean = rows.any { it.first == IAS || it.first == ALTITUDE }
}

@Composable
internal fun FlightDash(rows: List<Pair<String, String>>) {
    val colors = MaterialTheme.colorScheme
    val v = rows.toMap()
    fun num(key: String) = v[key]?.toFloatOrNull()
    Row(Modifier.fillMaxWidth().height(DASH_H), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsCard(FlightLabels.IAS, Modifier.weight(1f).fillMaxHeight()) {
            num(FlightLabels.IAS)?.let { Tape(it, 10f, 50f, Modifier.fillMaxWidth().weight(1f)) } ?: Text("–", color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1.3f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsCard(FlightLabels.THROTTLE, Modifier.fillMaxWidth().weight(1f)) {
                val thr = num(FlightLabels.THROTTLE)
                Bar((thr ?: 0f) / 110f, StatsColors.throttle, Modifier.fillMaxWidth().height(16.dp))
                Text(thr?.let { "%.0f%%".format(it) } ?: "–", fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant)
            }
            StatsCard(FlightLabels.G, Modifier.fillMaxWidth().weight(1f)) {
                Text(num(FlightLabels.G)?.let { "%.1f".format(it) } ?: "–", fontSize = 34.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace,
                    color = colors.onBackground)
                num(FlightLabels.FUEL)?.let { Text("${FlightLabels.FUEL} %.0f".format(it), fontSize = 12.sp, color = colors.onSurfaceVariant) }
            }
        }
        StatsCard(FlightLabels.ALTITUDE, Modifier.weight(1f).fillMaxHeight()) {
            num(FlightLabels.ALTITUDE)?.let { Tape(it, 100f, 500f, Modifier.fillMaxWidth().weight(1f)) } ?: Text("–", color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun Tape(value: Float, step: Float, every: Float, modifier: Modifier) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val small = TextStyle(color = colors.onSurfaceVariant, fontSize = 11.sp, fontFamily = FontFamily.Monospace)
    val big = TextStyle(color = colors.onBackground, fontSize = 18.sp, fontWeight = FontWeight.Bold, fontFamily = FontFamily.Monospace)
    Canvas(modifier) {
        val cy = size.height / 2f
        val px = (size.height / 2f) / (step * 4f)
        val boxH = 32.dp.toPx()
        var m = floor((value - step * 5f) / step) * step
        while (m < value + step * 6f) {
            val y = cy - (m - value) * px
            if (y in 0f..size.height) {
                val labelled = abs((m / every).roundToInt() * every - m) < 0.01f
                drawLine(colors.onSurfaceVariant, Offset(size.width * 0.74f, y), Offset(size.width, y), strokeWidth = (if (labelled) 2.5f else 1f).dp.toPx())
                if (labelled && abs(y - cy) > boxH * 0.75f) {
                    val layout = measurer.measure("%.0f".format(m), small)
                    drawText(layout, topLeft = Offset(size.width * 0.70f - layout.size.width, y - layout.size.height / 2f))
                }
            }
            m += step
        }
        drawRoundRect(pal.primary, topLeft = Offset(1.dp.toPx(), cy - boxH / 2f), size = Size(size.width - 2.dp.toPx(), boxH),
            cornerRadius = CornerRadius(8.dp.toPx()), style = Stroke(2.dp.toPx()))
        val layout = measurer.measure("%.0f".format(value), big)
        drawText(layout, topLeft = Offset(size.width / 2f - layout.size.width / 2f, cy - layout.size.height / 2f))
    }
}

@Composable
internal fun ShipDash(e: GameStatusFiles.Elite) {
    val colors = MaterialTheme.colorScheme
    Row(Modifier.fillMaxWidth().height(210.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatsCard(stringResource(R.string.stats_power), Modifier.weight(1.2f).fillMaxHeight()) {
            e.pips?.takeIf { it.size == 3 }?.let { Pips(it, Modifier.fillMaxWidth().weight(1f)) } ?: Text("–", color = colors.onSurfaceVariant)
        }
        Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            StatsCard(stringResource(R.string.stats_fuel_t), Modifier.fillMaxWidth().weight(1f)) {
                e.fuelMain?.let { Text(stringResource(R.string.stats_fuel_main, "%.1f".format(it)), fontSize = 15.sp, fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Monospace, color = colors.onBackground) }
                e.fuelReserve?.let { Text(stringResource(R.string.stats_fuel_reserve, "%.2f".format(it)), fontSize = 12.sp, color = colors.onSurfaceVariant) }
            }
            StatsCard("", Modifier.fillMaxWidth().weight(1f)) {
                e.cargo?.let { KvRow(stringResource(R.string.bottom_telemetry_cargo), "%.0f t".format(it)) }
                e.legal?.let { KvRow(stringResource(R.string.bottom_telemetry_legal), it, if (it == "Clean") StatsColors.gpu else StatsColors.warn) }
                e.balance?.let { KvRow(stringResource(R.string.bottom_telemetry_balance), "%,d CR".format(it)) }
            }
        }
    }
}

@Composable
private fun Pips(pips: List<Int>, modifier: Modifier) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val measurer = rememberTextMeasurer()
    val style = TextStyle(color = colors.onSurfaceVariant, fontSize = 12.sp, fontWeight = FontWeight.Bold)
    val names = listOf("SYS", "ENG", "WEP")
    Canvas(modifier) {
        val colW = size.width / 3f
        val pipW = colW * 0.62f
        val labelH = 18.dp.toPx()
        val pipH = (size.height - labelH) / 4f - 4.dp.toPx()
        for (col in 0 until 3) {
            val x = col * colW + (colW - pipW) / 2f
            // Status.json counts half pips: 0..8 per system.
            val value = pips[col] / 2f
            for (i in 0 until 4) {
                val y = size.height - labelH - (i + 1) * (pipH + 4.dp.toPx())
                drawRoundRect(pal.line, topLeft = Offset(x, y), size = Size(pipW, pipH), cornerRadius = CornerRadius(4.dp.toPx()))
                val fill = (value - i).coerceIn(0f, 1f)
                if (fill > 0f) drawRoundRect(pal.primary, topLeft = Offset(x, y), size = Size(pipW * fill, pipH), cornerRadius = CornerRadius(4.dp.toPx()))
            }
            val layout = measurer.measure(names[col], style)
            drawText(layout, topLeft = Offset(col * colW + colW / 2f - layout.size.width / 2f, size.height - layout.size.height))
        }
    }
}
