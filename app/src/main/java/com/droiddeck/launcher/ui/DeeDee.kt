package com.droiddeck.launcher.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathOperation
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.drawscope.withTransform
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import kotlin.math.PI

internal enum class PetState { IDLE, WORKING, QUESTION, DONE, ERROR, SLEEP }

private val White = Color(0xFFFFFFFF)
private val Blue = Color(0xFF1A9FFF)
private val Ink = Color(0xFF0E141B)
private val Amber = Color(0xFFFFB627)
private val Red = Color(0xFFFF5A5F)
private val Slate = Color(0xFF3A4A5A)
private val Term = Color(0xFF26303B)
private val TermLine = Color(0xFF8FA3B6)

private val EaseInOut: Easing = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val EaseOut: Easing = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val Hop: Easing = CubicBezierEasing(0.3f, 0f, 0.4f, 1f)
private val Pop: Easing = CubicBezierEasing(0.3f, 1.6f, 0.5f, 1f)
private val Linear = Easing { it }
private val Step = Easing { 0f }

/** Where 0..1 of a loop [period] seconds long is at [time]; [lead] starts it that many seconds early. */
private fun loop(time: Float, period: Float, lead: Float = 0f): Float {
    val t = (time + lead) % period
    return (if (t < 0) t + period else t) / period
}

/** A CSS-style keyframe track: [stops] holds (position, value) pairs, eased per segment. */
private fun track(p: Float, easing: Easing, vararg stops: Float): Float {
    if (p <= stops[0]) return stops[1]
    var i = 0
    while (i + 2 < stops.size) {
        val a = stops[i]
        val b = stops[i + 2]
        if (p <= b) {
            val t = if (b > a) (p - a) / (b - a) else 1f
            return stops[i + 1] + (stops[i + 3] - stops[i + 1]) * easing.transform(t)
        }
        i += 2
    }
    return stops[stops.size - 1]
}

private fun mix(a: Color, b: Color, t: Float) = Color(
    a.red + (b.red - a.red) * t, a.green + (b.green - a.green) * t, a.blue + (b.blue - a.blue) * t, a.alpha + (b.alpha - a.alpha) * t,
)

/** The DroidDeck mark the head is drawn from, in its own 128 by 223.72 space. */
private val headMark: Path by lazy {
    val d = Path().apply {
        moveTo(-31.98f, 0f)
        lineTo(63.97f, 0f)
        cubicTo(125.81f, 0f, 175.93f, 50.08f, 175.93f, 111.86f)
        cubicTo(175.93f, 173.64f, 125.81f, 223.72f, 63.97f, 223.72f)
        lineTo(-31.98f, 223.72f)
        close()
    }
    val cut = Path().apply {
        addOval(Rect(Offset(63.98f, 111.86f), 71.81f))
        addRect(Rect(56.055f, 0f, 56.055f + 15.83f, 45f))
        addRect(Rect(56.055f, 178.72f, 56.055f + 15.83f, 223.72f))
    }
    Path.combine(PathOperation.Difference, d, cut)
}

private fun star(x: Float, y: Float, a: Float) = Path().apply {
    moveTo(x, y - a)
    quadraticBezierTo(x, y, x + a, y)
    quadraticBezierTo(x, y, x, y + a)
    quadraticBezierTo(x, y, x - a, y)
    quadraticBezierTo(x, y, x, y - a)
    close()
}

private class Spark(val x: Float, val y: Float, val size: Float, val color: Color, val lead: Float)

private val sparks = listOf(
    Spark(90f, 120f, 14f, White, 0f), Spark(318f, 110f, 12f, Blue, 0.4f), Spark(104f, 210f, 9f, Blue, 0.8f),
    Spark(312f, 200f, 10f, White, 0.4f), Spark(250f, 40f, 8f, White, 0f),
).map { it to star(it.x, it.y, it.size) }

