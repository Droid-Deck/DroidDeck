package com.droiddeck.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
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

// Starting a session from a blue button: the button grows until the page is its blue, and the
// loading screen opens on that blue and gathers it into the ball of the throbber.

/** Carries the flood's colour to the session, which opens on it. */
const val EXTRA_FLOOD = "com.droiddeck.launcher.FLOOD"

/** Where the last pressed blue button sat, so a session started by it can grow out of it. */
internal object LaunchOrigin {
    private var bounds: Rect? = null
    private var button: Any? = null
    private var at = 0L

    /** The button a flood is growing out of: it steps aside so only the flood shows. */
    var flooding by mutableStateOf<Any?>(null)

    fun mark(r: Rect, key: Any) {
        bounds = r
        button = key
        at = SystemClock.uptimeMillis()
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
internal fun LaunchFlood(from: Rect, onProgress: (Float) -> Unit, onCovered: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
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
        val (l, t, r, b) = edges.map { it.value }
        val mean = edges.sumOf { it.value.coerceIn(0f, 1f).toDouble() }.toFloat() / 4f
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
        val blob = kotlin.math.sin(Math.PI * mean).toFloat().coerceAtLeast(0f)
        val corner = lerp(12.dp.toPx() * (1f - mean), minOf(w, h) * 0.42f, blob)
        // The button's gradient gives way to the ball's flat blue in the first half.
        val mix = (mean * 2f).coerceAtMost(1f)
        drawRoundRect(
            Brush.linearGradient(
                listOf(lerp(colors.primary, pal.signal, mix), lerp(pal.primary2, pal.signal, mix)),
                start = Offset(left, top), end = Offset(right, bottom),
            ),
            topLeft = Offset(left, top), size = Size(w, h), cornerRadius = CornerRadius(corner),
        )
    }
}

/**
 * The session's first frames: the page in [flood], drawing in to a circle over [ball] (centre and
 * radius in root px, once the throbber is laid out) that lands the size of it. Calls [onLanded] then.
 */
@Composable
internal fun FloodGather(flood: Color, ball: Pair<Offset, Float>?, onLanded: () -> Unit) {
    val gather = remember { Animatable(0f) }
    var started by remember { mutableStateOf(false) }
    val landed by rememberUpdatedState(onLanded)
    LaunchedEffect(ball != null) {
        if (ball == null || started) return@LaunchedEffect
        started = true
        // A beat of plain blue first, so the change of screen does not read as a cut.
        delay(Motion.ms(90).toLong())
        gather.animateTo(1f, tween(Motion.ms(640).coerceAtLeast(1), easing = Gather))
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
