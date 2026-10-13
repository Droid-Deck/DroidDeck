package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.GameFacts
import com.droiddeck.launcher.stores.Lookup
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

// A store game's facts under its hero: who made it and when, from the store; the Metacritic score
// and HowLongToBeat's times, looked up elsewhere by name. The store's facts are there at once; the
// looked-up ones hold their place while they come and step out if there is nothing to show.

internal class GameScores(val metacritic: Lookup<Int>, val hltb: Lookup<GameFacts.Hltb>)

/** Both lookups at once and off the main thread, each landing on its own; what memory already has shows without a wait. */
@Composable
internal fun rememberGameScores(title: String?): GameScores {
    val ctx = LocalContext.current.applicationContext
    var metacritic by remember(title) { mutableStateOf(title?.let { GameFacts.peekMetacritic(it) } ?: if (title == null) Lookup.None else Lookup.Pending) }
    var hltb by remember(title) { mutableStateOf(title?.let { GameFacts.peekHltb(it) } ?: if (title == null) Lookup.None else Lookup.Pending) }
    LaunchedEffect(title) {
        if (title == null) return@LaunchedEffect
        launch { metacritic = withContext(Dispatchers.IO) { GameFacts.metacritic(ctx, title) }?.let { Lookup.Found(it) } ?: Lookup.None }
        launch { hltb = withContext(Dispatchers.IO) { GameFacts.hltb(ctx, title) }?.let { Lookup.Found(it) } ?: Lookup.None }
    }
    return GameScores(metacritic, hltb)
}

/** The facts row; nothing at all when the store said nothing and neither lookup found the game. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoreGameFacts(developer: String, releaseDate: String, scores: GameScores, modifier: Modifier = Modifier) {
    val released = remember(releaseDate) { GameFacts.releaseLabel(releaseDate) }
    val anything = developer.isNotBlank() || released != null || scores.metacritic != Lookup.None || scores.hltb != Lookup.None
    if (!anything) return
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier.padding(top = 12.dp)) {
        if (developer.isNotBlank()) FactTile(stringResource(R.string.stores_fact_developer)) { FactText(developer) }
        if (released != null) FactTile(stringResource(R.string.stores_fact_released)) { FactText(released) }
        Looked(scores.metacritic) { lookup ->
            FactTile(stringResource(R.string.stores_fact_metacritic)) {
                if (lookup is Lookup.Found) MetacriticBadge(lookup.value) else Placeholder(ValueHeight)
            }
        }
        Looked(scores.hltb) { lookup ->
            FactTile(stringResource(R.string.stores_fact_hltb)) {
                if (lookup is Lookup.Found) HltbTimes(lookup.value) else Placeholder(220.dp)
            }
        }
    }
}

/** A looked-up tile: in from the start (holding its place while it asks), out with a fade when there was nothing. */
@Composable
private fun <T> Looked(lookup: Lookup<T>, content: @Composable (Lookup<T>) -> Unit) {
    // The last answer worth drawing, so the tile does not blank as it leaves.
    var shown by remember { mutableStateOf(lookup) }
    if (lookup != Lookup.None) shown = lookup
    AnimatedVisibility(visible = lookup != Lookup.None, enter = fadeIn(), exit = fadeOut() + shrinkHorizontally()) { content(shown) }
}

private val FactShape = RoundedCornerShape(12.dp)
private val ValueHeight = 24.dp

@Composable
private fun FactTile(label: String, value: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = Modifier.widthIn(max = 360.dp).clip(FactShape).background(pal.surface).border(1.dp, pal.line, FactShape)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = pal.onSurfaceVariant, maxLines = 1)
        Box(contentAlignment = Alignment.CenterStart, modifier = Modifier.heightIn(min = ValueHeight)) { value() }
    }
}

@Composable
private fun FactText(text: String) =
    Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)

/** Metacritic's own bands: green from 75, yellow from 50, red below. */
internal fun metacriticColour(score: Int): Color = when {
    score >= 75 -> Color(0xFF66CC33)
    score >= 50 -> Color(0xFFFFCC33)
    else -> Color(0xFFFF6874)
}

@Composable
private fun MetacriticBadge(score: Int) {
    Box(contentAlignment = Alignment.Center, modifier = Modifier.size(ValueHeight).clip(RoundedCornerShape(6.dp)).background(metacriticColour(score))) {
        Text("$score", fontSize = 13.sp, fontWeight = FontWeight.Black, color = Color(0xFF0A0B0D), maxLines = 1)
    }
}

