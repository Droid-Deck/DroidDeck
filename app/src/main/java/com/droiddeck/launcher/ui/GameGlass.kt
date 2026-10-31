package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.geometry.isSpecified
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ColorFilter
import androidx.compose.ui.graphics.ColorMatrix
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Paint
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import coil.imageLoader
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import coil.size.Precision
import kotlin.math.max

/** Buttons in a capsule render as pills; elsewhere they keep their rounded-rectangle shape. */
internal val LocalPillButtons = compositionLocalOf { false }

/**
 * The page's blurred art, shared so frosted surfaces can draw the part of it behind them.
 * Coordinates are read when drawing, so a surface and the backdrop always agree on where they are.
 */
internal class GlassBackdrop {
    var painter: Painter? = null
    var coords: LayoutCoordinates? = null
    var light: Boolean = false
    var ready by mutableStateOf(false)

    fun offsetOf(surface: LayoutCoordinates?): Offset? {
        val base = coords?.takeIf { it.isAttached } ?: return null
        val at = surface?.takeIf { it.isAttached } ?: return null
        return base.localPositionOf(at, Offset.Zero)
    }

    fun paint(scope: DrawScope, size: Size) {
        val art = painter ?: return
        scope.drawCover(art, size, if (light) LightAmbient else DarkAmbient)
    }

    /** Drawn inside a layer, so it fades what was painted before it toward the page's end. */
    fun fadeBottom(scope: DrawScope, size: Size) {
        val fade = minOf(size.height * 0.5f, with(scope) { 240.dp.toPx() })
        if (size.height <= 0f) return
        scope.drawRect(Brush.verticalGradient((1f - fade / size.height) to Color.Black, 1f to Color.Transparent), size = size, blendMode = BlendMode.DstIn)
    }
}

internal val LocalGlass = compositionLocalOf<GlassBackdrop?> { null }

private val DarkAmbient = ColorFilter.colorMatrix(
    ColorMatrix().apply {
        setToSaturation(1.3f)
        timesAssign(ColorMatrix(floatArrayOf(0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 0.55f, 0f, 0f, 0f, 0f, 0f, 1f, 0f)))
    },
)
private val LightAmbient = ColorFilter.colorMatrix(ColorMatrix().apply { setToSaturation(1.1f) })

private fun DrawScope.drawCover(painter: Painter, into: Size, filter: ColorFilter) {
    val intrinsic = painter.intrinsicSize
    if (!intrinsic.isSpecified) return
    val iw = intrinsic.width
    val ih = intrinsic.height
    if (iw <= 0f || ih <= 0f || into.width <= 0f || into.height <= 0f) return
    val scale = max(into.width / iw, into.height / ih)
    val w = iw * scale
    val h = ih * scale
    clipRect(0f, 0f, into.width, into.height) {
        translate((into.width - w) * AmbientBiasX, (into.height - h) * AmbientBiasY) {
            with(painter) { draw(Size(w, h), colorFilter = filter) }
        }
    }
}

private const val AmbientBiasX = 0.6f
private const val AmbientBiasY = 0.05f

private fun ambientRequest(ctx: android.content.Context, model: Any?): ImageRequest? =
    model?.let { ImageRequest.Builder(ctx).data(it).size(AmbientPixels, AmbientPixels).precision(Precision.EXACT).memoryCacheKey("wash:$it").build() }

private const val AmbientPixels = 6

/** How washed-out the art is: average brightness less its saturation, from a tiny decode, so pale art is dimmed while dark and vivid art are left alone. */
@Composable
private fun rememberArtBrightness(model: Any?): Float {
    val ctx = LocalContext.current
    val data = (model as? ImageRequest)?.data ?: model
    val brightness by produceState(0.3f, data) {
        if (data == null) return@produceState
        val request = ImageRequest.Builder(ctx).data(data).size(SamplePixels, SamplePixels).precision(Precision.EXACT)
            .allowHardware(false).memoryCacheKey("lum:$data").build()
        val bitmap = (ctx.imageLoader.execute(request).drawable as? android.graphics.drawable.BitmapDrawable)?.bitmap ?: return@produceState
        var light = 0.0
        var saturation = 0.0
        for (y in 0 until bitmap.height) for (x in 0 until bitmap.width) {
            val pixel = Color(bitmap.getPixel(x, y))
            val top = maxOf(pixel.red, pixel.green, pixel.blue)
            light += pixel.luminance()
            if (top > 0f) saturation += (top - minOf(pixel.red, pixel.green, pixel.blue)) / top
        }
        val count = bitmap.width * bitmap.height
        value = ((light - SaturationWeight * saturation) / count).toFloat().coerceAtLeast(0f)
    }
    return brightness
}

private const val SamplePixels = 8
private const val SaturationWeight = 0.6

/**
 * A page whose game art fills the background: a blurred wash behind everything and the sharp art
 * across the top, fading into the page. Surfaces inside draw frosted glass over the wash.
 */
