package com.droiddeck.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sin

// The moves the stores made first, for any section: one selection shape that glides along a row
// (or down a column) of choices, and a pressed control that squashes into a dot and flies home.

/** Which way a [ChipGlide]'s choices run. */
internal enum class GlideAxis { Horizontal, Vertical }

/**
 * The selection under a row of chips: [at] (in the row) of chip [index], in [tint]. A move to the
 * next chip stretches it - the leading edge on the focus ring's lead spring, the trailing one a
 * beat later on its trail spring - and a hop past chips stretches the same way, across them.
 * Draw it over the chips: under them, their own rest fill hid it in flight. [axis] runs it down a column instead (the rail, a stack of layers). [corner] is the
 * chips' own corner; null draws a capsule. [fill] and [outline] are the resting alphas. Not
 * [visible], it fades away, and comes back on whichever chip it is on by then.
 */
@Composable
internal fun BoxScope.ChipGlide(
    index: Int,
    at: Rect?,
    tint: State<Color>,
    axis: GlideAxis = GlideAxis.Horizontal,
    corner: Dp? = null,
    fill: Float = 0.16f,
    outline: Float = 0.7f,
    visible: Boolean = true,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    // The two edges along the axis (left and right, or top and bottom), and the two across it.
    val start = remember { Animatable(Float.NaN) }
    val end = remember { Animatable(0f) }
    val pinch = remember { Animatable(0f) }
    // With a pad the focus ring fills what it holds, and is the only fill: a selection of the
    // row's own, sliding on its own beat, trailed the ring and sat on items the pad had left.
    val pad = androidx.compose.ui.platform.LocalInputModeManager.current.inputMode == androidx.compose.ui.input.InputMode.Keyboard
    @Suppress("NAME_SHADOWING") val visible = visible && !pad
    val shown = remember { Animatable(if (visible) 1f else 0f) }
    val visibleNow = rememberUpdatedState(visible)
    LaunchedEffect(visible) { shown.animateTo(if (visible) 1f else 0f, Motion.tw(if (visible) 160 else 120)) }
    val crossStart = remember { mutableStateOf(0f) }
    val crossEnd = remember { mutableStateOf(0f) }
    val was = remember { intArrayOf(-1) }
    val vertical = axis == GlideAxis.Vertical
    LaunchedEffect(index, at, Motion.scale) {
        val b = at ?: return@LaunchedEffect
        val s = if (vertical) b.top else b.left
        val e = if (vertical) b.bottom else b.right
        crossStart.value = if (vertical) b.left else b.top
        crossEnd.value = if (vertical) b.right else b.bottom
        val from = was[0]
        was[0] = index
        if (start.value.isNaN() || from == index || from < 0 || Motion.scale == 0f) {
            start.snapTo(s); end.snapTo(e); pinch.snapTo(0f)
            if (visibleNow.value) shown.snapTo(1f)
            return@LaunchedEffect
        }
        // Away or on its way back: it goes straight to the chip, unseen.
        if (!visibleNow.value || shown.value < 0.5f) {
            start.snapTo(s); end.snapTo(e); pinch.snapTo(0f)
            return@LaunchedEffect
        }
        coroutineScope {
            run {
                // Every move stretches across, however far: a long one used to go as a drop (which
                // sat on chips already left) and then as a fade (a flash of no fill at all).
                launch { pinch.animateTo(0f, Motion.sp(0.6f, 500f)) }
                launch { shown.animateTo(1f, Motion.tw(120)) }
                val forward = index > from
                val (lead, trail) = if (forward) end to start else start to end
                val leadTo = if (forward) e else s
                val trailTo = if (forward) s else e
                launch { lead.animateTo(leadTo, Motion.sp(0.62f, 700f)) }
                launch { delay(Motion.ms(40).toLong()); trail.animateTo(trailTo, Motion.sp(0.78f, 360f)) }
            }
        }
    }
    Canvas(Modifier.matchParentSize()) {
        if (start.value.isNaN() || crossEnd.value <= crossStart.value) return@Canvas
        val p = pinch.value
        val shape = glideShape(start.value, end.value, crossStart.value, crossEnd.value, p, with(density) { 6.dp.toPx() }, vertical)
        val at0 = shape.topLeft
        val size = shape.size
        val round = minOf(size.width, size.height) / 2f
        val r = CornerRadius(corner?.let { lerp(minOf(with(density) { it.toPx() }, round), round, p) } ?: round)
        // A drop in flight is solid; on a chip it is the chip's tinted fill and outline.
        val a = shown.value
        drawRoundRect(tint.value.copy(alpha = lerp(fill, 0.9f, p) * a), at0, size, r)
        if (outline > 0f) {
            // Inside the chip's edge: a stroke on it would lose its outer half to a scrolling row's clip.
            val w = with(density) { 1.dp.toPx() }
            val inset = w / 2f
            drawRoundRect(
                tint.value.copy(alpha = outline * (1f - p) * a), at0 + Offset(inset, inset), Size(size.width - w, size.height - w),
                CornerRadius((r.x - inset).coerceAtLeast(0f)), style = Stroke(w),
            )
        }
    }
}