/** The rig of the DeeDee mock (a 400 by 360 view box) looping through [state]; [crop] is the part of the view box shown. */
@Composable
internal fun DeeDee(state: PetState, modifier: Modifier, crop: Rect = Rect(35f, 15f, 365f, 345f)) {
    val time by produceState(0f) {
        val start = withFrameNanos { it }
        while (true) withFrameNanos { value = (it - start) / 1_000_000_000f }
    }
    val text = rememberTextMeasurer()
    Canvas(modifier) {
        val k = minOf(size.width / crop.width, size.height / crop.height)
        withTransform({
            translate((size.width - crop.width * k) / 2, (size.height - crop.height * k) / 2)
            scale(k, k, Offset.Zero)
            translate(-crop.left, -crop.top)
        }) { rig(state, time, text) }
    }
}

private fun DrawScope.rig(state: PetState, t: Float, text: androidx.compose.ui.text.TextMeasurer) {
    // Shadow under the feet.
    var shadowX = 1f
    var shadowAlpha = 0.4f
    when (state) {
        PetState.IDLE -> shadowX = track(loop(t, 3f), EaseInOut, 0f, 1f, 0.5f, 0.9f, 1f, 1f)
        PetState.DONE -> {
            val p = loop(t, 1.2f)
            shadowX = track(p, Hop, 0f, 1f, 0.15f, 1f, 0.45f, 0.7f, 0.75f, 1f, 1f, 1f)
            shadowAlpha = track(p, Hop, 0f, 0.4f, 0.15f, 0.4f, 0.45f, 0.2f, 0.75f, 0.4f, 1f, 0.4f)
        }
        else -> Unit
    }
    scale(shadowX, 1f, Offset(200f, 336f)) {
        drawOval(Color.Black.copy(alpha = shadowAlpha), Offset(130f, 327f), Size(140f, 18f))
    }

    // The whole body moves together.
    var dx = 0f
    var dy = 0f
    var sx = 1f
    var sy = 1f
    when (state) {
        PetState.IDLE -> dy = track(loop(t, 3f), EaseInOut, 0f, 0f, 0.5f, -6f, 1f, 0f)
        PetState.WORKING -> dy = track(loop(t, 1.6f), EaseInOut, 0f, 0f, 0.5f, -2f, 1f, 0f)
        PetState.QUESTION -> dy = track(loop(t, 2.4f), EaseInOut, 0f, 0f, 0.5f, -4f, 1f, 0f)
        PetState.DONE -> {
            val p = loop(t, 1.2f)
            dy = track(p, Hop, 0f, 0f, 0.15f, 0f, 0.45f, -30f, 0.75f, 0f, 1f, 0f)
            sx = track(p, Hop, 0f, 1.03f, 0.15f, 1f, 0.75f, 1f, 0.85f, 1.04f, 1f, 1.03f)
            sy = track(p, Hop, 0f, 0.96f, 0.15f, 1f, 0.75f, 1f, 0.85f, 0.95f, 1f, 0.96f)
        }
        PetState.ERROR -> dx = track(loop(t, 2f), EaseInOut, 0f, 0f, 0.04f, -7f, 0.08f, 7f, 0.12f, -5f, 0.16f, 5f, 0.2f, -2f, 0.24f, 2f, 0.3f, 0f, 1f, 0f)
        PetState.SLEEP -> {
            val p = loop(t, 4f)
            sx = track(p, EaseInOut, 0f, 1f, 0.5f, 1.02f, 1f, 1f)
            sy = track(p, EaseInOut, 0f, 1f, 0.5f, 0.97f, 1f, 1f)
        }
    }
    val alarm = if (state == PetState.ERROR) track(loop(t, 2f), EaseInOut, 0f, 0f, 0.04f, 1f, 0.24f, 1f, 0.3f, 0f, 1f, 0f) else 0f
    withTransform({
        translate(dx, dy)
        scale(sx, sy, Offset(200f, 317f))
    }) {
        legs()
        body(state, t, alarm)
        if (state == PetState.WORKING) terminal(t)
        arms(state, t)
        head(state, t, alarm)
    }
    extras(state, t, text)
}

private fun DrawScope.legs() {
    for ((x, y, w, h, r) in listOf(
        floatArrayOf(166f, 283f, 18f, 30f, 8f), floatArrayOf(216f, 283f, 18f, 30f, 8f),
        floatArrayOf(158f, 305f, 30f, 12f, 6f), floatArrayOf(212f, 305f, 30f, 12f, 6f),
    )) drawRoundRect(White, Offset(x, y), Size(w, h), CornerRadius(r))
}

