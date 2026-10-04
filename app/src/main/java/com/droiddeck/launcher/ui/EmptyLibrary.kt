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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameMillis
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
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
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
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
 * DroidDeck mark wanders over it, bouncing off the page's edges and off [avoid] (the words and
 * buttons) like an idle DVD logo, carrying a clearing in the wall with it. On arrival its outline
 * traces in, the D fills, and the ball pops toward the viewer, flips twice and slams back into its
 * socket; the ring the slam sends out fills each blank capsule it reaches. Poking the ball plays
 * the pop again, without the mark stopping. [fade] is drawn over the wall and under the mark.
 */
@Composable
internal fun EmptyLibrary(avoid: Rect?, fade: @Composable () -> Unit) {
    val clock = rememberMarkClock(avoid)
    Box(Modifier.fillMaxSize().onSizeChanged { clock.page = Size(it.width.toFloat(), it.height.toFloat()) }) {
        EmptyWall(clock)
        fade()
        Mark(clock)
    }
}

// The trace and fill, in ms from arrival.
private const val TRACE_MS = 1250f
private const val FILL_FROM = 450f
// The ball's timeline: its fade-in, pop, flip and slam, the ripple and the capsules it lights.
private const val POP_FROM = 900f
private const val IMPACT = 2200f
private const val RIPPLE_MS = 1250f
private const val HIT_MS = 900f
private const val END = 3600f
// Where a poke restarts the ball's timeline: just before the wind-up.
private const val POKE = 880f
private const val DRIFT_MS = 75_000f

/**
 * Time into the mark's arrival, into the ball's timeline (a poke restarts only that), into the
 * wall's drift, and where the mark is on the page and where its last slam landed.
 */
private class MarkClock {
    var intro by mutableFloatStateOf(0f)
    var ball by mutableFloatStateOf(0f)
    var wall by mutableFloatStateOf(0f)
    var at by mutableStateOf(Offset.Unspecified)
    var slam by mutableStateOf(Offset.Unspecified)
    var page = Size.Zero
    var poked = false
    fun poke() { if (ball >= IMPACT + RIPPLE_MS) poked = true }
}

@Composable
private fun rememberMarkClock(avoid: Rect?): MarkClock {
    val clock = remember { MarkClock() }
    val haptics = LocalHapticFeedback.current
    val keepOff by rememberUpdatedState(avoid)
    val speed = with(LocalDensity.current) { 64.dp.toPx() }
    val inset = with(LocalDensity.current) { 10.dp.toPx() }
    val clearance = with(LocalDensity.current) { 16.dp.toPx() }
    LaunchedEffect(clock) {
        var last = -1L
        var vx = speed * 0.8f
        var vy = -speed * 0.6f
        while (true) {
            if (clock.page == Size.Zero) { withFrameMillis { }; continue }
            val f = MarkFrame(clock.page, Offset.Zero)
            if (clock.at == Offset.Unspecified) clock.at = Offset(clock.page.width * 0.64f, clock.page.height * 0.42f)
            // Animations off in the system settings: the finished mark, still, on a still wall.
            if (Motion.scale == 0f) {
                clock.intro = END
                clock.ball = END
                clock.wall = 0f
                return@LaunchedEffect
            }
            withFrameMillis { now ->
                val dt = if (last < 0) 0f else min(50f, (now - last) / Motion.scale)
                last = now
                clock.intro += dt
                clock.wall += dt
                if (clock.poked) { clock.poked = false; clock.ball = POKE }
                val before = clock.ball
                clock.ball += dt

                // Drift, bouncing off the page edges and off whatever it should keep clear of.
                var x = clock.at.x + vx * dt / 1000f
                var y = clock.at.y + vy * dt / 1000f
                val hw = f.halfWidth
                val hh = f.halfHeight
                if (x - hw < inset) { x = inset + hw; vx = abs(vx) }
                if (x + hw > clock.page.width - inset) { x = clock.page.width - inset - hw; vx = -abs(vx) }
                if (y - hh < inset) { y = inset + hh; vy = abs(vy) }
                if (y + hh > clock.page.height - inset) { y = clock.page.height - inset - hh; vy = -abs(vy) }
                keepOff?.inflate(clearance)?.let { r ->
                    if (x + hw > r.left && x - hw < r.right && y + hh > r.top && y - hh < r.bottom) {
                        val outX = if (x < r.center.x) r.left - (x + hw) else r.right - (x - hw)
                        val outY = if (y < r.center.y) r.top - (y + hh) else r.bottom - (y - hh)
                        if (abs(outX) < abs(outY)) { x += outX; vx = if (outX < 0) -abs(vx) else abs(vx) }
                        else { y += outY; vy = if (outY < 0) -abs(vy) else abs(vy) }
                    }
                }
                clock.at = Offset(x, y)

                if (before < IMPACT && clock.ball >= IMPACT) {
                    clock.slam = clock.at
                    haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                }
            }
        }
    }
    return clock
}

