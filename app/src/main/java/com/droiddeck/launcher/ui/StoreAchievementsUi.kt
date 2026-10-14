package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInHorizontally
import androidx.compose.foundation.border
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.EmojiEvents
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.StoreAchievements
import com.droiddeck.launcher.stores.StoreAchievements.Achievement
import com.droiddeck.launcher.stores.StoreAchievements.Reason
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date

private val TrophyGold = Color(0xFFF5C542)

internal class AchievementsState(val list: List<Achievement>?, val reason: Reason?, val reading: Boolean) {
    val done: Int get() = list?.count { it.unlocked } ?: 0
    val all: Boolean get() = !list.isNullOrEmpty() && list.all { it.unlocked }

    companion object { val READING = AchievementsState(null, null, true) }
}

@Composable
internal fun rememberAchievements(game: StoreAchievements.Game?): AchievementsState? {
    val ctx = LocalContext.current.applicationContext
    var state by remember(game) { mutableStateOf(if (game == null) null else AchievementsState.READING) }
    LaunchedEffect(game, StoreAchievements.revision) {
        if (game == null) return@LaunchedEffect
        val source = StoreAchievements.forSource(game.source) ?: return@LaunchedEffect
        val cached = withContext(Dispatchers.IO) { source.cached(ctx, game) }
        if (cached != null) state = AchievementsState(cached, null, true)
        state = when (val r = withContext(Dispatchers.IO) { source.fetch(ctx, game) }) {
            is StoreAchievements.Result.Found -> AchievementsState(r.list, null, false)
            is StoreAchievements.Result.Failed -> AchievementsState(cached, r.reason, false)
        }
    }
    return state
}

@Composable
internal fun achievementsNote(state: AchievementsState, label: String): String? = when {
    !state.list.isNullOrEmpty() -> null
    state.reading -> stringResource(R.string.achievements_reading)
    state.reason == Reason.SIGNED_OUT -> stringResource(R.string.achievements_signed_out, label)
    state.reason == Reason.UNREACHABLE -> stringResource(R.string.achievements_unreachable, label)
    else -> stringResource(R.string.achievements_none, label)
}

@Composable
internal fun Trophy(size: Dp = 18.dp, modifier: Modifier = Modifier, description: String? = stringResource(R.string.achievements_all)) {
    Icon(Icons.Filled.EmojiEvents, description, modifier.size(size), tint = TrophyGold)
}

@Composable
internal fun AchievementsProgress(state: AchievementsState, compact: Boolean = false) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val list = state.list ?: return
    if (list.isEmpty()) return
    Column(verticalArrangement = Arrangement.spacedBy(if (compact) 6.dp else 8.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.achievements_count, state.done, list.size), fontSize = 13.sp, color = colors.onSurfaceVariant)
            if (state.all) Trophy(16.dp)
        }
        LinearProgressIndicator(
            progress = { state.done / list.size.toFloat() },
            color = pal.signal, trackColor = pal.line,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
    }
}

@Composable
private fun AchievementGroupLabel(label: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 2.dp)) {
        Text(label.uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant)
        Box(Modifier.weight(1f).height(1.dp).background(pal.line))
    }
}

private fun groups(list: List<Achievement>): List<Pair<Int, List<Achievement>>> = listOf(
    R.string.achievements_group_unlocked to list.filter { it.unlocked }.sortedByDescending { it.unlockedAt },
    R.string.achievements_group_locked to list.filter { !it.unlocked && !it.hidden },
    R.string.achievements_group_hidden to list.filter { !it.unlocked && it.hidden },
).filter { it.second.isNotEmpty() }

@Composable
private fun AchievementIcon(a: Achievement, size: Dp) {
    Box(Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(LocalPalette.current.surfaceVariant)) {
        val url = if (a.unlocked) a.icon else a.iconLocked ?: a.icon
        if (url != null) AsyncImage(
            model = url, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.fillMaxSize().alpha(if (a.unlocked) 1f else 0.55f),
        )
    }
}

