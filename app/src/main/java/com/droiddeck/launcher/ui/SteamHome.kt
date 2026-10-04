package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.runtime.State
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInParent
import androidx.compose.ui.layout.onPlaced
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.droiddeck.launcher.frontend.Library

/** From this many games the Steam tab is a wall; fewer would only repeat the same covers. */
private const val WALL_MIN_GAMES = 4

/**
 * The Steam tab: the library as a slowly drifting, tilted wall of capsules (a few games instead
 * lean on their own; none, the DroidDeck mark wandering a blank wall), and over it the wordmark and the one thing to do here -
 * Play. Everything about a single game lives on the Games tab.
 */
@Composable
internal fun SteamHome(s: FrontEndState, a: FrontEndActions, modifier: Modifier) {
    // A fixed order, ties included: the wall places each game by its position, so an order that
    // came out differently from one read of the library to the next moved every capsule.
    val games = remember(s.steamGames) { s.steamGames.sortedWith(compareByDescending<Library.SteamGame> { it.lastPlayed }.thenBy { it.gameId }) }
    val narrow = LocalNarrowPane.current
    val colors = MaterialTheme.colorScheme
    // Where the words and buttons sit, for the empty library's wandering mark to keep off.
    var words by remember { mutableStateOf<Rect?>(null) }
    Box(modifier = modifier.fillMaxSize().clipToBounds()) {
        when {
            games.isEmpty() -> EmptyLibrary(avoid = words) { WallFade() }
            games.size < WALL_MIN_GAMES -> CapsuleFan(games)
            else -> {
                CapsuleWall(games)
                WallFade()
            }
        }
        Column(
            modifier = Modifier.align(Alignment.BottomStart)
                .padding(start = if (narrow) 20.dp else 44.dp, bottom = if (narrow) 20.dp else 44.dp, end = 20.dp)
                .onPlaced { words = it.boundsInParent() },
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Wordmark(if (narrow) 30.dp else 40.dp)
                // Said only when something stands between Play and Steam; a ready runtime needs no words.
                if (s.busy || !s.ready || (s.available != null && s.available != s.installed)) RuntimeChip(s)
            }
            Spacer(Modifier.height(18.dp))
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                // Enabled without a runtime: the session's loading screen installs it first.
                PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, large = true, icon = Icons.Filled.PlayArrow, onClick = a.onPlay)
                Cog(size = 54.dp, onClick = a.onSteamSettings)
            }
        }
    }
}

/** The wall fades out behind the words, and toward the bottom where the buttons sit. */
@Composable
private fun WallFade() {
    val colors = MaterialTheme.colorScheme
    Box(
        Modifier.fillMaxSize().background(
            Brush.horizontalGradient(
                0f to colors.background.copy(alpha = 0.97f),
                0.34f to colors.background.copy(alpha = 0.86f),
                0.68f to colors.background.copy(alpha = 0.25f),
                1f to colors.background.copy(alpha = 0.1f),
            ),
        ),
    )
    Box(Modifier.fillMaxSize().background(Brush.verticalGradient(0.55f to Color.Transparent, 1f to colors.background.copy(alpha = 0.9f))))
}

/**
 * Columns of capsules tilted a few degrees, alternate columns drifting up and down. Each column
 * holds its run of covers twice and moves by exactly one run, so the loop has no seam. An empty
 * library draws the same wall in blank capsules, slower.
 */