/**
 * The mark on a page of [size], centred on [centre]: about a third of the page tall. [k] scales
 * the timeline's distances, which were drawn on a 460px mark.
 */
private class MarkFrame(size: Size, val centre: Offset) {
    val height = min(size.height * 0.3f, size.width * 0.28f * LogoShape.HEIGHT / LogoShape.WIDTH)
    val s = height / LogoShape.HEIGHT
    val halfWidth = (LogoShape.CX - LogoShape.LEFT) * s
    val halfHeight = LogoShape.CY * s
    val ball = LogoShape.BALL * s
    val k = height / 460f
    val ringFrom = ball
    val ringTo = hypot(size.width, size.height) * 1.05f
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
private fun EmptyWall(clock: MarkClock) {
    val pal = LocalPalette.current
    val bg = pal.background
    val hit = lerp(pal.surface, pal.signal, 0.78f)
    Canvas(Modifier.fillMaxSize()) {
        if (clock.at == Offset.Unspecified) return@Canvas
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
        val f = MarkFrame(size, clock.at)
        val origin = clock.slam
        val reach = hypot(capW, capH) / 2 * 0.77f
        val t = clock.ball
        val rippling = origin != Offset.Unspecified && t in IMPACT..IMPACT + RIPPLE_MS + HIT_MS
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
                    var a = 0f
                    if (rippling) {
                        val edge = max(0f, hypot(sx - origin.x, sy - origin.y) - reach)
                        val progress = (edge - f.ringFrom) / (f.ringTo - f.ringFrom)
                        if (progress <= 1f) a = hitAmount(t - (IMPACT + RIPPLE_MS * rippleReaches(clamp01(progress))))
                    }
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
        // The clearing the mark carries with it.
        drawRect(
            Brush.radialGradient(
                0f to bg, 255f / 420f to bg, 330f / 420f to bg.copy(alpha = 0.7f), 1f to Color.Transparent,
                center = f.centre, radius = 420f * f.k,
            ),
        )
    }
}

/** The mark, its ball's pop and flip, and the ring the slam sends out. */
@Composable
private fun Mark(clock: MarkClock) {
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
                if (clock.at == Offset.Unspecified || Motion.scale == 0f) return@detectTapGestures
                val f = MarkFrame(Size(size.width.toFloat(), size.height.toFloat()), clock.at)
                if ((o - clock.at).getDistance() <= f.ball * 1.4f) clock.poke()
            }
        },
    ) {
        if (clock.at == Offset.Unspecified) return@Canvas
        val f = MarkFrame(size, clock.at)
        val k = f.k
        val t = clock.ball
        val intro = clock.intro

        // The ripple, from where the slam landed, under the mark.
        if (clock.slam != Offset.Unspecified && t in IMPACT..IMPACT + RIPPLE_MS) {
            val p = (t - IMPACT) / RIPPLE_MS
            val alpha = if (p < 0.7f) mix(0.9f, 0.35f, p / 0.7f) else mix(0.35f, 0f, (p - 0.7f) / 0.3f)
            drawCircle(
                pal.signal.copy(alpha = alpha), mix(f.ringFrom, f.ringTo, RippleEase.transform(p)), clock.slam,
                style = Stroke(max(2.5f * k, 1.5.dp.toPx())),
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
            val fill = Out.transform(clamp01((intro - FILL_FROM) / 450f))
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

            // The trace around both halves on arrival, the GameCube nod.
            if (intro < TRACE_MS) {
                val drawn = TraceEase.transform(clamp01(intro / 775f))
                val alpha = if (intro < 775f) 1f else 1f - (intro - 775f) / 475f
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
            val shown = Out.transform(clamp01((intro - 650f) / 300f))
            if (shown > 0f) {
                val p = (t - POP_FROM) / (IMPACT - POP_FROM)
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
                val dark = Color.Black.copy(alpha = mix(0.6f, 0.25f, q) * shown)
                val sc = f.centre + Offset(20f * k * q, 48f * k * q)
                drawCircle(
                    Brush.radialGradient(
                        0f to dark, (sr - blur).coerceAtLeast(0f) / (sr + blur) to dark, 1f to Color.Transparent,
                        center = sc, radius = sr + blur,
                    ),
                    sr + blur, sc,
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
                val p = Out.transform((t - IMPACT) / 420f)
                drawCircle(pal.signal.copy(alpha = 1f - p), (f.ball + 6f * k) * mix(1f, 1.4f, p), f.centre, style = Stroke(max(4f * k, 2.dp.toPx())))
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
