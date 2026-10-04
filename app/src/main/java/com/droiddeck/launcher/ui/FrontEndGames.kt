package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
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
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.frontend.Library

// The Games page: the list, the hero for the selected game and its labels.

/**
 * The Games tab: installed Steam games down the left, most recently played first, and the one
 * picked beside them - its banner, Launch and the launch settings. One game skips the list.
 */
@Composable
internal fun GamesPage(s: FrontEndState, a: FrontEndActions, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    val games = remember(s.steamGames) { s.steamGames.sortedByDescending { it.lastPlayed } }
    val narrow = LocalNarrowPane.current
    val current = games.firstOrNull { "app:${it.appId}" == selected } ?: games.firstOrNull()
    if (current == null) {
        Column(modifier = modifier.padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
            Rise(0) { PageHeader(stringResource(R.string.content_games)) }
            Rise(1) { Note(stringResource(if (s.shortcutPicker && s.shortcutLibraryScanning) R.string.game_shortcut_scanning else R.string.games_empty)) }
            if (!s.shortcutPicker) Rise(2) {
                Actions { PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.padding(top = 12.dp), onClick = a.onPlay) }
            }
        }
        return
    }
    val host = rememberMenuHost()
    if (games.size == 1) {
        Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
            Rise(0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    GameHero(current, Modifier.weight(1f).heightIn(min = if (narrow) 190.dp else 250.dp)) {
                        GameHeroCopy(current, if (narrow) 28.sp else 38.sp)
                        GameActions(current, s, a)
                    }
                    if (!narrow) Poster(current.art, current.name, Modifier.width(168.dp))
                }
            }
            Rise(1) { SectionTitle(stringResource(R.string.games_launch_settings), null) }
            Rise(2) { LaunchSettings(s, a, host) }
        }
        return
    }
    val pal = LocalPalette.current
    Row(modifier = modifier) {
        GameList(games, current, onSelect = { onSelect("app:${it.appId}") }, onLaunch = { a.onSteamGame(it) },
            modifier = Modifier.width(if (narrow) 168.dp else 250.dp).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 14.dp else 20.dp, vertical = 16.dp),
        ) {
            GameHero(current, Modifier.fillMaxWidth().heightIn(min = if (narrow) 170.dp else 200.dp)) {
                GameHeroCopy(current, if (narrow) 24.sp else 32.sp)
                GameActions(current, s, a)
            }
            SectionTitle(stringResource(R.string.games_launch_settings), null)
            LaunchSettings(s, a, host)
        }
    }
}


@Composable
private fun GameActions(g: Library.SteamGame, s: FrontEndState, a: FrontEndActions) {
    Actions {
        PrimaryButton(stringResource(if (s.shortcutPicker) R.string.game_shortcut_choose else R.string.games_launch), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow) { a.onSteamGame(g) }
        g.gameFiles?.takeIf { it.isDirectory }?.let { dir ->
            SecondaryButton(stringResource(R.string.games_files), compact = true) { a.onBrowseFiles(dir) }
        }
        g.protonPrefix?.takeIf { it.isDirectory }?.let { dir ->
            SecondaryButton(stringResource(R.string.games_prefix), compact = true) { a.onBrowseFiles(dir) }
            // Only games added to the library; Steam titles keep their saves with Steam Cloud.
            if (g.library == Library.ADDED) ManageSaves(g, dir, a)
        }
        if (!s.shortcutPicker) GameShortcutMenu(g, a)
        BusyChip(s)
    }
}

@Composable
private fun GameShortcutMenu(g: Library.SteamGame, a: FrontEndActions) {
    var open by remember(g.gameId) { androidx.compose.runtime.mutableStateOf(false) }
    Box {
        SecondaryButton(stringResource(R.string.game_shortcut), compact = true) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = stringResource(R.string.game_shortcut)) { first ->
            MenuItem(stringResource(R.string.game_shortcut_add), checked = false, focusRequester = first) {
                open = false; a.onGameShortcut(g)
            }
            MenuItem(stringResource(R.string.game_link_copy), checked = false) {
                open = false; a.onCopyGameLink(g)
            }
        }
    }
}

