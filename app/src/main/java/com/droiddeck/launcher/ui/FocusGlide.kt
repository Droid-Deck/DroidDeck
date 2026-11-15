package com.droiddeck.launcher.ui

import android.view.View
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
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
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
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


internal class GlideSource {
    var coords: LayoutCoordinates? = null
    var shape: Shape by mutableStateOf<Shape>(androidx.compose.ui.graphics.RectangleShape)
    var color by mutableStateOf(Color.Transparent)
    var width by mutableStateOf(2.dp)
    var fillAlpha by mutableFloatStateOf(RING_FILL)
    val requester = androidx.compose.ui.focus.FocusRequester()
}

internal class FocusGlide(view: View?) {
    private val deferredWindow = view == null
    var view = view
        private set

    fun accepts(view: View): Boolean {
        // DropdownMenu recreates its popup view after closing.
        if (this.view == null || deferredWindow && this.view !== view && host?.isAttached != true) this.view = view
        return this.view === view
    }

    var host: LayoutCoordinates? = null
    val hot = mutableStateListOf<GlideSource>()
    var moves by mutableIntStateOf(0)
    var hidden by mutableStateOf(false)

    val current: GlideSource? get() = hot.lastOrNull()

    fun claim(s: GlideSource) { hot.remove(s); hot.add(s) }
    fun release(s: GlideSource) { hot.remove(s) }
    fun moved(s: GlideSource) { if (hot.lastOrNull() === s) moves++ }

    fun boundsOf(s: GlideSource): Rect? {
        val h = host ?: return null
        val c = s.coords ?: return null
        if (!h.isAttached || !c.isAttached) return null
        val b = h.localBoundingBoxOf(c, clipBounds = true)
        return if (b.width < 1f || b.height < 1f) null else b
    }
}

internal val LocalFocusGlide = staticCompositionLocalOf<FocusGlide?> { null }

internal object RingVeil {
    private var state = mutableIntStateOf(0)
    var count: Int
        get() = state.intValue
        set(value) { state.intValue = value }

    internal fun reset() { state = mutableIntStateOf(0) }
}

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

@Composable
internal fun Modifier.glideBorder(
    hot: Boolean,
    shape: Shape,
    hotColor: Color,
    restColor: Color = Color.Transparent,
    hotWidth: Dp = 2.dp,
    restWidth: Dp = 1.dp,
    hotFillAlpha: Float = RING_FILL,
): Modifier {
    val glide = LocalFocusGlide.current
    if (glide == null || !glide.accepts(LocalView.current)) {
        return border(if (hot) hotWidth else restWidth, if (hot) hotColor else restColor, shape)
    }
    val src = remember { GlideSource() }
    src.shape = shape
    src.color = hotColor
    src.width = hotWidth
    src.fillAlpha = hotFillAlpha
    DisposableEffect(glide, hot) {
        if (hot) glide.claim(src)
        onDispose { glide.release(src) }
    }
    val base = if (restColor.alpha > 0f) border(restWidth, restColor, shape) else this
    return base.focusRequester(src.requester).onGloballyPositioned { src.coords = it; glide.moved(src) }
}

private class Edge(val damping: Float, val stiffness: Float)

private fun lead() = Edge(0.62f, 700f)
private fun trail() = Edge(0.78f, 360f)
private fun follow() = Edge(1f, 2400f)

private const val SETTLE_MS = 450L
private const val STILL_MS = 120L
private const val TRACK_MAX_MS = 2_000L

private const val RING_FILL = 0.14f

private const val REPEAT_MS = 180L

private val FAR = 360.dp

@Composable
internal fun Modifier.focusGlide(
    shape: Shape = Shape12,
    color: Color = LocalPalette.current.signal,
): Modifier {
    var focused by remember { mutableStateOf(false) }
    return glideBorder(focused, shape, color, hotFillAlpha = 0f)
        .onFocusChanged { focused = it.isFocused }
}

@Composable
internal fun FocusGlideHost(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val view = LocalView.current
    val glide = remember(view) { FocusGlide(view) }
    Box(modifier.focusGlideOverlay(glide)) {
        CompositionLocalProvider(LocalFocusGlide provides glide) { content() }
    }
}

@Composable
internal fun FocusGlideWindow(content: @Composable (Modifier) -> Unit) {
    val glide = remember { FocusGlide(null) }
    val overlay = Modifier.focusGlideOverlay(glide)
    CompositionLocalProvider(LocalFocusGlide provides glide) { content(overlay) }
}

