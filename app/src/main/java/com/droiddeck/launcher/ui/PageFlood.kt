package com.droiddeck.launcher.ui

import android.os.SystemClock
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.spring
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

// A page opened from a control (the cog beside Play) grows out of it as a session grows out of
// Play (LaunchFlood): the control stretches out over the pane on loose springs, the far edges
// first, and the page rises into it once it is covered. Back draws it down into the control again.

/** Where a page opened from: the control's bounds and corner radius, in px. */
internal class Origin(val bounds: Rect, val corner: Float) {
    fun translate(by: Offset) = Origin(bounds.translate(by), corner)
}

internal object PageOrigin {
    private var origin: Origin? = null
    private var at = 0L

    /** The control a page is about to open from, in root coordinates. */
    fun mark(o: Origin) {
        origin = o
        at = SystemClock.uptimeMillis()
    }

    /** The control just pressed, once: stale after a second, so a page opened another way never floods. */
    fun take(): Origin? = origin.takeIf { SystemClock.uptimeMillis() - at < 1_000 }.also { origin = null }
}

/** How long a leaving page is kept for its flood to draw back into the control. */
internal const val PAGE_RETURN_MS = 900

/**
 * [content], a page that opened from the control at [from] (in this element's coordinates): the
 * control's tile stretching over it until covered, then the page rising in as the tile fades.
 * Once [leaving], the reverse: the tile comes back over the page and draws down into the control.
 * The focus ring stays out of it until it is done.
 */
@Composable
internal fun PageFlood(
    from: Origin,
    leaving: Boolean,
    /** What the tile shows instead of the control's fill: a game card's art, cropped to the tile as it grows. */
    art: Painter? = null,
    content: @Composable () -> Unit,
) {
    val pal = LocalPalette.current
    val glide = LocalFocusGlide.current
    // Left, top, right, bottom: 0 on the control, 1 on the pane's edge.
    val edges = remember { List(4) { Animatable(0f) } }
    val shown = remember { Animatable(0f) }
    val tile = remember { Animatable(1f) }
    var size by remember { mutableStateOf(Size.Zero) }
    LaunchedEffect(size != Size.Zero, leaving) {
        if (size == Size.Zero) return@LaunchedEffect
        glide?.hidden = true
        val b = from.bounds
        val travel = listOf(b.left, b.top, size.width - b.right, size.height - b.bottom).map { it.coerceAtLeast(0f) }
        val far = travel.max().coerceAtLeast(1f)
        coroutineScope {
            if (!leaving) {
                val runs = edges.mapIndexed { i, edge ->
                    val lead = travel[i] / far
                    launch {
                        delay(Motion.ms((150 * (1f - lead)).toInt()).toLong())
                        edge.animateTo(1f, Motion.sp(0.5f, lerp(150f, 260f, lead)))
                    }
                }
                snapshotFlow { edges.all { it.value >= 0.995f } }.first { it }
                launch { tile.animateTo(0f, Motion.tw(360, 60)) }
                shown.animateTo(1f, Motion.tw(320))
                runs.forEach { it.join() }
            } else {
                launch { shown.animateTo(0f, Motion.tw(140)) }
                tile.animateTo(1f, Motion.tw(140))
                edges.mapIndexed { i, edge ->
                    val lead = travel[i] / far
                    launch {
                        delay(Motion.ms((120 * (1f - lead)).toInt()).toLong())
                        edge.animateTo(0f, if (Motion.scale == 0f) Motion.sp() else spring(dampingRatio = 1f, stiffness = lerp(170f, 260f, lead)))
                    }
                }.forEach { it.join() }
                tile.animateTo(0f, Motion.tw(120))
            }
        }
        glide?.hidden = false
    }
    DisposableEffect(Unit) { onDispose { glide?.hidden = false } }
    Box(Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }) {
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 14.dp.toPx() }) { content() }
        Canvas(Modifier.fillMaxSize()) {
            if (tile.value > 0.01f) drawTile(from, edges.map { it.value }, pal.surface, pal.line2, tile.value, art)
        }
    }
}

/**
 * The control's tile with its edges [v] of the way to the pane's (left, top, right, bottom):
 * sinking with the page behind, blobby in flight, square once it fills the pane.
 */
private fun DrawScope.drawTile(from: Origin, v: List<Float>, fill: Color, line: Color, alpha: Float, art: Painter? = null) {
    val (l, t, r, b) = v
    val mean = v.sumOf { it.coerceIn(0f, 1f).toDouble() }.toFloat() / 4f
    // The control sinks with the page behind it (scaled to 0.95 about the pane's centre).
    val k = 1f - 0.05f * mean
    val cx = size.width / 2f
    val cy = size.height / 2f
    val f = from.bounds
    val left = lerp(cx + (f.left - cx) * k, 0f, l)
    val top = lerp(cy + (f.top - cy) * k, 0f, t)
    val right = lerp(cx + (f.right - cx) * k, size.width, r)
    val bottom = lerp(cy + (f.bottom - cy) * k, size.height, b)
    val w = (right - left).coerceAtLeast(0f)
    val h = (bottom - top).coerceAtLeast(0f)
    val blob = sin(PI * mean).toFloat().coerceAtLeast(0f)
    val corner = lerp(from.corner * (1f - mean), minOf(w, h) * 0.42f, blob)
    drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(corner), alpha = alpha)
    val pic = art?.intrinsicSize?.takeIf { it.isSpecified && it.width > 0f && it.height > 0f }
    if (art != null && pic != null && w > 0f && h > 0f) {
        // The art covers the tile at every size (a centre crop), so a portrait card opens into the
        // wide pane without stretching.
        val k = maxOf(w / pic.width, h / pic.height)
        val dw = pic.width * k
        val dh = pic.height * k
        clipPath(Path().apply { addRoundRect(RoundRect(left, top, left + w, top + h, CornerRadius(corner))) }) {
            translate(left + (w - dw) / 2f, top + (h - dh) / 2f) { with(art) { draw(Size(dw, dh), alpha = alpha) } }
        }
    }
    // The control's outline, kept while it is still the size of one.
    val edge = (1f - mean * 3f).coerceIn(0f, 1f)
    if (edge > 0f) drawRoundRect(line, Offset(left, top), Size(w, h), CornerRadius(corner), alpha = alpha * edge, style = Stroke(1.dp.toPx()))
}
