package com.droiddeck.launcher.ui

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Outline
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.hypot
import kotlinx.coroutines.coroutineScope

// One focus ring for a whole screen that slides from control to control. When focus moves, the
// edge on the side it is heading springs ahead and the trailing edge catches up a beat later, so
// the ring stretches like a drop and snaps into the new control's shape.

/** A control that can show the ring: where it is and what its ring looks like there. */
internal class GlideSource {
    var coords: LayoutCoordinates? = null
    var shape: Shape by mutableStateOf<Shape>(androidx.compose.ui.graphics.RectangleShape)
    var color by mutableStateOf(Color.Transparent)
    var width by mutableStateOf(2.dp)
}

internal class FocusGlide(val view: View) {
    var host: LayoutCoordinates? = null
    /** Controls that are hot, the latest last: focus and a hovering pointer can each hold one. */
    val hot = mutableStateListOf<GlideSource>()
    /** Bumped when the current control moves (a scroll, a relayout). */
    var moves by mutableIntStateOf(0)
    /** Off while a page floods in or out (PageFlood); back on, it appears on whatever has focus. */
    var hidden by mutableStateOf(false)

    val current: GlideSource? get() = hot.lastOrNull()

    fun claim(s: GlideSource) { hot.remove(s); hot.add(s) }
    fun release(s: GlideSource) { hot.remove(s) }
    fun moved(s: GlideSource) { if (hot.lastOrNull() === s) moves++ }

    /** [s]'s bounds in the host, cut to what is on screen; null when it is not laid out or is scrolled away. */
    fun boundsOf(s: GlideSource): Rect? {
        val h = host ?: return null
        val c = s.coords ?: return null
        if (!h.isAttached || !c.isAttached) return null
        val b = h.localBoundingBoxOf(c, clipBounds = true)
        return if (b.width < 1f || b.height < 1f) null else b
    }
}

internal val LocalFocusGlide = staticCompositionLocalOf<FocusGlide?> { null }

/** Floods on screen (a launch, a stop, the return from one): every ring keeps out of the way of them. */
internal object RingVeil {
    var count by mutableIntStateOf(0)
}

/** Keeps the focus ring off while this is in the composition. */
@Composable
internal fun VeilRing() {
    DisposableEffect(Unit) {
        RingVeil.count++
        onDispose { RingVeil.count-- }
    }
}

private fun cornerOf(shape: Shape, size: Size, dir: LayoutDirection, density: Density): Float =
    when (val o = shape.createOutline(size, dir, density)) {
        is Outline.Rounded -> o.roundRect.topLeftCornerRadius.x
        is Outline.Rectangle -> 0f
        is Outline.Generic -> minOf(size.width, size.height) / 2f
    }

/**
 * A control's outline: [restColor] at [restWidth] always, and [hotColor] at [hotWidth] while [hot].
 * Under a [FocusGlideHost] the hot outline is the host's one sliding ring; elsewhere (a dialog, a
 * popup, a screen with no host) it is drawn here, as a plain border.
 */
@Composable
internal fun Modifier.glideBorder(
    hot: Boolean,
    shape: Shape,
    hotColor: Color,
    restColor: Color = Color.Transparent,
    hotWidth: Dp = 2.dp,
    restWidth: Dp = 1.dp,
): Modifier {
    val glide = LocalFocusGlide.current
    if (glide == null || glide.view !== LocalView.current) {
        return border(if (hot) hotWidth else restWidth, if (hot) hotColor else restColor, shape)
    }
    val src = remember { GlideSource() }
    src.shape = shape
    src.color = hotColor
    src.width = hotWidth
    DisposableEffect(glide, hot) {
        if (hot) glide.claim(src)
        onDispose { glide.release(src) }
    }
    val base = if (restColor.alpha > 0f) border(restWidth, restColor, shape) else this
    return base.onGloballyPositioned { src.coords = it; glide.moved(src) }
}

