package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.drawscope.withTransform
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
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.util.lerp
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin


internal class Origin(val bounds: Rect, val corner: Float, val owner: String? = null, val art: Any? = null) {
    fun translate(by: Offset) = Origin(bounds.translate(by), corner, owner, art)
    fun ownedBy(key: String) = Origin(bounds, corner, key, art)
}

internal enum class PageExit {
    Drain,
    Hop,
    Sink,
}

internal enum class PageLeave { Ordinary, Link }

internal fun pageExit(owner: String?, incoming: String, leave: PageLeave): PageExit = when {
    owner != null && incoming == owner -> PageExit.Drain
    leave == PageLeave.Link -> PageExit.Hop
    else -> PageExit.Sink
}

internal object PageOrigin {
    private var origin: Origin? = null
    private var at = 0L

    fun mark(o: Origin) {
        origin = o
        at = SystemClock.uptimeMillis()
    }

    fun take(): Origin? = origin.takeIf { SystemClock.uptimeMillis() - at < 1_000 }.also { origin = null }
}

internal const val PAGE_RETURN_MS = 900

@Composable
internal fun PageFlood(
    from: Origin,
    leaving: Boolean,
    art: Painter? = null,
    sinks: Boolean = false,
    onto: (() -> Rect?)? = null,
    ontoCorner: androidx.compose.ui.unit.Dp = 0.dp,
    ontoLook: HeroLook? = null,
    ontoArt: Painter? = null,
    content: @Composable () -> Unit,
) {
    val pal = LocalPalette.current
    val glide = LocalFocusGlide.current
    val edges = remember { List(4) { Animatable(0f) } }
    val shown = remember { Animatable(0f) }
    val tile = remember { Animatable(1f) }
    var size by remember { mutableStateOf(Size.Zero) }
    var at by remember { mutableStateOf(Offset.Zero) }
    var target by remember { mutableStateOf<Rect?>(null) }
    val density = androidx.compose.ui.platform.LocalDensity.current
    LaunchedEffect(size != Size.Zero, leaving, sinks) {
        if (sinks) {
            tile.snapTo(0f)
            shown.snapTo(1f)
            glide?.hidden = false
            return@LaunchedEffect
        }
        if (size == Size.Zero) return@LaunchedEffect
        glide?.hidden = true
        target = onto?.let { get ->
            kotlinx.coroutines.withTimeoutOrNull(300) { snapshotFlow { get() }.first { it != null } }
        }?.translate(-at)?.takeIf { it.height > 1f && it.overlaps(Rect(Offset.Zero, size)) }
        if (target != null) {
            // A tile with no picture yet would cover the card with a blank box, so wait briefly for the art.
            if (!leaving) kotlinx.coroutines.withTimeoutOrNull(250) {
                snapshotFlow { art.ready() && (ontoArt == null || ontoArt.ready()) }.first { it }
            }
            coroutineScope {
                if (!leaving) {
                    val runs = edges.map { launch { it.animateTo(1f, Motion.sp(0.85f, 320f)) } }
                    snapshotFlow { edges.all { it.value >= 0.985f } }.first { it }
                    launch { shown.animateTo(1f, Motion.tw(220)) }
                    launch { delay(Motion.ms(120).toLong()); tile.animateTo(0f, Motion.tw(200)) }
                    runs.forEach { it.join() }
                } else {
                    launch { shown.animateTo(0f, Motion.tw(160)) }
                    tile.animateTo(1f, Motion.tw(120))
                    edges.map { launch { it.animateTo(0f, Motion.sp(0.9f, 320f)) } }.forEach { it.join() }
                    tile.animateTo(0f, Motion.tw(120))
                }
            }
            glide?.hidden = false
            return@LaunchedEffect
        }
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
    Box(
        Modifier.fillMaxSize().clipToBounds().onSizeChanged { size = Size(it.width.toFloat(), it.height.toFloat()) }
            .onGloballyPositioned { at = it.positionInRoot() },
    ) {
        val rise = if (onto != null) 0f else 1f
        Box(Modifier.fillMaxSize().graphicsLayer { alpha = shown.value; translationY = (1f - shown.value) * 14.dp.toPx() * rise }) { content() }
        val onCorner = with(density) { ontoCorner.toPx() }
        Canvas(Modifier.fillMaxSize().graphicsLayer { compositingStrategy = androidx.compose.ui.graphics.CompositingStrategy.Offscreen }) {
            val live = target?.let { landed -> onto?.invoke()?.translate(-at)?.takeIf { it.height > 1f } ?: landed }
            if (tile.value > 0.01f && (live == null || art.ready())) drawTile(from, edges.map { it.value }, pal.surface, pal.line2, tile.value, art, live, onCorner, ontoLook, ontoArt)
        }
    }
}

private fun DrawScope.drawTile(
    from: Origin, v: List<Float>, fill: Color, line: Color, alpha: Float, art: Painter? = null,
    to: Rect? = null, toCorner: Float = 0f, look: HeroLook? = null, toArt: Painter? = null,
) {
    val (l, t, r, b) = v
    val mean = v.sumOf { it.coerceIn(0f, 1f).toDouble() }.toFloat() / 4f
    val f = from.bounds
    val left: Float; val top: Float; val right: Float; val bottom: Float
    if (to != null) {
        left = lerp(f.left, to.left, l); top = lerp(f.top, to.top, t)
        right = lerp(f.right, to.right, r); bottom = lerp(f.bottom, to.bottom, b)
    } else {
        val k = 1f - 0.05f * mean
        val cx = size.width / 2f
        val cy = size.height / 2f
        left = lerp(cx + (f.left - cx) * k, 0f, l)
        top = lerp(cy + (f.top - cy) * k, 0f, t)
        right = lerp(cx + (f.right - cx) * k, size.width, r)
        bottom = lerp(cy + (f.bottom - cy) * k, size.height, b)
    }
    val w = (right - left).coerceAtLeast(0f)
    val h = (bottom - top).coerceAtLeast(0f)
    val corner = if (to != null) lerp(from.corner, toCorner, mean).coerceAtMost(minOf(w, h) / 2f)
    else lerp(from.corner * (1f - mean), minOf(w, h) * 0.42f, sin(PI * mean).toFloat().coerceAtLeast(0f))
    val pic = art?.intrinsicSize?.takeIf { it.isSpecified && it.width > 0f && it.height > 0f }
    if (to != null && art != null && pic != null && w > 0f && h > 0f) {
        drawHeroTile(art, toArt ?: art, left, top, w, h, corner, mean, alpha, fill, look, StoresMotion.heroDrift() * mean, StoresMotion.heroFadeTop)
        return
    }
    drawRoundRect(fill, Offset(left, top), Size(w, h), CornerRadius(corner), alpha = alpha)
    if (art != null && pic != null && w > 0f && h > 0f) {
        val k = maxOf(w / pic.width, h / pic.height)
        val dw = pic.width * k
        val dh = pic.height * k
        clipPath(Path().apply { addRoundRect(RoundRect(left, top, left + w, top + h, CornerRadius(corner))) }) {
            translate(left + (w - dw) / 2f, top + (h - dh) / 2f) { with(art) { draw(Size(dw, dh), alpha = alpha) } }
        }
    }
    val edge = (1f - mean * 3f).coerceIn(0f, 1f)
    if (edge > 0f) drawRoundRect(line, Offset(left, top), Size(w, h), CornerRadius(corner), alpha = alpha * edge, style = Stroke(1.dp.toPx()))
}

// Morphs the card's fitted art into the detail hero as GameBackdrop's ArtLayer draws it: cropped toward the upper right,
// dimmed, faded in from the left and out at the bottom, so the hand-off to the real hero has nothing to jump.
private fun DrawScope.drawHeroTile(
    card: Painter, hero: Painter, left: Float, top: Float, w: Float, h: Float, corner: Float, t: Float, alpha: Float, fill: Color, look: HeroLook?,
    drift: Float, fadeTop: Boolean,
) {
    val shape = Path().apply { addRoundRect(RoundRect(left, top, left + w, top + h, CornerRadius(corner))) }
    drawContext.canvas.saveLayer(Rect(left, top, left + w, top + h), androidx.compose.ui.graphics.Paint())
    clipPath(shape) {
        drawRect(fill, Offset(left, top), Size(w, h), alpha = alpha * (1f - t))
        val swap = if (hero === card) 1f else if (!hero.ready()) 0f else ((t - 0.15f) / 0.6f).coerceIn(0f, 1f)
        if (hero !== card) drawCropped(card, left, top, w, h, 0f, 0f, alpha * (1f - swap))
        withTransform({
            translate(w * HeroDrift.DX * drift, h * HeroDrift.DY * drift)
            scale(HeroDrift.zoom(drift), HeroDrift.zoom(drift), Offset(left + w * HeroDrift.PX, top + h * HeroDrift.PY))
            clipRect(left, top, left + w, top + h)
        }) { drawCropped(hero, left, top, w, h, lerp(0f, 0.45f, t), lerp(0f, -0.35f, t), alpha * swap) }
        if (look != null) {
            if (look.dim < 1f) drawRect(Color.Black, Offset(left, top), Size(w, h), alpha = alpha * (1f - look.dim) * t)
            translate(left, top) { with(look) { shade(Size(w, h), t * alpha) } }
        }
        val keep = Color.Black
        val gone = Color.Black.copy(alpha = 1f - t)
        drawRect(
            androidx.compose.ui.graphics.Brush.horizontalGradient(0.08f to gone, 0.55f to keep, startX = left, endX = left + w),
            Offset(left, top), Size(w, h), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(0.55f to keep, 1f to gone, startY = top, endY = top + h),
            Offset(left, top), Size(w, h), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
        if (fadeTop && h > 0f) drawRect(
            androidx.compose.ui.graphics.Brush.verticalGradient(0f to gone, (24.dp.toPx() / h).coerceAtMost(1f) to keep, startY = top, endY = top + h),
            Offset(left, top), Size(w, h), blendMode = androidx.compose.ui.graphics.BlendMode.DstIn,
        )
    }
    drawContext.canvas.restore()
}

private fun DrawScope.drawCropped(art: Painter, left: Float, top: Float, w: Float, h: Float, bx: Float, by: Float, alpha: Float) {
    if (alpha <= 0.001f) return
    val pic = art.intrinsicSize.takeIf { it.isSpecified && it.width > 0f && it.height > 0f } ?: return
    val k = maxOf(w / pic.width, h / pic.height)
    val dw = pic.width * k
    val dh = pic.height * k
    translate(left + (w - dw) / 2f * (1f + bx), top + (h - dh) / 2f * (1f + by)) { with(art) { draw(Size(dw, dh), alpha = alpha) } }
}

private fun Painter?.ready(): Boolean = this == null || intrinsicSize.let { it.isSpecified && it.width > 0f && it.height > 0f }