@Composable
internal fun AchievementRow(a: Achievement, compact: Boolean, modifier: Modifier = Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hot = interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(12.dp)
    val secret = a.hidden && !a.unlocked
    val icon = if (compact) 36.dp else 48.dp
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.fillMaxWidth().clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .focusable(interactionSource = interaction)
            .padding(if (compact) 8.dp else 10.dp),
    ) {
        AchievementIcon(a, icon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                if (secret) stringResource(R.string.achievements_hidden) else a.name,
                fontSize = if (compact) 14.sp else 15.sp, fontWeight = FontWeight.SemiBold,
                color = if (a.unlocked) colors.onBackground else colors.onSurfaceVariant,
                maxLines = if (compact) 1 else 2, overflow = TextOverflow.Ellipsis,
            )
            val desc = if (secret) stringResource(R.string.achievements_hidden_detail) else a.description
            if (desc.isNotEmpty()) Text(
                desc, fontSize = if (compact) 12.sp else 13.sp, lineHeight = if (compact) 16.sp else 18.sp,
                color = colors.onSurfaceVariant, maxLines = if (compact) 1 else 3, overflow = TextOverflow.Ellipsis,
            )
        }
        if (a.unlocked && a.unlockedAt > 0) {
            Spacer(Modifier.width(10.dp))
            Text(
                DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(a.unlockedAt * 1000)),
                fontSize = 12.sp, fontWeight = FontWeight.Medium, color = pal.signal,
            )
        }
    }
}

@Composable
internal fun StoreAchievementsCard(game: StoreAchievements.Game, title: String, modifier: Modifier = Modifier) {
    val label = StoreAchievements.forSource(game.source)?.label ?: return
    val state = rememberAchievements(game) ?: return
    var open by remember { mutableStateOf(false) }
    StoreAchievementsCardContent(label, state, modifier) { open = true }
    if (open) {
        VeilRing()
        Dialog(onDismissRequest = { open = false }, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
            CompositionLocalProvider(LocalFrontFocus provides null) {
                SessionAchievementsPanel(label, state, title, veil = Color.Transparent, floating = true, onClose = { open = false })
            }
        }
    }
}

@Composable
internal fun StoreAchievementsCardContent(label: String, state: AchievementsState, modifier: Modifier = Modifier, onOpen: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val list = state.list
    if (list.isNullOrEmpty() && !state.reading && state.reason != Reason.SIGNED_OUT && state.reason != Reason.UNREACHABLE) return
    val shape = RoundedCornerShape(16.dp)
    Column(
        verticalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.clip(shape).background(colors.surface).border(1.dp, pal.line, shape).padding(horizontal = 16.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
            Text(stringResource(R.string.achievements_title).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = colors.onSurfaceVariant, modifier = Modifier.weight(1f))
            if (!list.isNullOrEmpty()) {
                if (state.all) Trophy(16.dp, Modifier.padding(end = 6.dp))
                Text(
                    buildAnnotatedString {
                        withStyle(SpanStyle(fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)) { append("${state.done}") }
                        append(" / ${list.size}")
                    },
                    fontSize = 13.sp, color = colors.onSurfaceVariant,
                )
            }
        }
        if (list.isNullOrEmpty()) {
            Text(achievementsNote(state, label).orEmpty(), fontSize = 13.sp, color = colors.onSurfaceVariant)
            return@Column
        }
        LinearProgressIndicator(
            progress = { state.done / list.size.toFloat() },
            color = pal.signal, trackColor = pal.line,
            modifier = Modifier.fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
        )
        BoxWithConstraints(Modifier.fillMaxWidth()) {
            val size = 40.dp
            val gap = 8.dp
            val fit = ((maxWidth + gap) / (size + gap)).toInt().coerceAtLeast(1)
            val ordered = groups(list).flatMap { it.second }
            val shown = if (ordered.size <= fit) ordered else ordered.take(fit - 1)
            Row(horizontalArrangement = Arrangement.spacedBy(gap)) {
                for (a in shown) AchievementIcon(a, size)
                if (shown.size < ordered.size) Box(contentAlignment = Alignment.Center, modifier = Modifier.size(size).clip(RoundedCornerShape(8.dp)).background(pal.surfaceVariant)) {
                    Text("+${ordered.size - shown.size}", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant, maxLines = 1)
                }
            }
        }
        SecondaryButton(stringResource(R.string.achievements_show_all, list.size), compact = true, onClick = onOpen)
    }
}

