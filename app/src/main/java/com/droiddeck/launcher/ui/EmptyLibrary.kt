package com.droiddeck.launcher.ui

import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.unit.dp
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * The Steam tab with nothing installed. The blank wall drifts as the full one does, and the
 * DroidDeck mark sits in a clearing in it. On arrival the mark plays once: its outline traces in,
 * the D fills, the ball pops toward the viewer, flips twice and slams back into its socket, and the
 * ring the slam sends out fills each blank capsule it reaches. Tapping the ball plays it again.
 * [fade] is drawn over the wall and under the mark.
 */
@Composable
internal fun EmptyLibrary(fade: @Composable () -> Unit) {
    val clock = rememberIntroClock()
    Box(Modifier.fillMaxSize()) {
        EmptyWall(clock)
        fade()
        Mark(clock)
    }
}

// The timeline, in ms from arrival.
private const val IMPACT = 2200f
private const val RIPPLE_MS = 1250f
private const val HIT_MS = 900f
private const val END = 3600f
private const val DRIFT_MS = 75_000f

/** Time into the intro and into the wall's drift; a replay restarts the first and not the second. */
private class IntroClock {
    var ms by mutableFloatStateOf(0f)
    var wall by mutableFloatStateOf(0f)
    var start by mutableLongStateOf(-1L)
    fun replay() { start = -1L }
}

@Composable
private fun rememberIntroClock(): IntroClock {
    val clock = remember { IntroClock() }
    val haptics = LocalHapticFeedback.current
    LaunchedEffect(clock) {
        var wallStart = -1L
        while (true) {
            // Animations off in the system settings: the finished mark on a still wall.
            if (Motion.scale == 0f) {
                clock.ms = END
                clock.wall = 0f
                return@LaunchedEffect
            }
            withFrameMillis { now ->
                if (wallStart < 0) wallStart = now
                if (clock.start < 0) clock.start = now
                val before = clock.ms
                clock.ms = (now - clock.start) / Motion.scale
                clock.wall = (now - wallStart) / Motion.scale
                if (before < IMPACT && clock.ms >= IMPACT) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
            }
        }
    }
    return clock
}

/**
 * Where the mark sits on a page of [size]: right of centre, a bit above the middle, as large as
 * the page allows. [k] scales the timeline's distances, which were drawn on a 460px mark.
 */
private class MarkFrame(size: Size) {
    val height = min(size.height * 0.575f, size.width * 0.56f * LogoShape.HEIGHT / LogoShape.WIDTH)
    val s = height / LogoShape.HEIGHT
    val centre = Offset(size.width * 0.672f, size.height * 0.4625f)
    val ball = LogoShape.BALL * s
    val k = height / 460f
    val ringFrom = ball
    val ringTo = 1400f * k
    fun px(x: Float, y: Float) = Offset(centre.x + (x - LogoShape.CX) * s, centre.y + (y - LogoShape.CY) * s)
}

private val InOut = CubicBezierEasing(0.42f, 0f, 0.58f, 1f)
private val Out = CubicBezierEasing(0f, 0f, 0.58f, 1f)
private val TraceEase = CubicBezierEasing(0.45f, 0f, 0.25f, 1f)
private val PopEase = CubicBezierEasing(0.15f, 0.8f, 0.3f, 1f)
private val DropEase = CubicBezierEasing(0.6f, 0f, 0.9f, 0.5f)
private val FlipEase = CubicBezierEasing(0.25f, 0.7f, 0.35f, 1f)
private val RippleEase = CubicBezierEasing(0.2f, 0.6f, 0.35f, 1f)
private val HitEase = CubicBezierEasing(0.2f, 0.7f, 0.3f, 1f)

private fun clamp01(v: Float) = v.coerceIn(0f, 1f)
private fun mix(a: Float, b: Float, t: Float) = a + (b - a) * t

/** One stretch of a keyframed value: [from] to [to] while [p] runs from [a] to [b]. */
private fun span(p: Float, a: Float, b: Float, from: Float, to: Float, ease: Easing) =
    mix(from, to, ease.transform(clamp01((p - a) / (b - a))))