/**
 * The chosen one of a row of chips (or a column, [vertical]): a short bar of [color] along its foot
 * (or its start edge). Not a fill or an outline, so it never reads as focus; with a pad the ring's
 * own infill is the only fill. Goes inside the chip's clip.
 */
@Composable
internal fun Modifier.shownBar(on: Boolean, color: Color, vertical: Boolean = false): Modifier {
    val shown = androidx.compose.animation.core.animateFloatAsState(if (on) 1f else 0f, Motion.tw(if (on) 140 else 100), label = "shownBar").value
    return drawWithContent {
        drawContent()
        if (shown <= 0.01f) return@drawWithContent
        val t = 3.dp.toPx()
        if (vertical) {
            val l = size.height * 0.5f
            drawRect(color, Offset(0f, (size.height - l) / 2f), Size(t, l), alpha = shown)
        } else {
            val w = size.width * 0.36f
            drawRect(color, Offset((size.width - w) / 2f, size.height - t), Size(w, t), alpha = shown)
        }
    }
}

/**
 * Where a glide's shape is: its edges along the axis at [start] and [end] (either order), across
 * it from [crossStart] to [crossEnd], pinched [pinch] of the way (0 to 1) into a drop [drop] each
 * side of its centre. [vertical] runs the axis down instead of across.
 */
internal fun glideShape(start: Float, end: Float, crossStart: Float, crossEnd: Float, pinch: Float, drop: Float, vertical: Boolean): Rect {
    val a0 = minOf(start, end)
    val a1 = maxOf(start, end)
    val ca = (a0 + a1) / 2f
    val cc = (crossStart + crossEnd) / 2f
    val s0 = lerp(a0, ca - drop, pinch)
    val s1 = lerp(a1, ca + drop, pinch)
    val c0 = lerp(crossStart, cc - drop, pinch)
    val c1 = lerp(crossEnd, cc + drop, pinch)
    return if (vertical) Rect(c0, s0, c1, s1) else Rect(s0, c0, s1, c1)
}

/**
 * A dot on its way somewhere: from the control at [from] (root px) to the point [to] gives (root
 * px, asked each frame, so a target that moves is followed). [color] null is the palette's signal
 * blue. [onHalf] runs as it passes the top of its arc, [onLand] once it lands.
 */
internal class Flight(
    val from: Rect,
    val to: Density.() -> Offset?,
    val color: Color? = null,
    val onHalf: () -> Unit = {},
    val onLand: () -> Unit = {},
) {
    val id = SystemClock.uptimeMillis() * 1000 + (next++ % 1000)

    private companion object { var next = 0L }
}

