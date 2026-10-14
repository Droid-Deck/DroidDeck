package com.droiddeck.launcher.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExitTransition
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
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


internal class GameScores(val metacritic: Lookup<GameFacts.Metacritic>, val hltb: Lookup<GameFacts.Hltb>)

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

@Composable
internal fun HeroScores(scores: GameScores, modifier: Modifier = Modifier) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = modifier) {
        Looked(scores.metacritic, exit = fadeOut()) { lookup ->
            val found = (lookup as? Lookup.Found)?.value
            HeroStat(stringResource(R.string.stores_fact_metacritic), found?.url) {
                if (found != null) MetacriticBadge(found.score) else Placeholder(BadgeHeight)
            }
        }
        Looked(scores.hltb, exit = fadeOut()) { lookup ->
            val found = (lookup as? Lookup.Found)?.value
            val lead = found?.let { hltbRuns(it).firstOrNull() }
            HeroStat(lead?.second ?: stringResource(R.string.stores_hltb_main), found?.url) {
                if (lead != null) Text(hltbTime(lead.first), fontSize = 26.sp, lineHeight = 28.sp, fontWeight = FontWeight.Bold, color = Color.White, maxLines = 1)
                else Placeholder(64.dp)
            }
        }
    }
}

@Composable
private fun HeroStat(label: String, link: String?, value: @Composable () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = link != null && rememberHot(src)
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.padding(horizontal = 6.dp).width(1.dp).height(44.dp).background(Color.White.copy(alpha = 0.18f)))
        Column(
            horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.then(linkSurface(label, link, src, hot, StatShape, hotFill = Color.Black.copy(alpha = 0.45f))).widthIn(min = 96.dp).padding(horizontal = 14.dp, vertical = 8.dp),
        ) {
            Box(contentAlignment = Alignment.Center, modifier = Modifier.height(BadgeHeight)) { value() }
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                val tint = Color.White.copy(alpha = if (hot) 1f else 0.78f)
                Text(label.uppercase(), fontSize = 10.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = tint, maxLines = 1)
                if (link != null) Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = tint, modifier = Modifier.size(10.dp))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun StoreGameFacts(developer: String, publisher: String, releaseDate: String, scores: GameScores, scoresInHero: Boolean, modifier: Modifier = Modifier) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    val released = remember(releaseDate) { GameFacts.releaseLabel(releaseDate) }
    val publishedBy = publisher.takeUnless { it.isBlank() || it.trim().equals(developer.trim(), ignoreCase = true) }
    val rest = listOfNotNull(publishedBy, released)
    val credits = if (developer.isBlank() && rest.isEmpty()) null else buildAnnotatedString {
        if (developer.isNotBlank()) withStyle(SpanStyle(color = colors.onBackground, fontWeight = FontWeight.SemiBold)) { append(developer) }
        rest.forEachIndexed { i, part -> append(if (i == 0 && developer.isBlank()) part else " · $part") }
    }
    val hltbHere = scores.hltb.let { it is Lookup.Pending || (it as? Lookup.Found)?.value?.let { t -> hltbRuns(t).size > if (scoresInHero) 1 else 0 } == true }
    val scoreHere = !scoresInHero && scores.metacritic != Lookup.None
    if (credits == null && !hltbHere && !scoreHere) return
    val links: @Composable () -> Unit = {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            if (!scoresInHero) Looked(scores.metacritic, exit = fadeOut()) { lookup ->
                val found = (lookup as? Lookup.Found)?.value
                if (found != null) LineLink(stringResource(R.string.stores_fact_metacritic), found.url) { MetacriticBadge(found.score, small = true) }
                else Placeholder(BadgeHeight)
            }
            if (hltbHere) Looked(scores.hltb, exit = fadeOut()) { lookup ->
                val found = (lookup as? Lookup.Found)?.value
                if (found != null) LineLink(stringResource(R.string.stores_fact_hltb), found.url) {
                    for ((secs, what) in hltbRuns(found).drop(if (scoresInHero) 1 else 0)) Text(
                        buildAnnotatedString {
                            withStyle(SpanStyle(color = colors.onBackground, fontWeight = FontWeight.SemiBold, fontSize = 15.sp)) { append(hltbTime(secs)) }
                            append(" ")
                            append(what)
                        },
                        fontSize = 13.sp, color = pal.onSurfaceVariant, maxLines = 1,
                    )
                }
                else Placeholder(160.dp)
            }
        }
    }
    Column(modifier.fillMaxWidth().padding(top = 12.dp)) {
        val creditsText: @Composable (Modifier) -> Unit = { m ->
            if (credits != null) Text(credits, fontSize = 14.sp, color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = m)
        }
        if (scoresInHero) Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().heightIn(min = 40.dp)) {
            creditsText(Modifier.weight(1f))
            links()
        } else Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            creditsText(Modifier)
            links()
        }
        Box(Modifier.padding(top = 8.dp).fillMaxWidth().height(1.dp).background(pal.line))
    }
}

