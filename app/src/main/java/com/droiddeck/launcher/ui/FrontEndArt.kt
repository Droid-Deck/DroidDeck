package com.droiddeck.launcher.ui

import android.graphics.Bitmap
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.Image
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import coil.compose.AsyncImage
import java.io.File

// Cover art: posters, the art grid and its tiles.

internal class Tile(
    val title: String, val sub: String?, val art: File?, val key: String,
    val iconRes: Int? = null, val dim: Boolean = false, val iconBitmap: Bitmap? = null,
    val showFooter: Boolean = true,
    val onClick: () -> Unit,
)

@Composable
internal fun Poster(art: File?, name: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.sp(0.6f, Spring.StiffnessLow)) }
    Box(
        modifier = modifier.padding(start = 16.dp).aspectRatio(2f / 3f)
            .graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 16.dp.toPx(); rotationZ = (1f - t.value) * 2f; scaleX = 0.94f + 0.06f * t.value; scaleY = scaleX; shadowElevation = 22.dp.toPx(); shape = Shape12; clip = false }
            .clip(Shape12).background(artBrush(hueOf(name))),
    ) {
        if (art != null) CoverImage(art, Modifier.fillMaxSize())
        else Text(name, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.align(Alignment.BottomStart).padding(8.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
internal fun ArtGrid(tiles: List<Tile>, wide: Boolean = false) {
    val square = tiles.isNotEmpty() && tiles.all { it.art == null && (it.iconRes != null || it.iconBitmap != null) }
    // Big enough to recognise a game by its art and read its name under it; icon tiles are squares.
    val minSize = if (square) 76.dp else if (wide) 120.dp else 96.dp
    val gap = 12.dp
    // Laid out whole, not lazily: the pad's focus search only finds tiles that exist, and a lazy
    // grid composes only the rows on screen, so a press towards the next row bounced back among
    // the visible tiles. A few hundred tiles lay out fine; the page's scroll follows the focused one.
    BoxWithConstraints(modifier = Modifier.fillMaxWidth()) {
        val avail = maxWidth - 8.dp
        val cols = ((avail + gap) / (minSize + gap)).toInt().coerceAtLeast(1)
        val tileWidth = (avail - gap * (cols - 1)) / cols
        Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 14.dp, start = 4.dp, end = 4.dp)) {
            for (row in tiles.chunked(cols)) {
                Row(horizontalArrangement = Arrangement.spacedBy(gap), modifier = Modifier.fillMaxWidth().padding(bottom = gap)) {
                    for (t in row) key(t.key) {
                        val src = remember { MutableInteractionSource() }
                        val hot = rememberHot(src)
                        val track = Modifier.paneItem("tile:" + t.key).then(if (t === tiles.first()) Modifier.firstTile() else Modifier)
                        Box(modifier = Modifier.width(tileWidth).zIndex(if (hot) 1f else 0f)) { GameTile(t, wide, square, src, hot, track) }
                    }
                }
            }
        }
    }
}

@Composable
private fun GameTile(t: Tile, wide: Boolean, square: Boolean, src: MutableInteractionSource, hot: Boolean, track: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else if (hot) 1.04f else 1f, Motion.sp(0.55f, Spring.StiffnessMedium), label = "tileScale")
    val lift by animateFloatAsState(if (hot) -5f else 0f, Motion.sp(0.6f), label = "tileLift")
    val elev by animateFloatAsState(if (hot) 18f else 2f, Motion.tw(300), label = "tileElev")
    val pal = LocalPalette.current
    Column(
        modifier = track
            .graphicsLayer { scaleX = scale; scaleY = scale; translationY = lift.dp.toPx(); shadowElevation = elev.dp.toPx(); shape = Shape12; clip = false; ambientShadowColor = if (hot) pal.signal else Color.Black; spotShadowColor = if (hot) pal.signal else Color.Black; transformOrigin = androidx.compose.ui.graphics.TransformOrigin(0.5f, 0.9f) }
            .clip(Shape12)
            .background(colors.surface)
            .glideBorder(hot, Shape12, pal.signal, hotWidth = 2.5.dp)
            .alpha(if (t.dim && !hot) 0.55f else 1f)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = t.onClick),
    ) {
        Box(modifier = Modifier.fillMaxWidth().shine(hot)) {
            Art(t.art, t.iconRes, t.title, Modifier.fillMaxWidth(), wide, t.iconBitmap)
        }
        if (t.showFooter) {
            Column(modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                Text(t.title, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (t.sub != null) Text(t.sub, fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Art for a portrait (2:3) tile. Box art fills it; wide art - a PS3 disc's ICON0, a game's header -
 * is shown whole over a blurred, darkened copy of itself instead of losing its sides to the crop.
 */
@Composable
private fun CoverImage(art: File, modifier: Modifier) {
    var wideArt by remember(art) { mutableStateOf(false) }
    Box(modifier) {
        if (wideArt) {
            AsyncImage(
                model = art, contentDescription = null, contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize().graphicsLayer { scaleX = 1.2f; scaleY = 1.2f }.blur(14.dp),
            )
            Spacer(Modifier.fillMaxSize().background(Color.Black.copy(alpha = 0.45f)))
        }
        AsyncImage(
            model = art, contentDescription = null,
            contentScale = if (wideArt) ContentScale.Fit else ContentScale.Crop,
            onSuccess = { state ->
                val size = state.painter.intrinsicSize
                if (size.width > size.height * 1.1f) wideArt = true
            },
            modifier = Modifier.fillMaxSize(),
        )
    }
}

@Composable
private fun Art(art: File?, iconRes: Int?, label: String, modifier: Modifier, wide: Boolean = false, iconBitmap: Bitmap? = null) {
    val colors = MaterialTheme.colorScheme
    val ratio = if (art == null && (iconRes != null || iconBitmap != null)) 1f else if (wide) 16f / 9f else 2f / 3f
    Box(modifier = modifier.aspectRatio(ratio).background(if (art == null && iconRes == null && iconBitmap == null) artBrush(hueOf(label)) else Brush.linearGradient(listOf(colors.surfaceVariant, colors.surface)))) {
        when {
            art != null -> if (wide) AsyncImage(model = art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                            else CoverImage(art, Modifier.fillMaxSize())
            iconRes != null -> Image(painterResource(iconRes), null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(if (wide) 10.dp else 8.dp))
            iconBitmap != null -> Image(bitmap = iconBitmap.asImageBitmap(), contentDescription = null, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxSize().padding(8.dp))
            else -> {
                Spacer(Modifier.fillMaxSize().background(Brush.verticalGradient(0f to Color.Transparent, 0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.55f))))
                Text(label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = Color.White.copy(alpha = 0.92f), modifier = Modifier.align(Alignment.BottomStart).padding(6.dp), maxLines = 3, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}