@Composable
private fun Modifier.focusGlideOverlay(glide: FocusGlide): Modifier {
    val density = LocalDensity.current
    val dir = LocalLayoutDirection.current
    val edges = remember { List(4) { Animatable(0f) } }
    val corner = remember { Animatable(0f) }
    val solid = remember { Animatable(0f) }
    val alpha = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    val jobs = remember { arrayOfNulls<Job>(8) }
    val target = remember { FloatArray(4) }
    val vel = remember { FloatArray(4) }
    val springs = remember { Array(4) { follow() } }
    val startAt = remember { LongArray(4) }
    val cur = glide.current
    var lastColor by remember { mutableStateOf(Color.Transparent) }
    if (cur != null) lastColor = cur.color
    val color by animateColorAsState(cur?.color ?: lastColor, Motion.tw(160), label = "glideColor")
    var width by remember { mutableStateOf(2.dp) }
    if (cur != null) width = cur.width
    var fillAlpha by remember { mutableFloatStateOf(RING_FILL) }
    if (cur != null) fillAlpha = cur.fillAlpha

    LaunchedEffect(glide, Motion.scale) {
        var shown: GlideSource? = null
        var movedAt = 0L
        var dirSpecs: List<Edge> = List(4) { follow() }
        var delays = LongArray(4)
        var lastMove = 0L
        fun go(i: Int, v: Float, spec: Edge, wait: Long) {
            if (jobs[5]?.isActive == true || solid.targetValue > 0f) {
                jobs[5]?.cancel()
                jobs[6]?.cancel()
                jobs[6] = scope.launch { solid.animateTo(0f, Motion.tw(120)) }
            }
            val chasing = jobs[7]?.isActive == true
            jobs[i]?.let { if (it.isActive) { it.cancel(); vel[i] = edges[i].velocity } }
            if (!chasing) for (j in 0..3) if (jobs[j]?.isActive != true) vel[j] = edges[j].velocity
            target[i] = v
            springs[i] = spec
            startAt[i] = System.nanoTime() + Motion.ms(wait.toInt()) * 1_000_000L
            if (chasing) return
            jobs[7] = scope.launch {
                var last = withFrameNanos { it }
                while (true) {
                    val frame = withFrameNanos { it }
                    val dt = ((frame - last) / 1e9f).coerceAtMost(0.1f) / Motion.scale.coerceAtLeast(0.01f)
                    last = frame
                    val now = System.nanoTime()
                    var settled = true
                    for (j in 0..3) {
                        if (now < startAt[j]) { settled = false; continue }
                        val k = springs[j].stiffness
                        val c = 2f * springs[j].damping * kotlin.math.sqrt(k)
                        var x = edges[j].value
                        var u = vel[j]
                        var left = dt
                        while (left > 0f) {
                            val h = minOf(left, 0.004f)
                            u += (-k * (x - target[j]) - c * u) * h
                            x += u * h
                            left -= h
                        }
                        if (abs(x - target[j]) < 0.3f && abs(u) < 3f) { x = target[j]; u = 0f } else settled = false
                        vel[j] = u
                        edges[j].snapTo(x)
                    }
                    if (settled) break
                }
            }
        }
        fun droplet(s: GlideSource, b: Rect) {
            jobs.forEach { it?.cancel() }
            val size = s.coords?.size?.let { Size(it.width.toFloat(), it.height.toFloat()) } ?: b.size
            val endCorner = cornerOf(s.shape, size, dir, density)
            jobs[5] = scope.launch {
                val r = with(density) { 6.dp.toPx() }
                fun dot(c: Offset) = listOf(c.x - r, c.y - r, c.x + r, c.y + r)
                val here = Offset((edges[0].value + edges[2].value) / 2f, (edges[1].value + edges[3].value) / 2f)
                coroutineScope {
                    launch { solid.animateTo(1f, Motion.tw(80)) }
                    launch { corner.animateTo(r, Motion.tw(90)) }
                    dot(here).forEachIndexed { i, v -> launch { edges[i].animateTo(v, Motion.tw(90)) } }
                }
                val distance = hypot(b.center.x - here.x, b.center.y - here.y) / density.density
                val travel = (180 + distance * 0.12f).toInt().coerceIn(220, 380)
                val p = Animatable(0f)
                val flight = launch { p.animateTo(1f, Motion.tw(travel, easing = FastOutSlowInEasing)) }
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
                delay(60)
                jobs.forEach { it?.cancel() }
                solid.snapTo(0f)
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
                        val out = with(density) { 3.dp.toPx() }
                        val grown = listOf(b.left - out, b.top - out, b.right + out, b.bottom + out)
                        jobs.forEach { it?.cancel() }
                        scope.launch { solid.snapTo(0f); edges.forEachIndexed { i, e -> e.snapTo(grown[i]) } }.join()
                        dirSpecs = List(4) { Edge(0.6f, 700f) }
                        delays = LongArray(4)
                        cornerTo(src, b, snap = true)
                        scope.launch { alpha.animateTo(1f, Motion.tw(140)) }
                    } else {
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
                            dirSpecs = if (held) List(4) { Edge(0.9f, 900f) }
                                else List(4) { i -> when (i) { leadIdx -> lead(); trailIdx -> trail(); else -> Edge(0.7f, 520f) } }
                            delays = LongArray(4) { i -> if (i == trailIdx && !held) 30L else 0L }
                            cornerTo(src, b, snap = false)
                        }
                    }
                    movedAt = System.nanoTime()
                    if (!dropped) listOf(b.left, b.top, b.right, b.bottom).forEachIndexed { i, v -> go(i, v, dirSpecs[i], delays[i]) }
                    last = b
                } else if (b != last && jobs[5]?.isActive != true) {
                    val fresh = (System.nanoTime() - movedAt) / 1_000_000 < SETTLE_MS
                    listOf(b.left, b.top, b.right, b.bottom).forEachIndexed { i, v -> go(i, v, if (fresh) dirSpecs[i] else follow(), 0L) }
                    if (last == null) scope.launch { alpha.animateTo(1f, Motion.tw(120)) }
                    last = b
                }
                val now = System.nanoTime()
                val settled = (now - start) / 1_000_000 > SETTLE_MS && (now - stillSince) / 1_000_000 > STILL_MS
                if (settled || (now - start) / 1_000_000 > TRACK_MAX_MS) break
                withFrameNanos { }
            }
        }
    }

    return this
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
                if (f < 0.99f) {
                    drawRoundRect(
                        color, topLeft = Offset(l + w / 2f, t + w / 2f), size = Size(sw, sh),
                        cornerRadius = CornerRadius(rad), alpha = a * (1f - f) * fillAlpha,
                    )
                    drawRoundRect(
                        color, topLeft = Offset(l + w / 2f, t + w / 2f), size = Size(sw, sh),
                        cornerRadius = CornerRadius(rad), style = Stroke(w), alpha = a,
                    )
                }
            }
}