/** When the ripple's edge reaches [progress] of its way out, as a share of the ripple's time. */
private val rippleTime: FloatArray = FloatArray(257) { RippleEase.transform(it / 256f) }
private fun rippleReaches(progress: Float): Float {
    var lo = 0
    var hi = rippleTime.size - 1
    while (lo < hi) {
        val mid = (lo + hi) / 2
        if (rippleTime[mid] < progress) lo = mid + 1 else hi = mid
    }
    return lo / 256f
}

/** How lit a capsule the ripple reached [since] ms ago is: up fast, then fading back. */
private fun hitAmount(since: Float): Float {
    if (since < 0f || since > HIT_MS) return 0f
    val p = since / HIT_MS
    return if (p < 0.14f) HitEase.transform(p / 0.14f) else 1f - HitEase.transform((p - 0.14f) / 0.86f)
}

/** The blank wall: the same tilted, drifting columns as [CapsuleWall], each capsule lit as the ripple passes. */
@Composable
private fun EmptyWall(clock: IntroClock) {
    val pal = LocalPalette.current
    val bg = pal.background
    val hit = lerp(pal.surface, pal.signal, 0.78f)
    Canvas(Modifier.fillMaxSize()) {
        val capW = 112.dp.toPx()
        val capH = 168.dp.toPx()
        val gap = 14.dp.toPx()
        val corner = CornerRadius(10.dp.toPx())
        val line = 1.dp.toPx()
        val tilt = Math.toRadians(13.0)
        val cs = cos(tilt).toFloat()
        val sn = sin(tilt).toFloat()
        val wallW = (size.width * cs + size.height * sn) * 1.1f
        val wallH = max(size.height * 2.4f, (size.width * sn + size.height * cs) * 1.1f)
        val columns = ((wallW + gap) / (capW + gap)).toInt() + 1
        val perRun = ((wallH + gap) / (capH + gap)).toInt() + 1
        val run = (capH + gap) * perRun
        val shift = run * ((clock.wall % DRIFT_MS) / DRIFT_MS)
        val pivot = center
        val left = pivot.x - wallW / 2
        val top = pivot.y - wallH / 2
        val f = MarkFrame(size)
        val reach = hypot(capW, capH) / 2 * 0.77f
        val t = clock.ms
        rotate(-13f, pivot) {
            for (c in 0 until columns) {
                val x = left + c * (capW + gap)
                val dy = if (c % 2 == 0) -shift else shift - run
                for (i in 0 until perRun * 2) {
                    val y = top + i * (capH + gap) + dy
                    // The capsule's centre on the page, out of the wall's tilt.
                    val lx = x + capW / 2 - pivot.x
                    val ly = y + capH / 2 - pivot.y
                    val sx = pivot.x + lx * cs + ly * sn
                    val sy = pivot.y - lx * sn + ly * cs
                    if (sx < -capH || sx > size.width + capH || sy < -capH || sy > size.height + capH) continue
                    val edge = max(0f, hypot(sx - f.centre.x, sy - f.centre.y) - reach)
                    val progress = (edge - f.ringFrom) / (f.ringTo - f.ringFrom)
                    val a = if (progress > 1f) 0f
                    else hitAmount(t - (IMPACT + RIPPLE_MS * rippleReaches(clamp01(progress))))
                    val grow = 1f + 0.045f * a
                    scale(grow, grow, Offset(x + capW / 2, y + capH / 2)) {
                        if (a > 0f) drawRoundRect(
                            pal.signal.copy(alpha = 0.22f * a), Offset(x - 4.dp.toPx(), y - 4.dp.toPx()),
                            Size(capW + 8.dp.toPx(), capH + 8.dp.toPx()), CornerRadius(14.dp.toPx()),
                        )
                        drawRoundRect(lerp(pal.surface, hit, a), Offset(x, y), Size(capW, capH), corner)
                        drawRoundRect(
                            lerp(pal.line, pal.signal, a), Offset(x, y), Size(capW, capH), corner,
                            style = Stroke(line * (1f + a)),
                        )
                    }
                }
            }
        }
        // The clearing the mark sits in.
        val r = 420f * f.k
        drawRect(
            Brush.radialGradient(
                0f to bg, 255f / 420f to bg, 330f / 420f to bg.copy(alpha = 0.7f), 1f to Color.Transparent,
                center = f.centre, radius = r,
            ),
        )
    }
}