/**
 * Manage saves: import a GameHub or Winlator save zip into this game's prefix, export its saves as
 * either, or open the save folder found for it. The saves are looked for when the menu opens.
 */
@Composable
private fun ManageSaves(g: Library.SteamGame, prefix: java.io.File, a: FrontEndActions) {
    var open by remember(g.gameId) { androidx.compose.runtime.mutableStateOf(false) }
    val saves by androidx.compose.runtime.produceState<List<com.droiddeck.launcher.session.GameSaves.SaveDir>?>(null, g.gameId, open) {
        if (open) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.droiddeck.launcher.session.GameSaves.locate(prefix, g) }.getOrDefault(emptyList())
        }
    }
    val found = saves
    val summary = when {
        found == null -> stringResource(R.string.games_saves_looking)
        found.isEmpty() -> stringResource(R.string.games_saves_none)
        else -> {
            val mb = found.sumOf { it.bytes } / 1048576.0
            stringResource(
                R.string.games_saves_summary,
                if (found.size == 1) found[0].relPath else stringResource(R.string.games_saves_folders, found.size),
                found.sumOf { it.files },
                if (mb < 1) stringResource(R.string.games_kb, (mb * 1024).toInt()) else stringResource(R.string.common_size_mb, mb),
            )
        }
    }
    Box {
        SecondaryButton(stringResource(R.string.games_manage_saves), compact = true) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = stringResource(R.string.games_saves), note = summary) { first ->
            MenuItem(stringResource(R.string.games_import_saves), checked = false, detail = stringResource(R.string.games_import_saves_hint), focusRequester = first) {
                open = false; a.onSaveImport(g)
            }
            MenuItem(stringResource(R.string.games_export_gamehub), checked = false, detail = stringResource(R.string.games_export_gamehub_hint)) {
                open = false; a.onSaveExport(g, com.droiddeck.launcher.session.GameSaves.Layout.GAMEHUB)
            }
            MenuItem(stringResource(R.string.games_export_winlator), checked = false, detail = stringResource(R.string.games_export_winlator_hint)) {
                open = false; a.onSaveExport(g, com.droiddeck.launcher.session.GameSaves.Layout.WINLATOR)
            }
            found?.firstOrNull()?.let { d ->
                MenuItem(stringResource(R.string.games_open_save_folder), checked = false, detail = d.relPath) {
                    open = false; a.onBrowseFiles(java.io.File(prefix, "drive_c/users/steamuser/" + d.relPath))
                }
            }
        }
    }
}

