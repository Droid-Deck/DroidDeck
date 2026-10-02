package com.droiddeck.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlin.math.hypot
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.delay
import kotlinx.coroutines.coroutineScope
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.snapshotFlow
import androidx.compose.animation.core.spring
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.unit.Dp
import java.lang.ref.WeakReference
import kotlin.math.PI
import kotlin.math.sin

// Starting a session from a blue button: the button grows until the page is its blue, and the
// loading screen opens on that blue and gathers it into the ball of the throbber.
// Stopping one from the drawer runs it the other way: Stop grows until the page is blue, and the
// front end opens on that blue and draws it back down into the button the session came from.
// Any other way out (backing out of the loading screen, closing the ended screen, the session
// ending) spreads the blue back out of the throbber's ball instead (FloodSpread).

/** Carries the flood's colour to the session, which opens on it. */
const val EXTRA_FLOOD = "com.droiddeck.launcher.FLOOD"

/** Where the last pressed blue button sat, so a session started by it can grow out of it. */
internal object LaunchOrigin {
    private var bounds: Rect? = null
    private var button: Any? = null
    private var at = 0L
    /** The last button a session was started from, for a stop to return into. */
    private var last: WeakReference<Any>? = null

    /** The button a flood is growing out of: it steps aside so only the flood shows. */
    var flooding by mutableStateOf<Any?>(null)

    fun mark(r: Rect, key: Any) {
        bounds = r
        button = key
        last = WeakReference(key)
        at = SystemClock.uptimeMillis()
    }

    /**
     * Where the button the running session was started from sits now, if it is still on screen;
     * it hides for the return, as for the flood.
     */
    fun takeReturn(): Rect? {
        val key = last?.get() as? Array<*> ?: return null
        val c = key.getOrNull(0) as? LayoutCoordinates ?: return null
        if (!c.isAttached) return null
        flooding = key
        return c.boundsInRoot()
    }

    /** The button behind a launch starting now, if one was pressed just before; it hides for the flood. */
    fun take(): Rect? {
        val b = bounds?.takeIf { SystemClock.uptimeMillis() - at < 1_500 }
        if (b != null) flooding = button
        bounds = null
        button = null
        return b
    }
}

/** A session stopped behind a flood: the blue the front end opens on, for a few seconds. */
internal object QuitFlood {
    private var color: Int? = null
    private var at = 0L

    fun mark(argb: Int) {
        color = argb
        at = SystemClock.uptimeMillis()
    }

    fun take(): Int? {
        val c = color?.takeIf { SystemClock.uptimeMillis() - at < 3_000 }
        color = null
        return c
    }
}

/** The page a flood grows over, sinking back a touch as it is covered; [progress] is the flood's. */
@Composable
internal fun FloodBehind(progress: () -> Float, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize().graphicsLayer { val k = sinkScale(progress()); scaleX = k; scaleY = k }) { content() }
}

private fun sinkScale(progress: Float) = 1f - 0.04f * progress

private val Gather = CubicBezierEasing(0.6f, 0f, 0.15f, 1f)

/**
 * The pressed button, [from] in root px, stretching out to cover the page. Each edge rides its own
 * loose spring: the ones with furthest to go set off first and quickest, the ones near the page's
 * edge lag and then snap after them, so it pulls like something elastic rather than zooming. It
 * goes blobby in flight, its gradient settles into the flat blue of the ball, and [onProgress]
 * (0 to 1) lets the page behind sink a little. Calls [onCovered] once nothing but blue shows.
 * Swallows touches while it runs.
 */
