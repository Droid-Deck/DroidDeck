package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.testTag
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
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
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
import kotlinx.coroutines.launch

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
    GamesDialogs(a)
    val onAdd: (() -> Unit)? = if (s.shortcutPicker) null else ({ GamesDialogState.adding = true })
    val pageContext = androidx.compose.ui.platform.LocalContext.current
    val onEdit: (Library.SteamGame) -> Unit = { g ->
        g.gameFiles?.let { GamesDialogState.editing = EditRequest(it.path, openTarget = false, fromSummary = false, preview = EditPreview.of(pageContext, g, it.path)) }
    }
    if (current == null) {
        Column(modifier = modifier.padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
            Rise(0) { PageHeader(stringResource(R.string.content_games)) { onAdd?.let { AddGameButton(it) } } }
            Rise(1) { Note(stringResource(if (s.shortcutPicker && s.shortcutLibraryScanning) R.string.game_shortcut_scanning else R.string.games_empty)) }
            if (!s.shortcutPicker) Rise(2) {
                Actions { PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.padding(top = 12.dp).testTag("play-steam"), onClick = a.onPlay) }
            }
            GameFileFolderActions(s, a)
        }
        return
    }
    val host = rememberMenuHost()
    if (games.size == 1) {
        Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
            if (onAdd != null) Rise(0) { GamesHeader(1, onAdd, Modifier.padding(bottom = 10.dp)) }
            Rise(0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    GameHero(current, Modifier.weight(1f).heightIn(min = if (narrow) 190.dp else 250.dp)) {
                        GameHeroCopy(current, if (narrow) 28.sp else 38.sp)
                        GameActions(current, s, a, onEdit)
                    }
                    if (!narrow) Poster(current.art, current.name, Modifier.width(168.dp))
                }
            }
            Rise(1) { SectionTitle(stringResource(R.string.games_launch_settings), null) }
            Rise(2) { LaunchSettings(s, a, host, current) }
            Rise(3) { GameFileFolderActions(s, a) }
        }
        return
    }
    val pal = LocalPalette.current
    Row(modifier = modifier) {
        GameList(games, current, onSelect = { onSelect("app:${it.appId}") }, onLaunch = { a.onSteamGame(it) }, onAdd = onAdd,
            modifier = Modifier.width(if (narrow) 168.dp else 250.dp).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 14.dp else 20.dp, vertical = 16.dp),
        ) {
            GameHero(current, Modifier.fillMaxWidth().heightIn(min = if (narrow) 170.dp else 200.dp)) {
                GameHeroCopy(current, if (narrow) 24.sp else 32.sp)
                GameActions(current, s, a, onEdit)
            }
            SectionTitle(stringResource(R.string.games_launch_settings), null)
            LaunchSettings(s, a, host, current)
            GameFileFolderActions(s, a)
        }
    }
}

@Composable
private fun GameFileFolderActions(s: FrontEndState, a: FrontEndActions) {
    if (s.shortcutPicker) return
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box(Modifier.padding(top = 12.dp)) {
        SecondaryButton(stringResource(R.string.game_frontend_files), compact = true) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = s.gameSyncFolder ?: stringResource(R.string.game_frontend_files)) { first ->
            MenuItem(stringResource(R.string.game_file_sync), checked = false, focusRequester = first) {
                open = false; a.onSyncGameFiles()
            }
            if (s.gameSyncFolder != null) MenuItem(stringResource(R.string.game_file_stop_sync), checked = false) {
                open = false; a.onStopGameFileSync()
            }
        }
    }
}