/** Dots flying anywhere outside the Stores page (which keeps its own): a hop, a swap, a toggle. */
internal object Flights {
    val all = mutableStateListOf<Flight>()

    /** Sends [f], or with animations off just runs its ends. */
    fun fly(f: Flight) {
        if (Motion.scale == 0f) { f.onHalf(); f.onLand(); return }
        all += f
    }
}

/** [Flights] drawn over everything, in the space of an element at [origin] (root px). */
@Composable
internal fun FlightsLayer(origin: Offset) {
    for (f in Flights.all.toList()) key(f.id) { FlightDot(f, origin) { Flights.all.remove(f) } }
}

/**
 * One dot: the control squashes into a 14dp dot (width first, a little bloop), which then flies
 * on an arc to its target, stretched along its path like the focus ring's drop.
 */
@Composable
internal fun FlightDot(f: Flight, origin: Offset, onDone: () -> Unit) {
    val pal = LocalPalette.current
    val color = f.color ?: pal.signal
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        t.animateTo(1f, Motion.tw(150, easing = FastOutSlowInEasing))
        coroutineScope {
            launch { delay(Motion.ms(150).toLong()); f.onHalf() }
            t.animateTo(2f, Motion.tw(300, easing = FastOutSlowInEasing))
        }
        f.onLand()
        onDone()
    }
    Canvas(Modifier.fillMaxSize()) {
        val r = 7.dp.toPx()
        val from = f.from.translate(-origin)
        val a = from.center
        val b = (f.to(this) ?: return@Canvas) - origin
        val v = t.value
        if (v < 1f) {
            // Squash: the width goes first, then the height, into the dot; a bloop at the end.
            val w = lerp(from.width, 2 * r, (v * 1.4f).coerceAtMost(1f))
            val h = lerp(from.height, 2 * r, ((v - 0.25f) / 0.75f).coerceIn(0f, 1f))
            val k = 1f - 0.15f * sin(PI * v).toFloat()
            drawRoundRect(color, Offset(a.x - w * k / 2, a.y - h * k / 2), Size(w * k, h * k), CornerRadius(minOf(w, h) * k / 2))
        } else {
            val p = v - 1f
            val c = Offset((a.x + b.x) / 2, minOf(a.y, b.y) - 80.dp.toPx())
            val q = 1f - p
            val pos = Offset(q * q * a.x + 2 * q * p * c.x + p * p * b.x, q * q * a.y + 2 * q * p * c.y + p * p * b.y)
            val d = Offset(2 * q * (c.x - a.x) + 2 * p * (b.x - c.x), 2 * q * (c.y - a.y) + 2 * p * (b.y - c.y))
            val stretch = sin(PI * p).toFloat()
            val angle = atan2(d.y, d.x) * 180f / PI.toFloat()
            rotate(angle, pos) {
                val w = 2 * r * (1f + 0.5f * stretch)
                val h = 2 * r * (1f - 0.25f * stretch)
                drawOval(color, Offset(pos.x - w / 2, pos.y - h / 2), Size(w, h))
            }
        }
    }
}

/**
 * A row that scrolls sideways fades out at an edge it has more past, instead of slicing a pill or
 * a card through: the content's own alpha, so whatever is behind the row shows through.
 */
internal fun Modifier.edgeFade(scroll: androidx.compose.foundation.ScrollState, width: Dp = 28.dp): Modifier =
    this.graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }
        .drawWithContent {
            drawContent()
            val w = width.toPx().coerceAtMost(size.width / 3f)
            if (scroll.value > 0) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Transparent, 1f to Color.Black, startX = 0f, endX = w),
                size = Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
            if (scroll.value < scroll.maxValue) drawRect(
                androidx.compose.ui.graphics.Brush.horizontalGradient(0f to Color.Black, 1f to Color.Transparent, startX = size.width - w, endX = size.width),
                topLeft = Offset(size.width - w, 0f), size = Size(w, size.height), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
            )
        }