@Composable
internal fun LaunchFlood(
    from: Rect,
    onProgress: (Float) -> Unit,
    /** The button's own colours, where it is not the page's blue one (a red Stop). */
    fromColors: Pair<Color, Color>? = null,
    cornerAtRest: Dp = 12.dp,
    onCovered: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val start = fromColors ?: (colors.primary to pal.primary2)
    VeilRing()
    // Left, top, right, bottom: 0 on the button, 1 on the page's edge. Springs overshoot past the
    // edge, off the page, so the wobble is felt in the pull and never seen as a shrink.
    val edges = remember { List(4) { Animatable(0f) } }
    val covered by rememberUpdatedState(onCovered)
    val progress by rememberUpdatedState(onProgress)
    var page by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(page != Size.Zero) {
        if (page == Size.Zero) return@LaunchedEffect
        val travel = listOf(from.left, from.top, page.width - from.right, page.height - from.bottom).map { it.coerceAtLeast(0f) }
        val far = travel.max().coerceAtLeast(1f)
        coroutineScope {
            val runs = edges.mapIndexed { i, edge ->
                val lead = travel[i] / far
                launch {
                    delay(Motion.ms((180 * (1f - lead)).toInt()).toLong())
                    edge.animateTo(1f, spring(dampingRatio = 0.48f, stiffness = lerp(110f, 190f, lead))) {
                        progress(edges.sumOf { it.value.coerceIn(0f, 1f).toDouble() }.toFloat() / 4f)
                    }
                }
            }
            // Covered once every edge has reached the page's, the first time round.
            snapshotFlow { edges.all { it.value >= 0.995f } }.first { it }
            covered()
            runs.forEach { it.join() }
        }
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { page = Size(it.width.toFloat(), it.height.toFloat()) }.pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
        drawFlood(from, edges.map { it.value }, start, pal.signal, cornerAtRest.toPx())
    }
}

/**
 * The flood from a button at [from] with its edges [v] of the way to the page's (left, top, right,
 * bottom): the button sinking with the page behind, blobby in flight, its colours [start] giving
 * way to the flat [signal] in the first half.
 */
private fun DrawScope.drawFlood(from: Rect, v: List<Float>, start: Pair<Color, Color>, signal: Color, cornerAtRest: Float) {
    val (l, t, r, b) = v
    val mean = v.sumOf { it.coerceIn(0f, 1f).toDouble() }.toFloat() / 4f
    // The button sinks with the page behind (FloodBehind), so the flood leaves from where it is now.
    val k = sinkScale(mean)
    val cx = size.width / 2f
    val cy = size.height / 2f
    val left = lerp(cx + (from.left - cx) * k, 0f, l)
    val top = lerp(cy + (from.top - cy) * k, 0f, t)
    val right = lerp(cx + (from.right - cx) * k, size.width, r)
    val bottom = lerp(cy + (from.bottom - cy) * k, size.height, b)
    val w = (right - left).coerceAtLeast(0f)
    val h = (bottom - top).coerceAtLeast(0f)
    // A button's corners at rest, a blob in flight, square once it fills the page.
    val blob = sin(PI * mean).toFloat().coerceAtLeast(0f)
    val corner = lerp(cornerAtRest * (1f - mean), minOf(w, h) * 0.42f, blob)
    // The button's gradient gives way to the ball's flat blue in the first half.
    val mix = (mean * 2f).coerceAtMost(1f)
    val brush = Brush.linearGradient(
        listOf(lerp(start.first, signal, mix), lerp(start.second, signal, mix)),
        start = Offset(left, top), end = Offset(right, bottom),
    )
    drawRoundRect(brush, topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(corner))
}

/**
 * The front end's first frames after a stop: the page in [flood] after a beat, drawn back down
 * into the button at [to] (root px, measured before the page sank) on critically damped springs,
 * the far edges first, so it sets down on the button without a bounce. [onProgress] (1 to 0) lets
 * the page behind rise back. No button to return to: the blue fades. Calls [onLanded] at the end.
 */