/** The mark, its ball's pop and flip, and the ring the slam sends out. */
@Composable
private fun Mark(clock: IntroClock) {
    val pal = LocalPalette.current
    val ink = MaterialTheme.colorScheme.onBackground
    val bg = pal.background
    val slabEdge = lerp(bg, ink, 0.4f)
    val rim = lerp(bg, pal.signal, 0.55f)
    val left = remember { Path() }
    val right = remember { Path() }
    val piece = remember { Path() }
    val measure = remember { PathMeasure() }
    Canvas(
        Modifier.fillMaxSize().pointerInput(clock) {
            detectTapGestures { o ->
                val f = MarkFrame(Size(size.width.toFloat(), size.height.toFloat()))
                if (clock.ms >= END && Motion.scale != 0f && (o - f.centre).getDistance() <= f.ball * 1.2f) clock.replay()
            }
        },
    ) {
        val f = MarkFrame(size)
        val k = f.k
        val t = clock.ms

        // The ripple, under the mark.
        if (t in IMPACT..IMPACT + RIPPLE_MS) {
            val p = (t - IMPACT) / RIPPLE_MS
            val alpha = if (p < 0.7f) mix(0.9f, 0.35f, p / 0.7f) else mix(0.35f, 0f, (p - 0.7f) / 0.3f)
            drawCircle(
                pal.signal.copy(alpha = alpha), mix(f.ringFrom, f.ringTo, RippleEase.transform(p)), f.centre,
                style = Stroke(2.5f * k),
            )
        }

        val shake = if (t in IMPACT..IMPACT + 230f) {
            val p = (t - IMPACT) / 230f
            when {
                p < 0.25f -> span(p, 0f, 0.25f, 0f, 7f, Out)
                p < 0.55f -> span(p, 0.25f, 0.55f, 7f, -3f, Out)
                else -> span(p, 0.55f, 1f, -3f, 0f, Out)
            } * k
        } else 0f

        translate(0f, shake) {
            // The D, as flat raised slabs with a solid edge underneath.
            val fill = Out.transform(clamp01((t - 450f) / 450f))
            if (fill > 0f) {
                val lift = 8f * k * (1f - fill)
                val edge = 5f * f.s
                LogoShape.left(left, 0f) { x, y -> f.px(x, y) + Offset(0f, lift + edge) }
                LogoShape.right(right) { x, y -> f.px(x, y) + Offset(0f, lift + edge) }
                drawPath(left, slabEdge.copy(alpha = fill))
                drawPath(right, slabEdge.copy(alpha = fill))
                LogoShape.left(left, 0f) { x, y -> f.px(x, y) + Offset(0f, lift) }
                LogoShape.right(right) { x, y -> f.px(x, y) + Offset(0f, lift) }
                drawPath(left, ink.copy(alpha = fill))
                drawPath(right, ink.copy(alpha = fill))
            }

            // The trace around both halves, the GameCube nod.
            if (t < 1250f) {
                val drawn = TraceEase.transform(clamp01(t / 775f))
                val alpha = if (t < 775f) 1f else 1f - (t - 775f) / 475f
                LogoShape.left(left, 0f, f::px)
                LogoShape.right(right, f::px)
                for (half in listOf(left, right)) {
                    measure.setPath(half, false)
                    piece.reset()
                    measure.getSegment(0f, measure.length * drawn, piece, true)
                    drawPath(piece, pal.signal.copy(alpha = alpha), style = Stroke(2f * f.s))
                }
            }

            // The ball.
            val shown = Out.transform(clamp01((t - 650f) / 300f))
            if (shown > 0f) {
                val p = (t - 900f) / 1300f
                val (pop, rise) = when {
                    p <= 0f || p >= 1f -> 1f to 0f
                    p < 0.11f -> span(p, 0f, 0.11f, 1f, 0.92f, InOut) to span(p, 0f, 0.11f, 0f, 7f, InOut)
                    p < 0.31f -> span(p, 0.11f, 0.31f, 0.92f, 1.6f, PopEase) to span(p, 0.11f, 0.31f, 7f, -42f, PopEase)
                    p < 0.88f -> span(p, 0.31f, 0.88f, 1.6f, 1.54f, InOut) to span(p, 0.31f, 0.88f, -42f, -34f, InOut)
                    else -> span(p, 0.88f, 1f, 1.54f, 1f, DropEase) to span(p, 0.88f, 1f, -34f, 0f, DropEase)
                }
                val q = clamp01((pop - 1f) / 0.6f)
                val bob = if (t > END) -4f * k * (0.5f - 0.5f * cos(2f * PI.toFloat() * (t - END) / 5200f)) else 0f

                // Its shadow stays on the slabs, spreading and softening as the ball rises.
                val blur = mix(10f, 28f, q) * k
                val sr = f.ball * 0.93f * mix(1f, 1.4f, q)
                drawCircle(
                    Brush.radialGradient(
                        0f to Color.Black.copy(alpha = mix(0.6f, 0.25f, q) * shown),
                        (sr - blur).coerceAtLeast(0f) / (sr + blur) to Color.Black.copy(alpha = mix(0.6f, 0.25f, q) * shown),
                        1f to Color.Transparent,
                        center = f.centre + Offset(20f * k * q, 48f * k * q), radius = sr + blur,
                    ),
                    sr + blur, f.centre + Offset(20f * k * q, 48f * k * q),
                )

                val sq = if (t in IMPACT..IMPACT + 280f) {
                    val s = (t - IMPACT) / 280f
                    when {
                        s < 0.3f -> span(s, 0f, 0.3f, 1f, 1.1f, Out) to span(s, 0f, 0.3f, 1f, 0.88f, Out)
                        s < 0.65f -> span(s, 0.3f, 0.65f, 1.1f, 0.97f, Out) to span(s, 0.3f, 0.65f, 0.88f, 1.04f, Out)
                        else -> span(s, 0.65f, 1f, 0.97f, 1f, Out) to span(s, 0.65f, 1f, 1.04f, 1f, Out)
                    }
                } else 1f to 1f
                val grow = mix(0.6f, 1f, shown) * pop
                val turn = 720f * FlipEase.transform(clamp01((t - 1290f) / 760f))
                translate(0f, rise * k + bob) {
                    scale(grow * sq.first, grow * sq.second, f.centre) {
                        coin(f.centre, f.ball, f.ball * 2f * 0.061f, turn, pal.signal.copy(alpha = shown), rim.copy(alpha = shown))
                    }
                }
            }

            // The flash where it lands.
            if (t in IMPACT..IMPACT + 420f) {
                val p = (t - IMPACT) / 420f
                drawCircle(pal.signal.copy(alpha = 1f - Out.transform(p)), (f.ball + 6f * k) * mix(1f, 1.4f, Out.transform(p)), f.centre, style = Stroke(4f * k))
            }
        }
    }
}

/**
 * The ball as a coin of [thick]ness turned [deg] about its vertical axis: flat faces in the logo's
 * blue, and between them a solid rim that shows as it turns edge-on.
 */
private fun DrawScope.coin(c: Offset, r: Float, thick: Float, deg: Float, face: Color, rim: Color) {
    val a = Math.toRadians(deg.toDouble())
    val cs = cos(a).toFloat()
    val half = thick / 2 * sin(a).toFloat()
    val rx = r * abs(cs)
    if (abs(half) > 0.01f) {
        drawOval(rim, Offset(c.x - half - rx, c.y - r), Size(2 * rx, 2 * r))
        drawOval(rim, Offset(c.x + half - rx, c.y - r), Size(2 * rx, 2 * r))
        drawRect(rim, Offset(min(c.x - half, c.x + half), c.y - r), Size(abs(2 * half), 2 * r))
    }
    val fx = c.x + if (cs >= 0f) half else -half
    drawOval(face, Offset(fx - rx, c.y - r), Size(2 * rx, 2 * r))
}