private operator fun FloatArray.component5() = this[4]

private fun DrawScope.body(state: PetState, t: Float, alarm: Float) {
    drawRect(White, Offset(194f, 200f), Size(12f, 20f))
    drawRoundRect(White, Offset(148f, 215f), Size(104f, 72f), CornerRadius(22f))
    val glow = when (state) {
        PetState.WORKING -> track(loop(t, 0.8f), EaseInOut, 0f, 1f, 0.5f, 0.5f, 1f, 1f)
        PetState.SLEEP -> track(loop(t, 4f), EaseInOut, 0f, 0.35f, 0.5f, 0.8f, 1f, 0.35f)
        else -> 1f
    }
    drawCircle(mix(Blue, Red, alarm).copy(alpha = glow), 11f, Offset(200f, 251f))
}

private fun DrawScope.terminal(t: Float) {
    drawRoundRect(Term, Offset(150f, 256f), Size(100f, 40f), CornerRadius(8f))
    drawRoundRect(Blue, Offset(150f, 256f), Size(100f, 40f), CornerRadius(8f), style = Stroke(3f))
    drawRoundRect(Blue, Offset(160f, 266f), Size(44f, 5f), CornerRadius(2.5f))
    val grow = track(loop(t, 1.6f), EaseOut, 0f, 0.1f, 0.7f, 1f, 1f, 1f)
    scale(grow, 1f, Offset(160f, 278f)) { drawRoundRect(TermLine, Offset(160f, 276f), Size(60f, 5f), CornerRadius(2.5f)) }
    val cursor = track(loop(t, 0.7f), Step, 0f, 1f, 0.5f, 0.2f, 1f, 0.2f)
    drawRoundRect(White.copy(alpha = cursor), Offset(224f, 276f), Size(10f, 5f), CornerRadius(2f))
}

/** One arm: from the shoulder through a curve to the hand, turned about the shoulder. */
private fun DrawScope.arm(shoulder: Offset, control: Offset, hand: Offset, degrees: Float) {
    rotate(degrees, shoulder) {
        val path = Path().apply {
            moveTo(shoulder.x, shoulder.y)
            quadraticBezierTo(control.x, control.y, hand.x, hand.y)
        }
        drawPath(path, White, style = Stroke(10f, cap = StrokeCap.Round))
        drawCircle(White, 9f, hand)
    }
}

private fun DrawScope.arms(state: PetState, t: Float) {
    val l = Offset(151f, 236f)
    val r = Offset(249f, 236f)
    when (state) {
        PetState.IDLE -> {
            val sway = track(loop(t, 3f), EaseInOut, 0f, 0f, 0.5f, 4f, 1f, 0f)
            arm(l, Offset(127f, 246f), Offset(123f, 276f), sway)
            arm(r, Offset(273f, 246f), Offset(277f, 276f), sway)
        }
        PetState.WORKING -> {
            val p = loop(t, 0.32f)
            arm(l, Offset(133f, 258f), Offset(157f, 270f), track(p, EaseInOut, 0f, 0f, 0.5f, -6f, 1f, 0f))
            arm(r, Offset(267f, 258f), Offset(243f, 270f), track(p, EaseInOut, 0f, -5f, 0.5f, 1f, 1f, -5f))
        }
        PetState.QUESTION -> {
            arm(l, Offset(127f, 246f), Offset(123f, 276f), 0f)
            arm(r, Offset(279f, 230f), Offset(287f, 198f), track(loop(t, 0.9f), EaseInOut, 0f, -8f, 0.5f, 14f, 1f, -8f))
        }
        PetState.DONE -> {
            val p = loop(t, 1.2f)
            arm(l, Offset(121f, 230f), Offset(115f, 196f), track(p, EaseInOut, 0f, 0f, 0.45f, 12f, 1f, 0f))
            arm(r, Offset(279f, 230f), Offset(285f, 196f), track(p, EaseInOut, 0f, 0f, 0.45f, -12f, 1f, 0f))
        }
        PetState.ERROR, PetState.SLEEP -> {
            arm(Offset(151f, 238f), Offset(137f, 254f), Offset(135f, 284f), 0f)
            arm(Offset(249f, 238f), Offset(263f, 254f), Offset(265f, 284f), 0f)
        }
    }
}

