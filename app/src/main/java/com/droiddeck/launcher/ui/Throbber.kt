package com.droiddeck.launcher.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.rotate
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The DroidDeck mark as the loading indicator. Every loop the D's square half bends into an arc,
 * the ring they make turns once around the ball, and the half straightens back into the D.
 */
@Composable
internal fun LogoThrobber(modifier: Modifier = Modifier, running: Boolean = true) {
    val ink = MaterialTheme.colorScheme.onBackground
    val ball = LocalPalette.current.signal
    val loop = rememberInfiniteTransition(label = "throbber")
        .animateFloat(0f, 1f, infiniteRepeatable(tween(LOOP_MS, easing = LinearEasing)), label = "loop")
    // Animations off in the system settings, or nothing running: the plain D.
    val still = !running || Motion.scale == 0f
    val left = remember { Path() }
    val right = remember { Path() }
    Canvas(modifier.aspectRatio(LogoShape.WIDTH / LogoShape.HEIGHT)) {
        val p = if (still) 0f else loop.value
        val bend = when {
            p < 0.10f -> 0f
            p < 0.24f -> Settle.transform((p - 0.10f) / 0.14f)
            p < 0.76f -> 1f
            p < 0.90f -> 1f - Settle.transform((p - 0.76f) / 0.14f)
            else -> 0f
        }
        val turn = if (p < 0.16f || p > 0.84f) 0f else Turn.transform((p - 0.16f) / 0.68f) * 360f
        val ballScale = when {
            p < 0.12f -> 1f
            p < 0.30f -> 1f - 0.2f * Settle.transform((p - 0.12f) / 0.18f)
            p < 0.70f -> 0.8f
            p < 0.88f -> 0.8f + 0.2f * Settle.transform((p - 0.70f) / 0.18f)
            else -> 1f
        }
        val s = size.width / LogoShape.WIDTH
        fun px(x: Float, y: Float) = Offset((x - LogoShape.LEFT) * s, y * s)
        val centre = px(LogoShape.CX, LogoShape.CY)
        LogoShape.left(left, bend, ::px)
        LogoShape.right(right, ::px)
        rotate(turn, centre) {
            drawPath(left, ink)
            drawPath(right, ink)
        }
        drawCircle(ball, radius = LogoShape.BALL * s * ballScale, center = centre)
    }
}

/** The ball of a throbber laid out in [bounds]: its centre and its radius, at rest. */
internal fun throbberBall(bounds: androidx.compose.ui.geometry.Rect): Pair<Offset, Float> {
    val s = bounds.width / LogoShape.WIDTH
    return Offset(bounds.left + (LogoShape.CX - LogoShape.LEFT) * s, bounds.top + LogoShape.CY * s) to LogoShape.BALL * s
}

private const val LOOP_MS = 1900
private val Settle = CubicBezierEasing(0.5f, 0f, 0.3f, 1f)
private val Turn = CubicBezierEasing(0.65f, 0f, 0.35f, 1f)

/**
 * The logo's geometry in its SVG's own units (viewBox -55.98 0 239.92 223.72): a D of a square
 * left side and a round right side, cut by a ring around the ball and split top and bottom.
 * The left half is sampled along rays from the centre so it can bend, point for point, into the
 * mirror of the right half's arc.
 */
private object LogoShape {
    const val LEFT = -55.98f
    const val WIDTH = 239.92f
    const val HEIGHT = 223.72f
    const val CX = 63.98f
    const val CY = 111.86f
    const val BALL = 55.98f
    private const val OUTER = 111.86f
    private const val INNER = 71.81f
    private const val SQUARE_X = -31.98f
    private const val SPLIT_LEFT = 56.055f
    private const val SPLIT_RIGHT = 71.885f

    private class Pt(val x: Float, val y: Float)

    private fun at(a: Double, r: Float) = Pt((CX + r * cos(a)).toFloat(), (CY + r * sin(a)).toFloat())
    private fun angle(x: Float, y: Float) = atan2((y - CY).toDouble(), (x - CX).toDouble())
    // Angles taken the long way round, through the left: from about -94 degrees down to -266.
    private fun leftward(a: Double) = if (a < 0) a else a - 2 * Math.PI

    private val rays: List<Double> = run {
        val top = angle(SPLIT_LEFT, 0f)
        val topCorner = angle(SQUARE_X, 0f)
        val bottomCorner = leftward(angle(SQUARE_X, HEIGHT))
        val bottom = leftward(angle(SPLIT_LEFT, HEIGHT))
        // The corners are rays of their own, so the square half keeps sharp corners.
        fun span(a: Double, b: Double, n: Int) = List(n) { i -> a + (b - a) * i / (n - 1) }
        span(top, topCorner, 9).dropLast(1) + span(topCorner, bottomCorner, 25).dropLast(1) + span(bottomCorner, bottom, 9)
    }

    private val square: List<Pt> = rays.map { a ->
        val c = cos(a)
        val sn = sin(a)
        val hits = buildList {
            if (c < -1e-9) add((SQUARE_X - CX) / c)
            if (sn < -1e-9) add((0f - CY) / sn)
            if (sn > 1e-9) add((HEIGHT - CY) / sn)
        }
        val t = hits.minOrNull() ?: 0.0
        Pt((CX + t * c).toFloat(), (CY + t * sn).toFloat())
    }.toMutableList().also { it[0] = Pt(SPLIT_LEFT, 0f); it[it.lastIndex] = Pt(SPLIT_LEFT, HEIGHT) }

    private val arc: List<Pt> = rays.map { at(it, OUTER) }.toMutableList().also {
        val dy = sqrt(OUTER * OUTER - (SPLIT_LEFT - CX) * (SPLIT_LEFT - CX))
        it[0] = Pt(SPLIT_LEFT, CY - dy)
        it[it.lastIndex] = Pt(SPLIT_LEFT, CY + dy)
    }

    private val leftInner: List<Pt> = run {
        val dx = (SPLIT_LEFT - CX).toDouble()
        val dy = sqrt(INNER * INNER - dx * dx)
        val from = atan2(dy, dx)
        val to = atan2(-dy, dx) + 2 * Math.PI
        List(rays.size) { i -> at(from + (to - from) * i / (rays.size - 1), INNER) }
    }

    private val rightHalf: List<Pt> = run {
        val dx = (SPLIT_RIGHT - CX).toDouble()
        val oy = sqrt(OUTER * OUTER - dx * dx)
        val iy = sqrt(INNER * INNER - dx * dx)
        val outerFrom = atan2(-oy, dx)
        val outerTo = atan2(oy, dx)
        val innerFrom = atan2(iy, dx)
        val innerTo = atan2(-iy, dx)
        List(40) { i -> at(outerFrom + (outerTo - outerFrom) * i / 39, OUTER) } +
            List(40) { i -> at(innerFrom + (innerTo - innerFrom) * i / 39, INNER) }
    }

    /** The left half, [bend] of the way from the square D side (0) to the arc (1). */
    fun left(path: Path, bend: Float, px: (Float, Float) -> Offset) {
        path.reset()
        square.forEachIndexed { i, q ->
            val r = arc[i]
            val o = px(q.x + (r.x - q.x) * bend, q.y + (r.y - q.y) * bend)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        for (q in leftInner) px(q.x, q.y).let { path.lineTo(it.x, it.y) }
        path.close()
    }

    fun right(path: Path, px: (Float, Float) -> Offset) {
        path.reset()
        rightHalf.forEachIndexed { i, q ->
            val o = px(q.x, q.y)
            if (i == 0) path.moveTo(o.x, o.y) else path.lineTo(o.x, o.y)
        }
        path.close()
    }
}