@Composable
private fun LineLink(label: String, link: String, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.then(linkSurface(label, link, src, hot, LinkShape)).padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        content()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal, maxLines = 1)
            Icon(Icons.AutoMirrored.Filled.OpenInNew, contentDescription = null, tint = pal.signal, modifier = Modifier.size(12.dp))
        }
    }
}

@Composable
private fun <T> Looked(lookup: Lookup<T>, exit: ExitTransition, content: @Composable (Lookup<T>) -> Unit) {
    var shown by remember { mutableStateOf(lookup) }
    if (lookup != Lookup.None) shown = lookup
    AnimatedVisibility(visible = lookup != Lookup.None, enter = fadeIn(), exit = fadeOut() + exit) { content(shown) }
}

private val StatShape = RoundedCornerShape(12.dp)
private val LinkShape = RoundedCornerShape(10.dp)
private val BadgeHeight = 32.dp

@Composable
private fun linkSurface(label: String, link: String?, src: MutableInteractionSource, hot: Boolean, shape: RoundedCornerShape, hotFill: Color? = null): Modifier {
    if (link == null) return Modifier
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val open = { openUrl(ctx, link) }
    return Modifier.paneItem("fact:$label").clip(shape)
        .background(if (hot) hotFill ?: pal.signal.copy(alpha = 0.16f) else Color.Transparent)
        .glideBorder(hot, shape, pal.signal, restColor = Color.Transparent)
        .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = open)
        .controllerConfirm(onClick = open)
}

@Composable
private fun hltbRuns(t: GameFacts.Hltb): List<Pair<Int, String>> = listOf(
    t.main to stringResource(R.string.stores_hltb_main),
    t.extras to stringResource(R.string.stores_hltb_extras),
    t.completionist to stringResource(R.string.stores_hltb_completionist),
).filter { it.first > 0 }

@Composable
private fun hltbTime(secs: Int): String =
    GameFacts.hours(secs)?.let { stringResource(R.string.stores_hltb_hours, it) } ?: stringResource(R.string.stores_hltb_minutes, maxOf(1, secs / 60))

internal fun metacriticColour(score: Int): Color = when {
    score >= 75 -> Color(0xFF66CC33)
    score >= 50 -> Color(0xFFFFCC33)
    else -> Color(0xFFFF6874)
}

@Composable
private fun MetacriticBadge(score: Int, small: Boolean = false) {
    val height = if (small) 24.dp else BadgeHeight
    Box(contentAlignment = Alignment.Center, modifier = Modifier.height(height).widthIn(min = height + 6.dp).clip(RoundedCornerShape(if (small) 6.dp else 8.dp)).background(metacriticColour(score)).padding(horizontal = 6.dp)) {
        Text("$score", fontSize = if (small) 13.sp else 17.sp, fontWeight = FontWeight.Black, color = Color(0xFF0A0B0D), maxLines = 1)
    }
}

@Composable
private fun Placeholder(width: Dp) {
    val pulse by rememberInfiniteTransition(label = "fact").animateFloat(
        0.35f, 0.8f, infiniteRepeatable(tween(900, easing = FastOutSlowInEasing), RepeatMode.Reverse), label = "pulse",
    )
    Box(Modifier.width(width).height(16.dp).alpha(pulse).clip(RoundedCornerShape(5.dp)).background(LocalPalette.current.line2))
}