/** When it was last played (or where it is, if never) over its name, then room for Launch. */
@Composable
private fun ColumnScope.GameHeroCopy(g: Library.SteamGame, titleSize: androidx.compose.ui.unit.TextUnit) {
    Text(
        (lastPlayedText(g.lastPlayed) ?: libraryLabel(g.library)).uppercase(),
        fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = LocalPalette.current.signal,
        maxLines = 1, overflow = TextOverflow.Ellipsis,
    )
    Text(
        g.name, fontSize = titleSize, lineHeight = titleSize * 1.15f, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GameList(
    games: List<Library.SteamGame>, current: Library.SteamGame,
    onSelect: (Library.SteamGame) -> Unit, onLaunch: (Library.SteamGame) -> Unit, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // Laid out whole, as the art grid is: the pad's focus search only finds rows that exist.
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
    ) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(start = 6.dp, bottom = 10.dp)) {
            Text(stringResource(R.string.content_games), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
            Text(games.size.toString(), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        }
        for (g in games) key(g.appId) {
            GameRow(g, g.appId == current.appId, onSelect = { onSelect(g) }, onLaunch = { onLaunch(g) })
        }
    }
}

/** One game in the list. Moving onto it with the pad shows it; A launches it, a tap only shows it. */
@Composable
private fun GameRow(g: Library.SteamGame, selected: Boolean, onSelect: () -> Unit, onLaunch: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    LaunchedEffect(focused) { if (focused && !selected) onSelect() }
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().paneItem("game:${g.appId}")
            .clip(Shape12)
            .background(if (selected) pal.signal.copy(alpha = 0.14f) else if (hovered) Color.White.copy(alpha = 0.05f) else Color.Transparent)
            .glideBorder(focused, Shape12, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onSelect)
            .controllerConfirm(onClick = onLaunch)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.width(30.dp).height(45.dp).clip(RoundedCornerShape(5.dp)).background(artBrush(hueOf(g.name)))) {
            if (g.art != null) AsyncImage(model = g.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(g.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                playedSpan(g.lastPlayed)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.games_never_played),
                fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** A page's title, with room at its right for a status chip. */
@Composable
internal fun PageHeader(title: String, trailing: @Composable RowScope.() -> Unit = {}) {
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
    ) {
        Text(
            title, fontSize = if (LocalNarrowPane.current) 22.sp else 26.sp, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onBackground, maxLines = 1,
        )
        trailing()
    }
}

/** Why a launch button is greyed out: the runtime is being worked on. Nothing when it is not. */
@Composable
internal fun BusyChip(s: FrontEndState) {
    if (s.busy) ActionChip(if (s.percent >= 0) stringResource(R.string.games_runtime_busy_percent, s.percent) else s.stage, ok = false)
}

/** Whether Steam can start, said where Play is rather than only in Setup. */
@Composable
internal fun RuntimeChip(s: FrontEndState) = when {
    s.busy -> Chip(if (s.percent >= 0) stringResource(R.string.setup_runtime_progress, s.stage, s.percent) else s.stage, ok = false)
    s.removalPending -> Chip(stringResource(R.string.runtime_removal_incomplete), ok = false)
    !s.ready -> Chip(stringResource(R.string.games_runtime_first_play), ok = false)
    s.available != null && s.available != s.installed -> Chip(stringResource(R.string.games_runtime_update), ok = false)
    else -> Chip(stringResource(R.string.games_runtime_ready), ok = true)
}

/** "Last played 3 days ago" from Steam's unix seconds; null for a game never played. */
@Composable
private fun lastPlayedText(lastPlayed: Long): String? =
    playedSpan(lastPlayed)?.let { stringResource(R.string.games_last_played, it.replaceFirstChar { c -> c.lowercase() }) }

/** "3 days ago" from Steam's unix seconds; null for a game never played. */
private fun playedSpan(lastPlayed: Long): String? {
    if (lastPlayed <= 0L) return null
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        lastPlayed * 1000L, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

@Composable
private fun libraryLabel(library: String): String = when (library) {
    "internal" -> stringResource(R.string.games_internal)
    Library.ADDED -> stringResource(R.string.games_added)
    else -> library
}

/**
 * A game's wide banner: Steam's hero art where the client cached one, else its capsule blurred to
 * fill the width. The copy sits bottom-left over a scrim of the ground colour so it always reads;
 * the art takes the banner's size, so a banner given a minimum height grows to fit its copy.
 */
@Composable
private fun GameHero(g: Library.SteamGame, modifier: Modifier, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier.clip(Shape16).background(artBrush(hueOf(g.name)))) {
        val image = g.hero ?: g.art
        if (image != null) AsyncImage(
            model = image, contentDescription = null, contentScale = ContentScale.Crop,
            modifier = Modifier.matchParentSize()
                .then(if (g.hero == null) Modifier.blur(24.dp).graphicsLayer { scaleX = 1.3f; scaleY = 1.3f } else Modifier),
        )
        Spacer(
            Modifier.matchParentSize().background(
                Brush.horizontalGradient(
                    0f to colors.background.copy(alpha = 0.92f),
                    0.55f to colors.background.copy(alpha = 0.6f),
                    1f to Color.Transparent,
                ),
            ),
        )
        Column(
            verticalArrangement = Arrangement.spacedBy(4.dp),
            modifier = Modifier.align(Alignment.BottomStart).padding(horizontal = 22.dp, vertical = 18.dp),
            content = content,
        )
    }
}