private fun DrawScope.head(state: PetState, t: Float, alarm: Float) {
    val tilt = when (state) {
        PetState.QUESTION -> track(loop(t, 2.4f), EaseInOut, 0f, -9f, 0.5f, -5f, 1f, -9f)
        PetState.SLEEP -> 6f
        else -> 0f
    }
    withTransform({
        rotate(tilt, Offset(200f, 215f))
        if (state == PetState.SLEEP) translate(0f, 6f)
    }) {
        antenna(state, t)
        withTransform({
            translate(200f, 150f)
            scale(0.62f, 0.62f, Offset.Zero)
            translate(-64f, -111.86f)
        }) {
            drawPath(headMark, White)
            drawCircle(mix(Blue, Red, alarm).copy(alpha = if (state == PetState.SLEEP) 0.6f else 1f), 55.98f, Offset(63.98f, 111.86f))
        }
        eye(state, t)
        if (state == PetState.WORKING) {
            val sweep = 46f / (2f * PI.toFloat() * 39.6f) * 360f
            rotate(360f * loop(t, 1.1f), Offset(200f, 150f)) {
                drawArc(Blue, 0f, sweep, false, Offset(160.4f, 110.4f), Size(79.2f, 79.2f), style = Stroke(5f, cap = StrokeCap.Round))
            }
        }
    }
}

private fun DrawScope.antenna(state: PetState, t: Float) {
    val lean = when (state) {
        PetState.ERROR -> 22f
        PetState.SLEEP -> -28f
        else -> 0f
    }
    rotate(lean, Offset(200f, 100f)) {
        drawLine(White, Offset(200f, 100f), Offset(200f, 52f), 7f, StrokeCap.Round)
        var radius = 10f
        val (color, alpha) = when (state) {
            PetState.IDLE -> Blue to track(loop(t, 3f), EaseInOut, 0f, 1f, 0.5f, 0.45f, 1f, 1f)
            PetState.WORKING -> Blue to track(loop(t, 0.5f), Step, 0f, 1f, 0.5f, 0.2f, 1f, 0.2f)
            PetState.QUESTION -> {
                val p = loop(t, 1f)
                radius = track(p, EaseInOut, 0f, 10f, 0.5f, 13f, 1f, 10f)
                Amber to track(p, EaseInOut, 0f, 1f, 0.5f, 0.5f, 1f, 1f)
            }
            PetState.DONE -> Blue to 1f
            PetState.ERROR -> Red to track(loop(t, 1f), Step, 0f, 1f, 0.5f, 0.25f, 1f, 0.25f)
            PetState.SLEEP -> Slate to 1f
        }
        drawCircle(color.copy(alpha = alpha), radius, Offset(200f, 45f))
    }
}

private fun DrawScope.eye(state: PetState, t: Float) {
    when (state) {
        PetState.DONE -> drawPath(Path().apply { moveTo(186f, 156f); quadraticBezierTo(203f, 136f, 220f, 156f) }, Ink, style = Stroke(7f, cap = StrokeCap.Round))
        PetState.SLEEP -> drawPath(Path().apply { moveTo(186f, 152f); quadraticBezierTo(203f, 162f, 220f, 152f) }, Ink, style = Stroke(6f, cap = StrokeCap.Round))
        PetState.ERROR -> {
            val stroke = 6f
            drawLine(Ink, Offset(193f, 139f), Offset(215f, 161f), stroke, StrokeCap.Round)
            drawLine(Ink, Offset(215f, 139f), Offset(193f, 161f), stroke, StrokeCap.Round)
        }
        else -> {
            val blink = if (state == PetState.IDLE) track(loop(t, 4.5f), EaseInOut, 0f, 1f, 0.44f, 1f, 0.47f, 0.1f, 0.5f, 1f, 1f, 1f) else 1f
            val (px, py) = when (state) {
                PetState.IDLE -> track(loop(t, 9f), EaseInOut, 0f, 0f, 0.3f, 0f, 0.36f, -7f, 0.52f, -7f, 0.58f, 5f, 0.8f, 5f, 0.86f, 0f, 1f, 0f) to 0f
                PetState.WORKING -> track(loop(t, 1.6f), EaseInOut, 0f, -7f, 0.5f, 6f, 1f, -7f) to 0f
                else -> 5f to -5f
            }
            scale(1f, blink, Offset(204f, 149f)) {
                translate(px, py) {
                    drawCircle(Ink, 16f, Offset(204f, 149f))
                    drawCircle(White, 5f, Offset(210f, 143f))
                    drawCircle(White.copy(alpha = 0.7f), 2.2f, Offset(199f, 155f))
                }
            }
        }
    }
}