@Composable
internal fun CapsuleWall(
    games: List<Library.SteamGame>,
    driftMs: Int = if (games.isEmpty()) 75_000 else 40_000,
    modifier: Modifier = Modifier,
) {
    val capW = 112.dp
    val capH = 168.dp
    val gap = 14.dp
    val transition = rememberInfiniteTransition(label = "wall")
    val drift = transition.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(driftMs, easing = LinearEasing)),
        label = "drift",
    )
    // Animations off in the system settings: a still wall.
    val still = Motion.scale == 0f
    BoxWithConstraints(modifier.fillMaxSize()) {
        // Centred on the page and big enough that, once tilted, it still covers every corner.
        val tilt = Math.toRadians(13.0)
        val cos = kotlin.math.cos(tilt).toFloat()
        val sin = kotlin.math.sin(tilt).toFloat()
        val wallW = (maxWidth * cos + maxHeight * sin) * 1.1f
        val wallH = maxOf(maxHeight * 2.4f, (maxWidth * sin + maxHeight * cos) * 1.1f)
        val columns = ((wallW + gap) / (capW + gap)).toInt() + 1
        val perRun = ((wallH + gap) / (capH + gap)).toInt() + 1
        val run = (capH + gap) * perRun
        Row(
            horizontalArrangement = Arrangement.spacedBy(gap),
            modifier = Modifier
                .wrapContentSize(Alignment.TopStart, unbounded = true)
                .offset(x = -(wallW - maxWidth) / 2, y = -(wallH - maxHeight) / 2)
                .requiredSize(wallW, wallH)
                .graphicsLayer { rotationZ = -13f },
        ) {
            repeat(columns) { c ->
                Column(
                    verticalArrangement = Arrangement.spacedBy(gap),
                    modifier = Modifier.wrapContentHeight(Alignment.Top, unbounded = true).graphicsLayer {
                        val shift = if (still) 0f else run.toPx() * drift.value
                        translationY = if (c % 2 == 0) -shift else shift - run.toPx()
                    },
                ) {
                    repeat(perRun * 2) { i ->
                        val g = if (games.isEmpty()) null else games[(c * 5 + (i % perRun) * 3) % games.size]
                        Capsule(g, RoundedCornerShape(10.dp), Modifier.size(capW, capH))
                    }
                }
            }
        }
    }
}

/** One to three games, each a large capsule leaning on the others and floating a little. */
@Composable
private fun CapsuleFan(games: List<Library.SteamGame>) {
    val pal = LocalPalette.current
    val transition = rememberInfiniteTransition(label = "fan")
    val floats = listOf(9_000, 11_000, 13_000).map { ms ->
        transition.animateFloat(0f, 1f, infiniteRepeatable(tween(ms, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "float")
    }
    val still = Motion.scale == 0f
    // Where each sits (share of the page), its height (share of the page's) and its lean; front first.
    val slots = listOf(
        FanSlot(0.54f, 0.14f, 0.62f, -9f),
        FanSlot(0.70f, 0.24f, 0.56f, 5f),
        FanSlot(0.40f, 0.30f, 0.50f, -17f),
    )
    BoxWithConstraints(
        Modifier.fillMaxSize().drawBehind {
            drawRect(
                Brush.radialGradient(
                    listOf(pal.signal.copy(alpha = 0.14f), Color.Transparent),
                    center = Offset(size.width * 0.68f, size.height * 0.45f), radius = size.height * 0.6f,
                ),
            )
        },
    ) {
        for (i in games.indices.reversed()) {
            val slot = slots[i]
            val h = maxHeight * slot.height
            val v: State<Float> = floats[i]
            Capsule(
                games[i], RoundedCornerShape(14.dp),
                Modifier
                    .offset(x = maxWidth * slot.x, y = maxHeight * slot.y)
                    .size(h * 2f / 3f, h)
                    .graphicsLayer {
                        val t = if (still) 0f else v.value
                        rotationZ = slot.lean + 2f * t
                        translationY = -12.dp.toPx() * t
                        shadowElevation = 24.dp.toPx()
                        shape = RoundedCornerShape(14.dp)
                        clip = false
                    },
            )
        }
    }
}

private class FanSlot(val x: Float, val y: Float, val height: Float, val lean: Float)

/** A game's portrait capsule, or a blank one standing in for a game yet to be installed. */
@Composable
private fun Capsule(g: Library.SteamGame?, shape: RoundedCornerShape, modifier: Modifier) {
    val pal = LocalPalette.current
    if (g == null) {
        Box(modifier.clip(shape).background(Brush.linearGradient(listOf(pal.surface, pal.background))).border(1.dp, pal.line, shape))
        return
    }
    Box(modifier.clip(shape).background(artBrush(hueOf(g.name)))) {
        if (g.art != null) AsyncImage(model = g.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
    }
}