/** Leading edge on a focus move: quick and a little loose. */
private fun lead(): AnimationSpec<Float> = Motion.sp(0.62f, 700f)
/** Trailing edge: slower, settles without a bounce to speak of. */
private fun trail(): AnimationSpec<Float> = Motion.sp(0.78f, 360f)
/** Following a control that moves under the ring (a scroll): tight, no wobble. */
private fun follow(): AnimationSpec<Float> = Motion.sp(1f, 2400f)

/** How long after a focus move the ring keeps its stretchy springs and keeps checking where it is. */
private const val SETTLE_MS = 450L
/** How long the control must stand still before the ring stops checking where it is. */
private const val STILL_MS = 120L
/** The longest the ring keeps checking after a move, for a control that never stands still. */
private const val TRACK_MAX_MS = 2_000L

/** Moves closer together than this are a held direction: the ring runs along as one, no stretch. */
private const val REPEAT_MS = 180L

/** A move further than this goes as a droplet: pinched to a drop, carried across, opened onto the control. */
private val FAR = 360.dp

/**
 * Hosts the sliding focus ring for everything inside it. The ring is drawn over [content], in the
 * host's space, so put the host inside anything that moves as a whole (a sheet that slides in).
 */
@Composable
internal fun FocusGlideHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val view = LocalView.current
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val glide = remember(view) { FocusGlide(view) }
    val edges = remember { List(4) { Animatable(0f) } } // left, top, right, bottom
    val corner = remember { Animatable(0f) }
    /** How solid the ring is: 1 while it travels as a drop, 0 (an outline) on a control. */
    val solid = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    // An edge each, the corner, and a droplet carrying all of them.
    val jobs = remember { arrayOfNulls<Job>(6) }
    val cur = glide.current
    var lastColor by remember { mutableStateOf(Color.Transparent) }
    if (cur != null) lastColor = cur.color
    val color by animateColorAsState(cur?.color ?: lastColor, Motion.tw(160), label = "glideColor")
    var width by remember { mutableStateOf(2.dp) }
    if (cur != null) width = cur.width

    LaunchedEffect(glide, Motion.scale) {
        var shown: GlideSource? = null
        var movedAt = 0L
        var dirSpecs: List<AnimationSpec<Float>> = List(4) { follow() }
        var delays = LongArray(4)
        var lastMove = 0L
        fun go(i: Int, v: Float, spec: AnimationSpec<Float>, wait: Long) {
            // A drop cut short by another move turns back into an outline on the way.
            if (jobs[5]?.isActive == true || solid.targetValue > 0f) scope.launch { solid.animateTo(0f, Motion.tw(120)) }
            jobs[5]?.cancel()
            // Picks up the speed it is going at: restarting from rest on every step of a held
            // direction is what made it stutter.
            val speed = edges[i].velocity
            jobs[i]?.cancel()
            jobs[i] = scope.launch {
                if (wait > 0) delay(Motion.ms(wait.toInt()).toLong())
                edges[i].animateTo(v, spec, initialVelocity = if (wait > 0) 0f else speed)
            }
        }
        /** Pinch into a solid dot where it is, carry it over, and open it into [b] as it arrives. */
        fun droplet(s: GlideSource, b: Rect) {
            jobs.forEach { it?.cancel() }
            val size = s.coords?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: b.size
            val endCorner = cornerOf(s.shape, size, dir, density)
            jobs[5] = scope.launch {
                val r = with(density) { 6.dp.toPx() }
                fun dot(c: Offset) = listOf(c.x - r, c.y - r, c.x + r, c.y + r)
                val here = Offset((edges[0].value + edges[2].value) / 2f, (edges[1].value + edges[3].value) / 2f)
                // Pinch into a solid drop where it is.
                coroutineScope {
                    launch { solid.animateTo(1f, Motion.tw(80)) }
                    launch { corner.animateTo(r, Motion.tw(90)) }
                    dot(here).forEachIndexed { i, v -> launch { edges[i].animateTo(v, Motion.tw(90)) } }
                }
                // Across as a dot, every edge together, on a timed ease, steering each frame for
                // where the control is now: one still rising into place is met where it ends up.
                val distance = hypot(b.center.x - here.x, b.center.y - here.y) / density.density
                val travel = (180 + distance * 0.12f).toInt().coerceIn(220, 380)
                val p = Animatable(0f)
                val flight = launch { p.animateTo(1f, Motion.tw(travel, easing = FastOutSlowInEasing)) }
                // Takes the control's shape as it arrives, not after: no resting as a dot.
                while (flight.isActive && p.value < 0.78f) {
                    val to = (glide.boundsOf(s) ?: b).center
                    dot(Offset(here.x + (to.x - here.x) * p.value, here.y + (to.y - here.y) * p.value)).forEachIndexed { i, v -> edges[i].snapTo(v) }
                    withFrameNanos { }
                }
                flight.cancel()
                val at = glide.boundsOf(s) ?: b
                launch { solid.animateTo(0f, Motion.tw(150)) }
                launch { corner.animateTo(endCorner, Motion.sp(0.8f, 600f)) }
                listOf(at.left, at.top, at.right, at.bottom).forEachIndexed { i, v ->
                    jobs[i] = launch { edges[i].animateTo(v, Motion.sp(0.7f, 750f)) }
                }
            }
        }
        fun cornerTo(s: GlideSource, b: Rect, snap: Boolean) {
            val size = s.coords?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: b.size
            val r = cornerOf(s.shape, size, dir, density)
            jobs[4]?.cancel()
            jobs[4] = scope.launch { if (snap) corner.snapTo(r) else corner.animateTo(r, Motion.sp(0.8f, 500f)) }
        }
        snapshotFlow { Triple(glide.current, glide.moves, glide.hidden || RingVeil.count > 0) }.collectLatest { (src, _, hidden) ->
            if (Motion.scale == 0f) {
                jobs.forEach { it?.cancel() }
                solid.snapTo(0f)
                val b = src?.let { glide.boundsOf(it) }
                if (hidden || b == null) alpha.snapTo(0f)
                else {
                    listOf(b.left, b.top, b.right, b.bottom).forEachIndexed { i, v -> edges[i].snapTo(v) }
                    val size = src.coords?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: b.size
                    corner.snapTo(cornerOf(src.shape, size, dir, density))
                    alpha.snapTo(1f)
                }
                shown = src
                return@collectLatest
            }
            if (hidden) {
                jobs.forEach { it?.cancel() }
                alpha.snapTo(0f)
                solid.snapTo(0f)
                shown = null
                return@collectLatest
            }
            if (src == null) {
                // Focus passing between two controls can let go of one a moment before the next
                // takes it: wait a beat, so that reads as a move and not a fade out and back in.
                delay(60)
                scope.launch { alpha.animateTo(0f, Motion.tw(140)) }
                shown = null
                return@collectLatest
            }
            var first = src !== shown
            val start = System.nanoTime()
            var last: Rect? = null
            var seen: Rect? = null
            var stillSince = start
            while (true) {
                val b = glide.boundsOf(src)
                if (b != seen) { seen = b; stillSince = System.nanoTime() }
                if (b == null) {
                    if (first) scope.launch { alpha.animateTo(0f, Motion.tw(140)) }
                } else if (first) {
                    var dropped = false
                    val prev = shown
                    val wasShowing = prev != null && alpha.targetValue > 0f
                    shown = src
                    first = false
                    if (!wasShowing) {
                        // Appearing: a hair bigger, pulled in onto the control as it fades up.
                        val out = with(density) { 3.dp.toPx() }
                        val grown = listOf(b.left - out, b.top - out, b.right + out, b.bottom + out)
                        jobs.forEach { it?.cancel() }
                        scope.launch { solid.snapTo(0f); edges.forEachIndexed { i, e -> e.snapTo(grown[i]) } }.join()
                        dirSpecs = List(4) { Motion.sp(0.6f, 700f) }
                        delays = LongArray(4)
                        cornerTo(src, b, snap = true)
                        scope.launch { alpha.animateTo(1f, Motion.tw(140)) }
                    } else {
                        // Moving: the edges on the side it is heading lead.
                        val dx = b.center.x - (edges[0].value + edges[2].value) / 2f
                        val dy = b.center.y - (edges[1].value + edges[3].value) / 2f
                        val horizontal = abs(dx) >= abs(dy)
                        val leadIdx = if (horizontal) (if (dx >= 0) 2 else 0) else (if (dy >= 0) 3 else 1)
                        val trailIdx = (leadIdx + 2) % 4
                        val now = System.nanoTime()
                        val held = (now - lastMove) / 1_000_000 < REPEAT_MS
                        lastMove = now
                        scope.launch { alpha.animateTo(1f, Motion.tw(120)) }
                        dropped = !held && hypot(dx, dy) > with(density) { FAR.toPx() }
                        if (dropped) droplet(src, b)
                        else {
                            // A held direction runs along as one piece; a single press stretches.
                            dirSpecs = if (held) List(4) { Motion.sp(0.9f, 900f) }
                                else List(4) { i -> when (i) { leadIdx -> lead(); trailIdx -> trail(); else -> Motion.sp(0.7f, 520f) } }
                            delays = LongArray(4) { i -> if (i == trailIdx && !held) 30L else 0L }
                            cornerTo(src, b, snap = false)
                        }
                    }
                    movedAt = System.nanoTime()
                    if (!dropped) listOf(b.left, b.top, b.right, b.bottom).forEachIndexed { i, v -> go(i, v, dirSpecs[i], delays[i]) }
                    last = b
                } else if (b != last && jobs[5]?.isActive != true) {
                    // It moved under the ring: keep the stretch while the move is fresh, else follow.
                    val fresh = (System.nanoTime() - movedAt) / 1_000_000 < SETTLE_MS
                    listOf(b.left, b.top, b.right, b.bottom).forEachIndexed { i, v -> go(i, v, if (fresh) dirSpecs[i] else follow(), 0L) }
                    if (last == null) scope.launch { alpha.animateTo(1f, Motion.tw(120)) }
                    last = b
                }
                // Layer animations (a tile growing, a row sliding, a page rising in) move it without
                // a relayout, so look again each frame until it has stood still a moment: a fixed
                // window let the ring park mid-rise and jump over when something next laid out.
                val now = System.nanoTime()
                val settled = (now - start) / 1_000_000 > SETTLE_MS && (now - stillSince) / 1_000_000 > STILL_MS
                if (settled || (now - start) / 1_000_000 > TRACK_MAX_MS) break
                withFrameNanos { }
            }
        }
    }

    Box(
        modifier
            .onGloballyPositioned { glide.host = it }
            .drawWithContent {
                drawContent()
                val a = alpha.value
                if (a <= 0.01f) return@drawWithContent
                val w = width.toPx()
                val l = edges[0].value; val t = edges[1].value; val r = edges[2].value; val b = edges[3].value
                val sw = (r - l - w).coerceAtLeast(0f)
                val sh = (b - t - w).coerceAtLeast(0f)
                val rad = (corner.value - w / 2f).coerceIn(0f, minOf(sw, sh) / 2f)
                val f = solid.value
                if (f > 0.01f) drawRoundRect(
                    color, topLeft = Offset(l, t), size = Size(r - l, b - t),
                    cornerRadius = CornerRadius(corner.value), alpha = a * f,
                )
                if (f < 0.99f) drawRoundRect(
                    color, topLeft = Offset(l + w / 2f, t + w / 2f), size = Size(sw, sh),
                    cornerRadius = CornerRadius(rad), style = Stroke(w), alpha = a,
                )
            },
    ) {
        CompositionLocalProvider(LocalFocusGlide provides glide) { content() }
    }
}