private fun LazyListScope.achievementItems(list: List<Achievement>, compact: Boolean, first: FocusRequester, labels: Boolean = true) {
    var index = 0
    for ((label, group) in groups(list)) {
        if (labels) item(key = "group-$label") { AchievementGroupLabel(stringResource(label, group.size)) }
        val offset = index
        itemsIndexed(group, key = { _, a -> "a-" + a.id }) { i, a ->
            AchievementRow(a, compact, if (offset + i == 0) Modifier.focusRequester(first) else Modifier)
        }
        index += group.size
    }
}

@Composable
internal fun SessionAchievements(game: StoreAchievements.Game, title: String?, onClose: () -> Unit) {
    val label = StoreAchievements.forSource(game.source)?.label.orEmpty()
    SessionAchievementsPanel(label, rememberAchievements(game) ?: AchievementsState.READING, title, onClose = onClose)
}

@Composable
internal fun SessionAchievementsPanel(label: String, state: AchievementsState, title: String?, veil: Color = Color.Black.copy(alpha = 0.45f), floating: Boolean = false, onClose: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    BackHandler(onBack = onClose)
    val kinds = remember(state.list) {
        val list = state.list.orEmpty()
        val split = groups(list)
        if (split.size < 2) emptyList() else listOf(R.string.stores_tab_all to list) + split
    }
    var pick by remember { mutableIntStateOf(0) }
    if (pick >= kinds.size) pick = 0
    val slide = remember { MutableTransitionState(false).apply { targetState = true } }
    BoxWithConstraints(
        Modifier.fillMaxSize().background(veil)
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClick = onClose),
        contentAlignment = Alignment.CenterEnd,
    ) {
        val short = maxHeight < 440.dp
        AnimatedVisibility(slide, enter = slideInHorizontally(Motion.sp(0.8f)) { it } + fadeIn(Motion.tw(160)), label = "achievementsPanel") {
        Column(
            (if (floating) Modifier.padding(12.dp).fillMaxHeight().width(minOf(420.dp, maxWidth * 0.92f)).clip(RoundedCornerShape(20.dp))
                .background(pal.background).border(1.dp, pal.line2, RoundedCornerShape(20.dp))
            else Modifier.fillMaxHeight().width(minOf(420.dp, maxWidth * 0.92f)).background(pal.background.copy(alpha = 0.97f)))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                .controllerBack(onClose)
                .then(if (kinds.isEmpty()) Modifier else Modifier.bumpers({ pick = (pick + kinds.size - 1) % kinds.size }, { pick = (pick + 1) % kinds.size }))
                .padding(if (short) 12.dp else 16.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.achievements_title), fontSize = if (short) 18.sp else 20.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                    if (title != null) Text(title, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                state.list?.takeIf { it.isNotEmpty() }?.let { list ->
                    if (state.all) Trophy(20.dp, Modifier.padding(end = 6.dp))
                    Text(stringResource(R.string.achievements_count_short, state.done, list.size), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = pal.signal)
                }
            }
            val list = state.list
            if (list.isNullOrEmpty()) {
                Text(achievementsNote(state, label).orEmpty(), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 14.dp))
            } else {
                LinearProgressIndicator(
                    progress = { state.done / list.size.toFloat() },
                    color = pal.signal, trackColor = pal.line,
                    modifier = Modifier.padding(top = if (short) 6.dp else 10.dp).fillMaxWidth().height(4.dp).clip(RoundedCornerShape(2.dp)),
                )
                if (kinds.isNotEmpty()) TabStrip(
                    kinds.map { (name, group) -> if (name == R.string.stores_tab_all) stringResource(name) + " · " + group.size else stringResource(name, group.size) },
                    pick, { pick = it }, Modifier.padding(top = if (short) 8.dp else 12.dp),
                )
                val first = remember { FocusRequester() }
                val shown = kinds.getOrNull(pick)?.second ?: list
                LazyColumn(
                    Modifier.weight(1f).padding(top = 6.dp),
                    contentPadding = PaddingValues(vertical = 6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) { achievementItems(shown, short, first, labels = pick == 0) }
                LaunchedEffect(list.isNotEmpty(), pick) {
                    withFrameNanos {}
                    runCatching { first.requestFocus() }
                }
            }
        }
        }
    }
}

