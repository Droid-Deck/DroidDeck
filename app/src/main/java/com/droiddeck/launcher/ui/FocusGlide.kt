package com.droiddeck.launcher.ui

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.AnimationSpec
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
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val jobs = remember { arrayOfNulls<Job>(5) }
    val cur = glide.current
    var lastColor by remember { mutableStateOf(Color.Transparent) }
    if (cur != null) lastColor = cur.color
    val color by animateColorAsState(cur?.color ?: lastColor, Motion.tw(160), label = "glideColor")
    var width by remember { mutableStateOf(2.dp) }
    if (cur != null) width = cur.width

    LaunchedEffect(glide) {
        var shown: GlideSource? = null
        var movedAt = 0L
        var dirSpecs: List<AnimationSpec<Float>> = List(4) { follow() }
        var delays = LongArray(4)
        fun go(i: Int, v: Float, spec: AnimationSpec<Float>, wait: Long) {
            jobs[i]?.cancel()
            jobs[i] = scope.launch {
                if (wait > 0) delay(Motion.ms(wait.toInt()).toLong())
                edges[i].animateTo(v, spec)
            }
        }
        fun cornerTo(s: GlideSource, b: Rect, snap: Boolean) {
            val size = s.coords?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: b.size
            val r = cornerOf(s.shape, size, dir, density)
            jobs[4]?.cancel()
            jobs[4] = scope.launch { if (snap) corner.snapTo(r) else corner.animateTo(r, Motion.sp(0.8f, 500f)) }
        }
        snapshotFlow { glide.current to glide.moves }.collectLatest { (src, _) ->
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
            while (true) {
                val b = glide.boundsOf(src)
                if (b == null) {
                    if (first) scope.launch { alpha.animateTo(0f, Motion.tw(140)) }
                } else if (first) {
                    val prev = shown
                    val wasShowing = prev != null && alpha.targetValue > 0f
                    shown = src
                    first = false
                    if (!wasShowing) {
                        // Appearing: a hair bigger, pulled in onto the control as it fades up.
                        val out = with(density) { 3.dp.toPx() }
                        val grown = listOf(b.left - out, b.top - out, b.right + out, b.bottom + out)
                        jobs.forEach { it?.cancel() }
                        scope.launch { edges.forEachIndexed { i, e -> e.snapTo(grown[i]) } }.join()
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
                        dirSpecs = List(4) { i -> when (i) { leadIdx -> lead(); trailIdx -> trail(); else -> Motion.sp(0.7f, 520f) } }
                        delays = LongArray(4) { i -> if (i == trailIdx) 30L else 0L }
                        cornerTo(src, b, snap = false)
                        scope.launch { alpha.animateTo(1f, Motion.tw(120)) }
                    }
                    movedAt = System.nanoTime()
                    listOf(b.left, b.top, b.right, b.bottom).forEachIndexed { i, v -> go(i, v, dirSpecs[i], delays[i]) }
                    last = b
                } else if (b != last) {
                    // It moved under the ring: keep the stretch while the move is fresh, else follow.
                    val fresh = (System.nanoTime() - movedAt) / 1_000_000 < SETTLE_MS
                    listOf(b.left, b.top, b.right, b.bottom).forEachIndexed { i, v -> go(i, v, if (fresh) dirSpecs[i] else follow(), 0L) }
                    if (last == null) scope.launch { alpha.animateTo(1f, Motion.tw(120)) }
                    last = b
                }
                // Layer animations (a tile growing, a row sliding) move it without a relayout, so
                // look again each frame for a moment after anything changes.
                if ((System.nanoTime() - start) / 1_000_000 > SETTLE_MS) break
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
                drawRoundRect(
                    color, topLeft = Offset(l + w / 2f, t + w / 2f), size = Size(sw, sh),
                    cornerRadius = CornerRadius(rad), style = Stroke(w), alpha = a,
                )
            },
    ) {
        CompositionLocalProvider(LocalFocusGlide provides glide) { content() }
    }
}
