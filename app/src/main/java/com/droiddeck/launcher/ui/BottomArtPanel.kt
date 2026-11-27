package com.droiddeck.launcher.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentGuest
import com.droiddeck.launcher.frontend.Library
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private const val ART_POLL_MS = 2_000L
private const val RESCAN_MS = 30_000L
private const val DRIFT_MS = 28_000

/** The game's art filling the bottom screen with a slow pan and zoom, for the running game or the one selected in Steam. */
@Composable
internal fun ArtPanel(runningGame: () -> String?) {
    val context = LocalContext.current.applicationContext
    var game by remember { mutableStateOf<IdleGame?>(null) }
    var playing by remember { mutableStateOf(false) }
    FullScreenChrome()
    LaunchedEffect(Unit) {
        var games = emptyList<Library.SteamGame>()
        var scanned = 0L
        while (isActive) {
            val running = runningGame()?.toLongOrNull()
            val shown = running ?: withContext(Dispatchers.IO) { runCatching { AgentGuest.selectedSteamAppId(context) }.getOrNull() }
            val missing = games.isEmpty() || (shown != null && games.none { it.appId.toLong() == shown })
            if (missing && (scanned == 0L || System.currentTimeMillis() - scanned > RESCAN_MS)) {
                withContext(Dispatchers.IO) { runCatching { Library.steamGames(context) }.getOrNull() }?.let { games = it }
                scanned = System.currentTimeMillis()
            }
            // Reloaded every poll so stats that failed to load once still arrive; the crossfade is keyed by app id.
            val loaded = withContext(Dispatchers.IO) { IdleHome.load(context, games, shown) }
            if (loaded == null) { if (shown != null) game = null } else if (loaded != game) game = loaded
            playing = running != null && running == game?.appId?.toLong()
            delay(ART_POLL_MS)
        }
    }
    Box(Modifier.fillMaxSize().background(Color.Black).testTag("bottom-art")) {
        // Keyed by app id so a refreshed game keeps its pan; the fading-out scene keeps its last details.
        val seen = remember { HashMap<Int, IdleGame>() }
        game?.let { seen[it.appId] = it }
        Crossfade(game?.appId, label = "art") { id ->
            // Read from state here, so refreshed details for the same game recompose this scene.
            val shown = game?.takeIf { it.appId == id } ?: seen[id]
            if (shown != null) ArtScene(shown, playing)
        }
    }
}

@Composable
private fun ArtScene(game: IdleGame, playing: Boolean) {
    val drift = rememberInfiniteTransition(label = "drift")
    val t by drift.animateFloat(0f, 1f, infiniteRepeatable(tween(DRIFT_MS, easing = LinearEasing), RepeatMode.Reverse), label = "t")
    val eased = t * t * (3 - 2 * t)
    BoxWithConstraints(Modifier.fillMaxSize()) {
        if (game.art != null) AsyncImage(
            model = game.art, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().graphicsLayer {
                val scale = 1.08f + 0.14f * eased
                scaleX = scale
                scaleY = scale
                translationX = (eased - 0.5f) * size.width * (scale - 1f)
                translationY = (0.5f - eased) * size.height * (scale - 1f) * 0.5f
            },
        )
        Box(
            Modifier.fillMaxSize().background(
                Brush.verticalGradient(0.55f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.78f)),
            ),
        )
        Column(Modifier.align(Alignment.BottomStart).padding(28.dp).fillMaxWidth(0.6f)) {
            Text(
                stringResource(if (playing) R.string.bottom_art_playing else if (game.highlighted) R.string.bottom_idle_selected
                    else R.string.bottom_idle_recent).uppercase(),
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.4.sp, color = Color.White.copy(alpha = 0.7f),
            )
            if (game.logo != null) AsyncImage(
                model = game.logo, contentDescription = game.name, contentScale = ContentScale.Fit, alignment = Alignment.BottomStart,
                modifier = Modifier.padding(top = 10.dp).widthIn(max = 460.dp).height(128.dp),
            ) else Text(
                game.name, fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 2,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp),
            )
            Text(idleDetails(game), fontSize = 14.sp, color = Color.White.copy(alpha = 0.75f), maxLines = 1,
                modifier = Modifier.padding(top = 8.dp))
        }
    }
}