@Composable
internal fun DrawerAchievementsRow(progress: Pair<Int, Int>?, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val interaction = remember { MutableInteractionSource() }
    val hot = interaction.collectIsFocusedAsState().value
    val shape = RoundedCornerShape(12.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = modifier.fillMaxWidth().height(44.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else colors.surface)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .clickable(interactionSource = interaction, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 12.dp),
    ) {
        val all = progress != null && progress.second > 0 && progress.first == progress.second
        Icon(Icons.Filled.EmojiEvents, null, tint = if (all) TrophyGold else if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(stringResource(R.string.achievements_title), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.weight(1f))
        if (progress != null && progress.second > 0) {
            Text(stringResource(R.string.achievements_count_short, progress.first, progress.second), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = pal.signal)
        }
    }
}


private val previewList = listOf(
    Achievement("first", "First Steps", "Finish the tutorial.", hidden = false, unlocked = true, unlockedAt = 1_759_000_000, icon = null, iconLocked = null),
    Achievement("boss", "Giant Slayer", "Defeat the warden of the old keep without taking damage.", hidden = false, unlocked = true, unlockedAt = 1_760_100_000, icon = null, iconLocked = null),
    Achievement("collector", "Collector", "Find every relic in the valley.", hidden = false, unlocked = false, unlockedAt = 0, icon = null, iconLocked = null),
    Achievement("secret", "The Last Door", "Open it.", hidden = true, unlocked = false, unlockedAt = 0, icon = null, iconLocked = null),
)

@Composable
private fun PreviewSurface(content: @Composable () -> Unit) {
    DroidDeckTheme(theme = Themes.GRAPHITE) {
        Box(Modifier.fillMaxSize().background(LocalPalette.current.background).padding(16.dp)) { content() }
    }
}

@Preview(name = "Store page section", widthDp = 800, heightDp = 600)
@Composable
private fun StoreAchievementsSectionPreview() = PreviewSurface {
    Column(Modifier.width(340.dp)) { StoreAchievementsCardContent("GOG", AchievementsState(previewList, null, false)) {} }
}

@Preview(name = "Store page section, signed out", widthDp = 800, heightDp = 200)
@Composable
private fun StoreAchievementsSectionSignedOutPreview() = PreviewSurface {
    Column(Modifier.width(340.dp)) { StoreAchievementsCardContent("Epic", AchievementsState(null, Reason.SIGNED_OUT, false)) {} }
}

@Preview(name = "Session panel", widthDp = 800, heightDp = 500)
@Composable
private fun SessionAchievementsPanelPreview() = DroidDeckTheme(theme = Themes.GRAPHITE) {
    SessionAchievementsPanel("Epic", AchievementsState(previewList, null, false), "Hollow Valley", onClose = {})
}

@Preview(name = "Session panel, 4:3", widthDp = 560, heightDp = 420)
@Composable
private fun SessionAchievementsPanelShortPreview() = DroidDeckTheme(theme = Themes.GRAPHITE) {
    SessionAchievementsPanel("GOG", AchievementsState(previewList.map {
        Achievement(it.id, it.name, it.description, it.hidden, true, 1_760_000_000, null, null)
    }, null, false), "Hollow Valley", onClose = {})
}

@Preview(name = "Session panel, offline", widthDp = 800, heightDp = 500)
@Composable
private fun SessionAchievementsPanelOfflinePreview() = DroidDeckTheme(theme = Themes.GRAPHITE) {
    SessionAchievementsPanel("GOG", AchievementsState(null, Reason.UNREACHABLE, false), "Hollow Valley", onClose = {})
}

@Preview(name = "Drawer row", widthDp = 360, heightDp = 160)
@Composable
private fun DrawerAchievementsRowPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        DrawerAchievementsRow(2 to 4) {}
        DrawerAchievementsRow(4 to 4) {}
        DrawerAchievementsRow(null) {}
    }
}

@Preview(name = "Rows", widthDp = 480, heightDp = 300)
@Composable
private fun AchievementRowPreview() = PreviewSurface {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        AchievementsProgress(AchievementsState(previewList, null, false))
        for (a in previewList) AchievementRow(a, compact = false)
        AchievementRow(previewList[0], compact = true)
    }
}