@Composable
internal fun FloodReturn(flood: Color, to: Rect?, onProgress: (Float) -> Unit, onLanded: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val edges = remember { List(4) { Animatable(1f) } }
    VeilRing()
    val fade = remember { Animatable(1f) }
    val landed by rememberUpdatedState(onLanded)
    val progress by rememberUpdatedState(onProgress)
    var page by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(page != Size.Zero) {
        if (page == Size.Zero) return@LaunchedEffect
        progress(1f)
        // A beat of plain blue first, so the change of screen does not read as a cut.
        delay(Motion.ms(220).toLong())
        if (to == null || Motion.scale == 0f) {
            fade.animateTo(0f, Motion.tw(240, easing = FastOutSlowInEasing))
        } else {
            val travel = listOf(to.left, to.top, page.width - to.right, page.height - to.bottom).map { it.coerceAtLeast(0f) }
            val far = travel.max().coerceAtLeast(1f)
            coroutineScope {
                edges.mapIndexed { i, edge ->
                    val lead = travel[i] / far
                    launch {
                        delay(Motion.ms((180 * (1f - lead)).toInt()).toLong())
                        edge.animateTo(0f, spring(dampingRatio = 1f, stiffness = lerp(110f, 190f, lead))) {
                            progress(edges.sumOf { it.value.coerceIn(0f, 1f).toDouble() }.toFloat() / 4f)
                        }
                    }
                }.forEach { it.join() }
            }
        }
        progress(0f)
        landed()
    }
    Canvas(Modifier.fillMaxSize().onSizeChanged { page = Size(it.width.toFloat(), it.height.toFloat()) }.pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
        if (to == null) drawRect(flood, alpha = fade.value)
        else drawFlood(to, edges.map { it.value }, colors.primary to pal.primary2, flood, 12.dp.toPx())
    }
}

/**
 * The session's first frames: the page in [flood], drawing in to a circle over [ball] (centre and
 * radius in root px, once the throbber is laid out) that lands the size of it. Calls [onLanded] then.
 */
@Composable
internal fun FloodGather(flood: Color, ball: Pair<Offset, Float>?, onLanded: () -> Unit) {
    val gather = remember { Animatable(0f) }
    VeilRing()
    var started by remember { mutableStateOf(false) }
    val landed by rememberUpdatedState(onLanded)
    LaunchedEffect(ball != null) {
        if (ball == null || started) return@LaunchedEffect
        started = true
        // A beat of plain blue first, so the change of screen does not read as a cut.
        delay(Motion.ms(90).toLong())
        gather.animateTo(1f, Motion.tw(640, easing = Gather))
        landed()
    }
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
        val b = ball
        if (b == null) {
            drawRect(flood)
            return@Canvas
        }
        val (c, r) = b
        val far = maxOf(hypot(c.x, c.y), hypot(size.width - c.x, c.y), hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y))
        drawCircle(flood, radius = lerp(far, r, gather.value), center = c)
    }
}

/** Where the loading screen's throbber ball sits now (centre and radius, root px), for a flood leaving from it. */
internal object ThrobberSpot {
    var ball: Pair<Offset, Float>? = null
}

private val Spread = CubicBezierEasing(0.7f, 0f, 0.4f, 1f)

/**
 * Leaving a session: [flood] spreading out of [ball] (centre and radius in root px; the middle of
 * the screen when there is none) until it covers the screen, the way FloodGather drew it in. Calls
 * [onCovered] then, for the front end to open on the same blue. Swallows touches while it runs.
 */
@Composable
internal fun FloodSpread(flood: Color, ball: Pair<Offset, Float>?, onCovered: () -> Unit) {
    VeilRing()
    val grow = remember { Animatable(0f) }
    val covered by rememberUpdatedState(onCovered)
    LaunchedEffect(Unit) {
        grow.animateTo(1f, Motion.tw(520, easing = Spread))
        covered()
    }
    Canvas(Modifier.fillMaxSize().pointerInput(Unit) { awaitEachGesture { while (true) awaitPointerEvent().changes.forEach { it.consume() } } }) {
        val (c, r) = ball ?: (Offset(size.width / 2f, size.height / 2f) to 0f)
        val far = maxOf(hypot(c.x, c.y), hypot(size.width - c.x, c.y), hypot(c.x, size.height - c.y), hypot(size.width - c.x, size.height - c.y))
        drawCircle(flood, radius = lerp(r, far, grow.value), center = c)
    }
}