private val previewScores = GameScores(
    Lookup.Found(GameFacts.Metacritic(93, "https://www.metacritic.com/game/the-witcher-3-wild-hunt/")),
    Lookup.Found(GameFacts.Hltb(main = 186_070, extras = 373_519, completionist = 630_794, url = "https://howlongtobeat.com/game/10270")),
)

private fun previewScore(score: Int) = Lookup.Found(GameFacts.Metacritic(score, "https://www.metacritic.com/"))

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

@Composable
private fun PreviewHero(title: String, scores: GameScores) {
    Box(Modifier.fillMaxWidth().height(180.dp).clip(Shape16).background(artBrush(hueOf(title)))) {
        Box(Modifier.matchParentSize().background(Brush.horizontalGradient(0.45f to Color.Transparent, 1f to Color.Black.copy(alpha = 0.6f))))
        Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp)) {
            Text(stringResource(R.string.stores_eyebrow_library).uppercase(), fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = LocalPalette.current.signal)
            Text(title, fontSize = 30.sp, fontWeight = FontWeight.Black, color = MaterialTheme.colorScheme.onBackground)
        }
        HeroScores(scores, Modifier.align(Alignment.BottomEnd).padding(end = 16.dp, bottom = 18.dp))
    }
}

@Preview(name = "Game page, all found", widthDp = 800, heightDp = 320)
@Composable
private fun FactsFoundPreview() = FactsPreview {
    PreviewHero("The Witcher 3: Wild Hunt", previewScores)
    StoreGameFacts("CD PROJEKT RED", "CD PROJEKT RED", "2015-05-19T00:00:00+02:00", previewScores, scoresInHero = true)
}

@Preview(name = "Game page, looking up", widthDp = 800, heightDp = 320)
@Composable
private fun FactsPendingPreview() = FactsPreview {
    val pending = GameScores(Lookup.Pending, Lookup.Pending)
    PreviewHero("Hades", pending)
    StoreGameFacts("Supergiant Games", "Supergiant Games", "2020.09.17", pending, scoresInHero = true)
}

@Preview(name = "Facts line, mixed", widthDp = 800, heightDp = 420)
@Composable
private fun FactsMixedPreview() = FactsPreview {
    PreviewCaption("Another studio's publisher; times still coming")
    StoreGameFacts("New World Computing", "Ubisoft", "1999-02-28", GameScores(previewScore(87), Lookup.Pending), scoresInHero = true)
    PreviewCaption("No times: credits only")
    StoreGameFacts("Mane6", "Maximum Entertainment", "2022-12-19T16:00:00.000Z", GameScores(previewScore(68), Lookup.None), scoresInHero = true)
    PreviewCaption("Only a main-story time, which the hero shows: credits only")
    StoreGameFacts("Tiny Indie Studio", "", "2019-11-05", GameScores(previewScore(42), Lookup.Found(GameFacts.Hltb(main = 2_400, extras = 0, completionist = 0, url = "https://howlongtobeat.com/"))), scoresInHero = true)
    PreviewCaption("Epic's no-date placeholder (2099) is not shown")
    StoreGameFacts("Studio", "", "2099-01-01T00:00:00.000Z", GameScores(previewScore(81), Lookup.None), scoresInHero = true)
}

@Preview(name = "Facts, narrow pane", widthDp = 420, heightDp = 200)
@Composable
private fun FactsNarrowPreview() = FactsPreview(narrow = true) {
    StoreGameFacts("CD PROJEKT RED", "CD PROJEKT RED", "2015-05-19T00:00:00+02:00", previewScores, scoresInHero = false)
}

@Preview(name = "Game page, Paper theme", widthDp = 800, heightDp = 320)
@Composable
private fun FactsPaperPreview() = FactsPreview(theme = Themes.PAPER) {
    PreviewHero("The Witcher 3: Wild Hunt", previewScores)
    StoreGameFacts("CD PROJEKT RED", "CD PROJEKT RED", "2015-05-19T00:00:00+02:00", previewScores, scoresInHero = true)
}