@Composable
private fun GameActions(g: Library.SteamGame, s: FrontEndState, a: FrontEndActions, onEdit: (Library.SteamGame) -> Unit) {
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
        // A game added in DroidDeck (Custom): its editor, Steam's Properties for a non-Steam game.
        if (!s.shortcutPicker && g.library == Library.ADDED && g.source == Library.ADDED && g.gameFiles != null) EditGameButton { onEdit(g) }
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
            MenuItem(stringResource(R.string.game_file_export), checked = false) {
                open = false; a.onExportGameFile(g)
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
        // A GOG or Epic game's saves are its cloud saves: the one place for them, named so.
        val cloudGame = com.droiddeck.launcher.stores.Store.byId(g.source).let { it == com.droiddeck.launcher.stores.Store.GOG || it == com.droiddeck.launcher.stores.Store.EPIC }
        val label = stringResource(if (cloudGame) R.string.store_cloud_saves else R.string.games_manage_saves)
        SecondaryButton(label, compact = true) { open = !open }
        AnchoredMenu(open, onDismiss = { open = false }, title = if (cloudGame) label else stringResource(R.string.games_saves), note = summary) { first ->
            if (cloudGame) CloudRows(g, open, first)
            MenuItem(stringResource(R.string.games_import_saves), checked = false, focusRequester = if (cloudGame) null else first) {
                open = false; a.onSaveImport(g)
            }
            MenuItem(stringResource(R.string.games_export_gamehub), checked = false) {
                open = false; a.onSaveExport(g, com.droiddeck.launcher.session.GameSaves.Layout.GAMEHUB)
            }
            MenuItem(stringResource(R.string.games_export_winlator), checked = false) {
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

/**
 * A GOG or Epic game's cloud saves: the Cloud saves switch, Upload and Download - three rows from
 * the first frame, so nothing moves under the pad while the cloud is checked. Until the check is
 * back the two actions are disabled and read "…"; then only their subtitles change: the last sync,
 * "No cloud saves", or a conflict, when Upload and Download become Keep local and Keep cloud.
 */
@Composable
private fun CloudRows(g: Library.SteamGame, open: Boolean, first: androidx.compose.ui.focus.FocusRequester) {
    val store = com.droiddeck.launcher.stores.Store.byId(g.source)?.takeIf { it != com.droiddeck.launcher.stores.Store.AMAZON } ?: return
    val id = g.storeId ?: return
    val context = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var busy by remember(g.gameId) { androidx.compose.runtime.mutableStateOf<String?>(null) }
    var tick by remember(g.gameId) { androidx.compose.runtime.mutableStateOf(0) }
    val status by androidx.compose.runtime.produceState<com.droiddeck.launcher.stores.CloudSaves.Status?>(null, g.gameId, open, tick) {
        if (open) value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
            runCatching { com.droiddeck.launcher.stores.CloudSaves.status(context, store, id) }.getOrNull()
        }
    }
    // The game's own switch (sidecar "cloud"), known locally: right from the first frame.
    val folder = g.gameFiles
    var enabled by remember(g.gameId, open) { androidx.compose.runtime.mutableStateOf(folder?.let { com.droiddeck.launcher.stores.StoreGameSidecar.read(it)?.cloud } ?: true) }
    val st = status
    val supported = st?.supported ?: true
    val conflict = st != null && st.conflicts.isNotEmpty()
    fun sync(up: Boolean, force: Boolean = false) {
        if (busy != null) return
        busy = if (up) "up" else "down"
        Thread({
            val r = if (up) com.droiddeck.launcher.stores.CloudSaves.upload(context, store, id, force) else com.droiddeck.launcher.stores.CloudSaves.download(context, store, id, force)
            com.droiddeck.launcher.stores.StoresState.post { busy = null; tick++; android.widget.Toast.makeText(context, if (r.ok) context.getString(R.string.cloud_done, r.files) else r.reason, android.widget.Toast.LENGTH_SHORT).show() }
        }, "cloud-manual").start()
    }
    MenuItem(stringResource(R.string.store_cloud_saves), checked = enabled && supported, enabled = supported, focusRequester = first,
        detail = if (!supported) stringResource(R.string.cloud_none) else null) {
        val f = folder ?: return@MenuItem
        val next = !enabled
        Thread({
            val written = runCatching { com.droiddeck.launcher.stores.StoreGameSidecar.updateCloud(f, next) }.getOrNull()
            com.droiddeck.launcher.stores.StoresState.post { enabled = written?.cloud ?: enabled }
        }, "cloud-switch").start()
    }
    val ready = st != null && supported && busy == null
    val pending = stringResource(R.string.cloud_pending)
    val summary = when {
        st == null || busy != null -> pending
        !supported -> stringResource(R.string.cloud_none)
        conflict -> stringResource(R.string.cloud_conflict_files, st.conflicts.size)
        st.lastSync > 0 -> stringResource(R.string.cloud_last_sync, android.text.format.DateUtils.getRelativeTimeSpanString(st.lastSync).toString())
        else -> stringResource(R.string.cloud_never)
    }
    // A conflict turns the two actions into its two answers; the rows stay where they are.
    MenuItem(stringResource(if (conflict) R.string.cloud_keep_local else R.string.cloud_upload), checked = false, enabled = ready, detail = summary) { sync(up = true, force = conflict) }
    MenuItem(stringResource(if (conflict) R.string.cloud_keep_cloud else R.string.cloud_download), checked = false, enabled = ready, detail = if (st == null || busy != null) pending else null) { sync(up = false, force = conflict) }
}

/** When it was last played (or where it is, if never) over its name, then room for Launch. */
@Composable
private fun ColumnScope.GameHeroCopy(g: Library.SteamGame, titleSize: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SourceChip(g.source)
        // A Custom game's chip already says what its eyebrow would; the line then stays empty.
        val eyebrow = lastPlayedText(g.lastPlayed) ?: libraryLabel(g)
        if (eyebrow.isNotEmpty()) Text(
            eyebrow.uppercase(),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = LocalPalette.current.signal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    Text(
        g.name, fontSize = titleSize, lineHeight = titleSize * 1.15f, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GameList(
    games: List<Library.SteamGame>, current: Library.SteamGame,
    onSelect: (Library.SteamGame) -> Unit, onLaunch: (Library.SteamGame) -> Unit, onAdd: (() -> Unit)?, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    // Laid out whole, as the art grid is: the pad's focus search only finds rows that exist.
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
    ) {
        GamesHeader(games.size, onAdd, Modifier.padding(start = 6.dp, bottom = 10.dp))
        for (g in games) key(g.appId) {
            GameRow(g, g.appId == current.appId, onSelect = { onSelect(g) }, onLaunch = { onLaunch(g) })
        }
    }
}

/** "Games N", and the + that adds a game from its .exe beside it ([onAdd] null: no +). */
@Composable
private fun GamesHeader(count: Int, onAdd: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = modifier) {
        Text(stringResource(R.string.content_games), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
        Text(count.toString(), fontSize = 13.sp, color = colors.onSurfaceVariant)
        if (onAdd != null) AddGameButton(onAdd)
    }
}

/**
 * The Games tab's dialogs, one at a time: the + picker, the summary after "Add all games in this
 * folder", and a game's editor (from the page's ✎ or a summary row, with back to the summary).
 */
internal object GamesDialogState {
    var adding by androidx.compose.runtime.mutableStateOf(false)
    var summary by androidx.compose.runtime.mutableStateOf<Pair<List<com.droiddeck.launcher.frontend.AddedGames.Game>, Int>?>(null)
    var editing by androidx.compose.runtime.mutableStateOf<EditRequest?>(null)
    /** The subfolder "Add all games in this folder" is looking at; null when it is done. */
    var checking by androidx.compose.runtime.mutableStateOf<String?>(null)
    /** The summary an editor opened from it goes back to. */
    var behindEditor: Pair<List<com.droiddeck.launcher.frontend.AddedGames.Game>, Int>? = null
}

@Composable
private fun GamesDialogs(a: FrontEndActions) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st = GamesDialogState
    if (st.adding) ExePickerDialog(
        stringResource(R.string.games_add_title),
        onAddAll = { dir ->
            st.summary = emptyList<com.droiddeck.launcher.frontend.AddedGames.Game>() to 0
            st.checking = dir.name
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.droiddeck.launcher.frontend.AddedGames.addFolder(
                        ctx.applicationContext, dir,
                        onGame = { g -> scope.launch { st.summary?.let { (list, n) -> st.summary = (list + g) to n } } },
                        onChecking = { name -> scope.launch { st.checking = name } },
                    )
                }
                st.checking = null
                a.onAddedGamesChanged(result.added.map { it.folder.path })
                st.summary?.let { (list, _) -> st.summary = list to result.already }
            }
        },
        onPick = { a.onAddGameExe(it.path) },
        onDismiss = { st.adding = false },
    )
    st.summary?.let { (added, already) ->
        AddedGamesSummaryDialog(added, already, checking = st.checking, onOpen = { folder, uncertain, preview ->
            st.behindEditor = added to already
            st.editing = EditRequest(folder, openTarget = uncertain, fromSummary = true, preview = preview)
        }, onRemove = { folder, appId, name ->
            st.summary = st.summary?.let { (list, n) -> list.filterNot { it.folder.path == folder } to n }
            a.onAddedGameRemoved(folder, appId, name)
        }, onDismiss = { st.summary = null; st.checking = null })
    }
    st.editing?.let { request ->
        AddedGameEditor(
            request,
            onBack = { st.behindEditor?.let { st.summary = it }; st.behindEditor = null },
            onClose = { st.editing = null },
            onChanged = { a.onAddedGamesChanged(listOf(request.folder)) },
            onRemove = { folder, appId, name -> a.onAddedGameRemoved(folder, appId, name) },
        )
    }
}

/** The ✎ on a Custom game's page: icon only. */
@Composable
private fun EditGameButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val label = stringResource(R.string.common_edit)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("game:edit").size(40.dp).clip(Shape12)
            .background(if (hot) pal.signal.copy(alpha = 0.18f) else colors.surface.copy(alpha = 0.55f))
            .glideBorder(hot, Shape12, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        androidx.compose.material3.Icon(Icons.Outlined.Edit, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(18.dp))
    }
}

/** A round + that opens the .exe picker. */
@Composable
private fun AddGameButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val focused by src.collectIsFocusedAsState()
    val hovered by src.collectIsHoveredAsState()
    val label = stringResource(R.string.games_add)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("games:add").size(32.dp).clip(CircleShape)
            .background(if (focused || hovered) pal.signal.copy(alpha = 0.18f) else colors.surfaceVariant)
            .glideBorder(focused, CircleShape, pal.signal)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        androidx.compose.material3.Icon(Icons.Filled.Add, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(20.dp))
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
            // Where it came from beside when it was played: a store's games sit among Steam's own.
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceChip(g.source, small = true)
                Text(
                    playedSpan(g.lastPlayed)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.games_never_played),
                    fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

/** A page's title, with room at its right for controls or status. */
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

/**
 * Where a never-played game lives, beside its source chip: Steam's library, the Games storage for
 * a store install (the chip names the store), "Custom game" for a folder the user added.
 */
@Composable
private fun libraryLabel(g: Library.SteamGame): String = when {
    g.library != Library.ADDED -> if (g.library == "internal") stringResource(R.string.games_internal) else g.library
    g.source != Library.ADDED -> stringResource(R.string.games_store_storage)
    else -> ""
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