private fun DrawScope.speechTail(color: Color) {
    drawPath(Path().apply { moveTo(264f, 82f); lineTo(254f, 100f); lineTo(278f, 86f); close() }, color)
}

private fun DrawScope.extras(state: PetState, t: Float, text: androidx.compose.ui.text.TextMeasurer) {
    when (state) {
        PetState.QUESTION -> {
            val p = loop(t, 2.4f)
            val s = track(p, Pop, 0f, 0.4f, 0.12f, 1f, 0.88f, 1f, 1f, 0.9f)
            val a = track(p, Pop, 0f, 0f, 0.12f, 1f, 0.88f, 1f, 1f, 0f).coerceIn(0f, 1f)
            scale(s, s, Offset(262f, 96f)) {
                drawRoundRect(Amber.copy(alpha = a), Offset(254f, 30f), Size(62f, 58f), CornerRadius(18f))
                speechTail(Amber.copy(alpha = a))
                val mark = text.measure("?", TextStyle(color = Ink.copy(alpha = a), fontSize = 42f.toSp(), fontWeight = FontWeight.Bold))
                drawText(mark, topLeft = Offset(285f - mark.size.width / 2f, 74f - mark.firstBaseline))
            }
        }
        PetState.ERROR -> {
            val nudge = track(loop(t, 2f), EaseInOut, 0f, 0f, 0.06f, -10f, 0.14f, 8f, 0.22f, -4f, 0.3f, 0f, 1f, 0f)
            rotate(nudge, Offset(262f, 96f)) {
                drawRoundRect(Red, Offset(254f, 34f), Size(54f, 54f), CornerRadius(16f))
                speechTail(Red)
                drawRoundRect(White, Offset(277f, 44f), Size(8f, 24f), CornerRadius(4f))
                drawCircle(White, 5f, Offset(281f, 77f))
            }
        }
        PetState.DONE -> for ((spark, path) in sparks) {
            val p = loop(t, 1.2f, spark.lead)
            val s = track(p, EaseInOut, 0f, 0f, 0.4f, 1f, 0.7f, 0.6f, 1f, 0f)
            val turn = track(p, EaseInOut, 0f, 0f, 0.4f, 45f, 0.7f, 90f, 1f, 0f)
            val a = track(p, EaseInOut, 0f, 0f, 0.4f, 1f, 0.7f, 0.8f, 1f, 0f)
            if (s <= 0.01f) continue
            withTransform({
                rotate(turn, Offset(spark.x, spark.y))
                scale(s, s, Offset(spark.x, spark.y))
            }) { drawPath(path, spark.color.copy(alpha = a)) }
        }
        PetState.SLEEP -> for ((i, z) in listOf(Triple(250f, 96f, 22f), Triple(268f, 74f, 30f), Triple(286f, 52f, 38f)).withIndex()) {
            val p = loop(t, 4f, -1.3f * i)
            val a = track(p, EaseOut, 0f, 0f, 0.2f, 1f, 1f, 0f)
            val zx = track(p, EaseOut, 0f, -10f, 1f, 14f)
            val zy = track(p, EaseOut, 0f, 16f, 1f, -22f)
            val glyph = text.measure("z", TextStyle(color = White.copy(alpha = a), fontSize = z.third.toSp(), fontWeight = FontWeight.Bold))
            // The view box places a glyph by its baseline.
            drawText(glyph, topLeft = Offset(z.first + zx, z.second + zy - glyph.firstBaseline))
        }
        else -> Unit
    }
}