/** Each kind of run HowLongToBeat has times for: the hours in full, what they are for beside them. */
@Composable
private fun HltbTimes(t: GameFacts.Hltb) {
    val colors = MaterialTheme.colorScheme
    val runs = listOf(
        t.main to stringResource(R.string.stores_hltb_main),
        t.extras to stringResource(R.string.stores_hltb_extras),
        t.completionist to stringResource(R.string.stores_hltb_completionist),
    ).filter { it.first > 0 }
    val h = runs.map { (secs, _) -> GameFacts.hours(secs)?.let { stringResource(R.string.stores_hltb_hours, it) } ?: stringResource(R.string.stores_hltb_minutes, maxOf(1, secs / 60)) }
    val line = buildAnnotatedString {
        runs.forEachIndexed { i, (_, what) ->
            if (i > 0) withStyle(SpanStyle(fontSize = 12.sp, color = colors.onSurfaceVariant)) { append("  ·  ") }
            withStyle(SpanStyle(fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)) { append(h[i]) }
            withStyle(SpanStyle(fontSize = 12.sp, color = colors.onSurfaceVariant)) { append(" $what") }
        }
    }
    Text(line, maxLines = 1, overflow = TextOverflow.Ellipsis)
}

/** Where a value will be, breathing while it is asked for. */
@Composable
private fun Placeholder(width: Dp) {
    val pulse by rememberInfiniteTransition(label = "fact").animateFloat(
        0.35f, 0.8f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    Box(Modifier.width(width).height(16.dp).alpha(pulse).clip(RoundedCornerShape(5.dp)).background(LocalPalette.current.line2))
}

// Previews: every state the row can be in, at a TV pane's width and a narrow one.

private val previewScores = GameScores(Lookup.Found(93), Lookup.Found(GameFacts.Hltb(main = 186_070, extras = 373_519, completionist = 630_794)))

@Composable
private fun FactsPreview(theme: String = Themes.GRAPHITE, narrow: Boolean = false, content: @Composable () -> Unit) {
    DroidDeckTheme(theme = theme) {
        CompositionLocalProvider(LocalNarrowPane provides narrow) {
            Box(Modifier.fillMaxSize().background(LocalPalette.current.background).padding(16.dp)) {
                Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) { content() }
            }
        }
    }
}

@Composable
private fun PreviewCaption(text: String) = Text(text, fontSize = 11.sp, color = LocalPalette.current.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp))

@Preview(name = "Facts, all found", widthDp = 800, heightDp = 160)
@Composable
private fun FactsFoundPreview() = FactsPreview {
    StoreGameFacts("CD PROJEKT RED", "2015-05-19T00:00:00+02:00", previewScores)
}

@Preview(name = "Facts, looking up", widthDp = 800, heightDp = 160)
@Composable
private fun FactsPendingPreview() = FactsPreview {
    StoreGameFacts("Supergiant Games", "2020.09.17", GameScores(Lookup.Pending, Lookup.Pending))
}

@Preview(name = "Facts, mixed", widthDp = 800, heightDp = 420)
@Composable
private fun FactsMixedPreview() = FactsPreview {
    PreviewCaption("Score found, times still coming")
    StoreGameFacts("Mane6", "2022-12-19T16:00:00.000Z", GameScores(Lookup.Found(68), Lookup.Pending))
    PreviewCaption("Neither site knows it: store facts only")
    StoreGameFacts("Tiny Indie Studio", "2024.03.01", GameScores(Lookup.None, Lookup.None))
    PreviewCaption("A low score, a short game with only a main-story time")
    StoreGameFacts("", "2019-11-05", GameScores(Lookup.Found(42), Lookup.Found(GameFacts.Hltb(main = 2_400, extras = 0, completionist = 0))))
    PreviewCaption("Epic's no-date placeholder (2099) is not shown")
    StoreGameFacts("Studio", "2099-01-01T00:00:00.000Z", GameScores(Lookup.Found(81), Lookup.None))
}

@Preview(name = "Facts, narrow pane", widthDp = 420, heightDp = 260)
@Composable
private fun FactsNarrowPreview() = FactsPreview(narrow = true) {
    StoreGameFacts("CD PROJEKT RED", "2015-05-19T00:00:00+02:00", previewScores)
}

@Preview(name = "Facts, Paper theme", widthDp = 800, heightDp = 160)
@Composable
private fun FactsPaperPreview() = FactsPreview(theme = Themes.PAPER) {
    StoreGameFacts("CD PROJEKT RED", "2015-05-19T00:00:00+02:00", previewScores)
}

// The real hero reads the stores' live state, so this stands one in to show where the row sits.
@Preview(name = "Game page, facts in place", widthDp = 800, heightDp = 480)
@Composable
private fun FactsOnPagePreview() = FactsPreview {
    val title = "The Witcher 3: Wild Hunt"
    Box(Modifier.fillMaxWidth().height(180.dp).clip(Shape16).background(artBrush(hueOf(title)))) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp)) {
            Text(stringResource(R.string.stores_eyebrow_library).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = LocalPalette.current.signal)
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
        }
    }
    StoreGameFacts("CD PROJEKT RED", "2015-05-19T00:00:00+02:00", previewScores)
    Text(
        "As war rages on throughout the Northern Realms, you take on the greatest contract of your life — tracking down the Child of Prophecy, a living weapon that can alter the shape of the world.",
        fontSize = 13.sp, lineHeight = 19.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(top = 12.dp),
    )
}