@Composable
internal fun GameBackdrop(
    art: Any?, ambient: Any?, fallbackHue: Float,
    fadeTop: Boolean,
    modifier: Modifier = Modifier,
    onArtPlaced: (androidx.compose.ui.geometry.Rect?) -> Unit = {},
    content: @Composable () -> Unit,
) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val glass = remember { GlassBackdrop() }
    glass.light = colors.background.luminance() > 0.5f
    val wash = rememberAsyncImagePainter(remember(ambient) { ambientRequest(ctx, ambient ?: art) })
    glass.painter = if (ambient != null || art != null) wash else null
    val edge = 24.dp
    val artMin = with(LocalDensity.current) { ArtMin.toPx() }
    Box(
        modifier.fillMaxWidth().onGloballyPositioned {
            glass.coords = it
            if (!glass.ready) glass.ready = true
            if (art == null) {
                val at = it.boundsInRoot()
                onArtPlaced(Rect(at.left, at.top, at.right, at.top + artMin))
            }
        },
    ) {
        DisposableEffect(art == null) { onDispose { if (art == null) onArtPlaced(null) } }
        Box(
            Modifier.matchParentSize()
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    if (glass.painter == null) drawRect(artBrush(fallbackHue), alpha = 0.55f) else glass.paint(this, size)
                    drawRect(colors.background.copy(alpha = if (glass.light) 0.7f else 0.58f))
                    if (fadeTop) drawRect(Brush.verticalGradient(0f to Color.Transparent, (edge.toPx() / size.height).coerceAtMost(0.5f) to Color.Black), blendMode = BlendMode.DstIn)
                    glass.fadeBottom(this, size)
                },
        )
        if (art != null) ArtLayer(art, ambient ?: art, fadeTop, onArtPlaced)
        CompositionLocalProvider(LocalGlass provides glass) { content() }
    }
}

@Composable
private fun ArtLayer(model: Any?, sample: Any?, fadeTop: Boolean, onPlaced: (androidx.compose.ui.geometry.Rect?) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val light = colors.background.luminance() > 0.5f
    val pale = ((rememberArtBrightness(sample) - 0.28f) / 0.5f).coerceIn(0f, 1f)
    val dim = if (light) 1f else 1f - 0.4f * pale
    val artFilter = remember(dim) { ColorFilter.colorMatrix(ColorMatrix(floatArrayOf(dim, 0f, 0f, 0f, 0f, 0f, dim, 0f, 0f, 0f, 0f, 0f, dim, 0f, 0f, 0f, 0f, 0f, 1f, 0f))) }
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val height = (maxWidth * 0.4f).coerceIn(ArtMin, ArtMax)
        DisposableEffect(Unit) { onDispose { onPlaced(null) } }
        Box(
            Modifier.fillMaxWidth().height(height)
                .onGloballyPositioned { onPlaced(it.boundsInRoot()) }
                .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                .drawWithContent {
                    drawContent()
                    drawRect(Brush.horizontalGradient(0.08f to Color.Transparent, 0.55f to Color.Black), blendMode = BlendMode.DstIn)
                    drawRect(Brush.verticalGradient(0.55f to Color.Black, 1f to Color.Transparent), blendMode = BlendMode.DstIn)
                    if (fadeTop) drawRect(Brush.verticalGradient(0f to Color.Transparent, (24.dp.toPx() / size.height) to Color.Black), blendMode = BlendMode.DstIn)
                },
        ) {
            AsyncImage(
                model = model, contentDescription = null, contentScale = ContentScale.Crop,
                alignment = BiasAlignment(0.45f, -0.35f),
                colorFilter = artFilter,
                modifier = Modifier.matchParentSize(),
            )
            Box(
                Modifier.matchParentSize().drawBehind {
                    drawRect(colors.background.copy(alpha = if (light) 0.1f else 0.12f + 0.3f * pale))
                    drawRect(
                        Brush.radialGradient(
                            0f to colors.background.copy(alpha = if (light) 0.5f else 0.66f),
                            0.55f to colors.background.copy(alpha = if (light) 0.2f else 0.3f),
                            1f to Color.Transparent,
                            center = Offset(size.width * 0.22f, size.height * 0.55f), radius = size.width * 0.55f,
                        ),
                    )
                },
            )
        }
    }
}

private val ArtMin = 220.dp
private val ArtMax = 340.dp

/** A frosted surface: the page's wash behind it, a dark tint, and a thin rim. Plain surface when there is no backdrop. */
@Composable
internal fun Modifier.glass(shape: Shape, lift: Float = 0f): Modifier {
    val g = LocalGlass.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    if (g == null) return clip(shape).background(colors.surface).border(1.dp, pal.line2, shape)
    val light = g.light
    val tint = colors.background.copy(alpha = if (light) 0.62f else 0.46f + lift)
    val rim = if (light) Color.Black.copy(alpha = 0.1f) else Color.White.copy(alpha = 0.12f + lift)
    val self = remember { arrayOfNulls<LayoutCoordinates>(1) }
    var placed by remember { mutableIntStateOf(0) }
    val last = remember { longArrayOf(Long.MIN_VALUE) }
    return clip(shape)
        .onGloballyPositioned { c ->
            self[0] = c
            val at = g.offsetOf(c) ?: return@onGloballyPositioned
            val key = (at.x.toLong() shl 32) xor (at.y.toLong() and 0xffffffffL)
            if (key != last[0]) { last[0] = key; placed++ }
        }
        .drawBehind {
            val base = g.coords?.size
            if (placed >= 0 && g.ready && base != null) g.offsetOf(self[0])?.let { at ->
                val wash = Size(base.width.toFloat(), base.height.toFloat())
                drawIntoCanvas { canvas ->
                    canvas.saveLayer(Rect(0f, 0f, size.width, size.height), Paint())
                    translate(-at.x, -at.y) { g.paint(this, wash); g.fadeBottom(this, wash) }
                    canvas.restore()
                }
            }
            drawRect(tint)
        }
        .border(1.dp, rim, shape)
}
