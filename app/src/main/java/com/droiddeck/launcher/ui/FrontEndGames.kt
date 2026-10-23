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
import androidx.compose.foundation.layout.BoxScope
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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
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
import com.droiddeck.launcher.stores.epic.EpicClient
import kotlinx.coroutines.launch


@Composable
internal fun GamesPage(s: FrontEndState, a: FrontEndActions, selected: String, onSelect: (String) -> Unit, modifier: Modifier) {
    val pageContext = androidx.compose.ui.platform.LocalContext.current
    LaunchedEffect(Unit) { EpicClient.refresh(pageContext) }
    val launcherName = stringResource(R.string.epic_client_title)
    val epic = EpicClient.status
    val launcherArt by androidx.compose.runtime.produceState<java.io.File?>(null, epic.version) {
        value = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { EpicClient.icon(pageContext, epic.version) }
    }
    val games = remember(s.steamGames, s.shortcutPicker, epic, launcherName, launcherArt) {
        val sorted = s.steamGames.sortedByDescending { it.lastPlayed }
        if (s.shortcutPicker) sorted else EpicClient.withLauncher(sorted, EpicClient.launcherEntry(pageContext, launcherName, epic, launcherArt))
    }
    val gameCount = games.count { it.library != Library.EPIC_LAUNCHER }
    val narrow = LocalNarrowPane.current
    val current = games.firstOrNull { "app:${it.appId}" == selected } ?: games.firstOrNull()
    GamesDialogs(a, s.steamGames)
    val onAdd: (() -> Unit)? = if (s.shortcutPicker) null else ({ GamesDialogState.adding = true })
    val launch: (Library.SteamGame) -> Unit = { g -> if (g.library == Library.EPIC_LAUNCHER) a.onEpicLauncher() else a.onSteamGame(g) }
    val onEdit: (Library.SteamGame) -> Unit = { g ->
        g.gameFiles?.let { GamesDialogState.editing = EditRequest(it.path, openTarget = false, fromSummary = false, preview = EditPreview.of(pageContext, g, it.path)) }
    }
    if (current == null || gameCount == 0) {
        Column(modifier = modifier.padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
            Rise(0) {
                PageHeader(stringResource(R.string.content_games)) {
                    if (s.steamGamesX64) ClientTag()
                    onAdd?.let { AddGameButton(it) }
                }
            }
            Rise(1) { Note(stringResource(if (s.shortcutPicker && s.shortcutLibraryScanning) R.string.game_shortcut_scanning else R.string.games_empty)) }
            if (!s.shortcutPicker) Rise(2) {
                Actions {
                    PrimaryButton(stringResource(R.string.games_play_steam), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow, modifier = Modifier.padding(top = 12.dp).testTag("play-steam"), onClick = a.onPlay)
                    games.firstOrNull { it.library == Library.EPIC_LAUNCHER }?.let { SecondaryButton(it.name, enabled = !s.busy, modifier = Modifier.padding(top = 12.dp)) { a.onEpicLauncher() } }
                }
            }
            GameFileFolderActions(s, a)
        }
        return
    }
    val host = rememberMenuHost()
    if (games.size == 1) {
        Column(modifier = modifier.verticalScroll(rememberScrollState()).padding(horizontal = if (narrow) 16.dp else 22.dp, vertical = if (narrow) 12.dp else 18.dp)) {
            if (onAdd != null) Rise(0) { GamesHeader(gameCount, s.steamGamesX64, onAdd, Modifier.padding(bottom = 10.dp)) }
            Rise(0) {
                Row(verticalAlignment = Alignment.Bottom) {
                    GameHero(current, Modifier.weight(1f).heightIn(min = if (narrow) 190.dp else 250.dp), corner = { GameRemoveButton(current, s, a) }) {
                        GameHeroCopy(current, if (narrow) 28.sp else 38.sp)
                        GameActions(current, s, a, onEdit, launch)
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
        GameList(games, gameCount, s.steamGamesX64, current, onSelect = { onSelect("app:${it.appId}") }, onLaunch = launch, onAdd = onAdd,
            modifier = Modifier.width(if (narrow) 168.dp else 250.dp).fillMaxHeight())
        Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
        Column(
            modifier = Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState())
                .padding(horizontal = if (narrow) 14.dp else 20.dp, vertical = 16.dp),
        ) {
            GameHero(current, Modifier.fillMaxWidth().heightIn(min = if (narrow) 170.dp else 200.dp), corner = { GameRemoveButton(current, s, a) }) {
                GameHeroCopy(current, if (narrow) 24.sp else 32.sp)
                GameActions(current, s, a, onEdit, launch)
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
private fun GameActions(g: Library.SteamGame, s: FrontEndState, a: FrontEndActions, onEdit: (Library.SteamGame) -> Unit, launch: (Library.SteamGame) -> Unit) {
    Actions {
        PrimaryButton(stringResource(if (s.shortcutPicker) R.string.game_shortcut_choose else R.string.games_launch), enabled = !s.busy, main = true, icon = Icons.Filled.PlayArrow) { launch(g) }
        if (g.library == Library.EPIC_LAUNCHER) {
            if (!s.shortcutPicker) UninstallEpicLauncher(s, a)
            BusyChip(s)
            return@Actions
        }
        g.gameFiles?.takeIf { it.isDirectory }?.let { dir ->
            SecondaryButton(stringResource(R.string.games_files), compact = true) { a.onBrowseFiles(dir) }
        }
        g.protonPrefix?.takeIf { it.isDirectory }?.let { dir ->
            SecondaryButton(stringResource(R.string.games_prefix), compact = true) { a.onBrowseFiles(dir) }
            if (g.library == Library.ADDED) ManageSaves(g, dir, a)
        }
        if (!s.shortcutPicker) GameShortcutMenu(g, a)
        if (!s.shortcutPicker && g.library == Library.ADDED && g.source == Library.ADDED && g.gameFiles != null) EditGameButton { onEdit(g) }
        else if (!s.shortcutPicker && g.library != Library.ADDED && g.source == Library.SOURCE_STEAM) EditGameButton { GamesDialogState.steamArt = g }
        else if (!s.shortcutPicker && g.library == Library.ADDED && g.source != Library.ADDED && g.gameFiles != null) EditGameButton { GamesDialogState.storeArt = g }
        BusyChip(s)
    }
}

@Composable
private fun UninstallEpicLauncher(s: FrontEndState, a: FrontEndActions) {
    var asking by remember { androidx.compose.runtime.mutableStateOf(false) }
    SecondaryButton(stringResource(R.string.common_uninstall), enabled = !s.busy, compact = true) { asking = true }
    if (asking) {
        val body = stringResource(R.string.epic_client_uninstall_body)
        ConfirmDialog(
            title = stringResource(R.string.stores_uninstall_title, stringResource(R.string.epic_client_title)),
            text = if (s.sessionRunning) body + "\n\n" + stringResource(R.string.epic_client_uninstall_stops) else body,
            confirm = stringResource(R.string.common_uninstall),
            onConfirm = { a.onUninstallEpicLauncher() },
            onDismiss = { asking = false },
        )
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

/** A skipped sync is not a success: "0 files" used to show for a signed-out store or cloud saves turned off. */
internal fun cloudMessage(context: android.content.Context, r: com.droiddeck.launcher.stores.CloudSaves.Result): String = when (r.result) {
    "ok" -> when {
        r.conflicts > 0 -> context.getString(R.string.cloud_conflict_files, r.conflicts)
        r.files == 0 -> context.getString(R.string.cloud_up_to_date)
        else -> context.getString(R.string.cloud_done, r.files)
    }
    "skipped" -> context.getString(when (r.reason) {
        "not-signed-in" -> R.string.cloud_signed_out
        "no-cloud-saves" -> R.string.cloud_none
        else -> R.string.cloud_not_synced
    })
    else -> context.getString(R.string.cloud_not_synced)
}

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
            com.droiddeck.launcher.stores.StoresState.post { busy = null; tick++; android.widget.Toast.makeText(context, cloudMessage(context, r), android.widget.Toast.LENGTH_SHORT).show() }
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
    val ready = st != null && supported && enabled && busy == null
    val pending = stringResource(R.string.cloud_pending)
    val summary = when {
        st == null || busy != null -> pending
        !supported -> stringResource(R.string.cloud_none)
        conflict -> stringResource(R.string.cloud_conflict_files, st.conflicts.size)
        st.lastSync > 0 -> stringResource(R.string.cloud_last_sync, android.text.format.DateUtils.getRelativeTimeSpanString(st.lastSync).toString())
        else -> stringResource(R.string.cloud_never)
    }
    MenuItem(stringResource(if (conflict) R.string.cloud_keep_local else R.string.cloud_upload), checked = false, enabled = ready, detail = summary) { sync(up = true, force = conflict) }
    MenuItem(stringResource(if (conflict) R.string.cloud_keep_cloud else R.string.cloud_download), checked = false, enabled = ready, detail = if (st == null || busy != null) pending else null) { sync(up = false, force = conflict) }
}

@Composable
private fun ColumnScope.GameHeroCopy(g: Library.SteamGame, titleSize: androidx.compose.ui.unit.TextUnit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        SourceChip(g.source)
        val eyebrow = lastPlayedText(g.lastPlayed) ?: libraryLabel(g)
        if (eyebrow.isNotEmpty()) Text(
            eyebrow.uppercase(),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp, color = LocalPalette.current.signal,
            maxLines = 1, overflow = TextOverflow.Ellipsis,
        )
    }
    GameTitle(g, titleSize)
    Spacer(Modifier.height(8.dp))
}

@Composable
private fun GameTitle(g: Library.SteamGame, titleSize: androidx.compose.ui.unit.TextUnit) {
    val logo = g.logo
    var failed by remember(logo) { androidx.compose.runtime.mutableStateOf(false) }
    if (logo != null && !failed) {
        AsyncImage(
            model = logo, contentDescription = g.name, contentScale = ContentScale.Fit, alignment = Alignment.CenterStart,
            onError = { failed = true },
            modifier = Modifier.padding(vertical = 4.dp).height((titleSize.value * 2.2f).dp).widthIn(max = (titleSize.value * 9f).dp).testTag("game-logo"),
        )
    } else Text(
        g.name, fontSize = titleSize, lineHeight = titleSize * 1.15f, fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis,
    )
}

@Composable
private fun GameList(
    games: List<Library.SteamGame>, count: Int, x64: Boolean, current: Library.SteamGame,
    onSelect: (Library.SteamGame) -> Unit, onLaunch: (Library.SteamGame) -> Unit, onAdd: (() -> Unit)?, modifier: Modifier,
) {
    val colors = MaterialTheme.colorScheme
    Column(
        verticalArrangement = Arrangement.spacedBy(2.dp),
        modifier = modifier.verticalScroll(rememberScrollState()).padding(start = 12.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
    ) {
        GamesHeader(count, x64, onAdd, Modifier.padding(start = 6.dp, bottom = 10.dp))
        val seen = remember { HashSet<Int>() }
        var settled by remember { androidx.compose.runtime.mutableStateOf(false) }
        for (g in games) key(g.appId) {
            Box(Modifier.arriveIn(settled && g.appId !in seen)) {
                GameRow(g, g.appId == current.appId, onSelect = { onSelect(g) }, onLaunch = { onLaunch(g) })
            }
        }
        androidx.compose.runtime.SideEffect {
            seen.addAll(games.map { it.appId })
            if (games.isNotEmpty()) settled = true
        }
    }
}

@Composable
private fun GamesHeader(count: Int, x64: Boolean, onAdd: (() -> Unit)?, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val narrow = LocalNarrowPane.current
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.content_games), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, maxLines = 1)
            Text(count.toString(), fontSize = 13.sp, color = colors.onSurfaceVariant)
            if (x64 && !narrow) ClientTag()
            if (onAdd != null) AddGameButton(onAdd)
        }
        if (x64 && narrow) Box(Modifier.padding(top = 4.dp)) { ClientTag() }
    }
}

@Composable
private fun ClientTag() {
    Box(Modifier.testTag("games-client-x64")) { StateChip(stringResource(R.string.mode_client_arch_x64), ChipTone.PLAIN) }
}

internal object GamesDialogState {
    var adding by androidx.compose.runtime.mutableStateOf(false)
    var summary by androidx.compose.runtime.mutableStateOf<Pair<List<com.droiddeck.launcher.frontend.AddedGames.Game>, Int>?>(null)
    var editing by androidx.compose.runtime.mutableStateOf<EditRequest?>(null)
    var checking by androidx.compose.runtime.mutableStateOf<String?>(null)
    var steamArt by androidx.compose.runtime.mutableStateOf<Library.SteamGame?>(null)
    var storeArt by androidx.compose.runtime.mutableStateOf<Library.SteamGame?>(null)
    var behindEditor: Pair<List<com.droiddeck.launcher.frontend.AddedGames.Game>, Int>? = null
}

@Composable
private fun GamesDialogs(a: FrontEndActions, games: List<Library.SteamGame>) {
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
    st.steamArt?.let { game ->
        SteamGameArtEditor(games.firstOrNull { it.appId == game.appId && it.library != Library.ADDED } ?: game, onClose = { st.steamArt = null }, onChanged = { a.onSteamGameArtChanged(game.appId) })
    }
    st.storeArt?.let { game ->
        val folder = game.gameFiles?.path
        if (folder != null) StoreGameArtEditor(folder, game.name, onClose = { st.storeArt = null }, onChanged = { a.onAddedGamesChanged(listOf(folder)) })
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

@Composable
private fun GameRemoveButton(g: Library.SteamGame, s: FrontEndState, a: FrontEndActions) {
    val kind = com.droiddeck.launcher.frontend.GameRemoval.kind(g)
    if (s.shortcutPicker || kind == com.droiddeck.launcher.frontend.GameRemoval.Kind.LAUNCHER) return
    if (g.gameFiles?.isDirectory != true) return
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    var asking by remember(g.gameId) { androidx.compose.runtime.mutableStateOf(false) }
    val shortcut = kind == com.droiddeck.launcher.frontend.GameRemoval.Kind.SHORTCUT
    val label = stringResource(if (shortcut) R.string.edit_remove else R.string.common_uninstall)
    val title = if (shortcut) stringResource(R.string.games_remove_title, g.name) else stringResource(R.string.stores_uninstall_title, g.name)
    val body = when {
        shortcut -> stringResource(R.string.games_remove_body)
        g.library == Library.ADDED -> stringResource(R.string.stores_uninstall_body)
        else -> stringResource(R.string.games_uninstall_body)
    }
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("game:remove").downToFirstTile().size(44.dp).clip(Shape12)
            .background(if (hot) colors.error.copy(alpha = 0.22f) else Color.Black.copy(alpha = 0.34f))
            .glideBorder(hot, Shape12, colors.error, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button) { asking = true }
            .controllerConfirm { asking = true }
            .semantics { contentDescription = label },
    ) {
        androidx.compose.material3.Icon(
            Icons.Outlined.Delete, contentDescription = null,
            tint = if (hot) colors.error else Color.White, modifier = Modifier.size(18.dp),
        )
    }
    if (asking) ConfirmDialog(
        title = title,
        text = body,
        confirm = label,
        onConfirm = { a.onGameRemoved(g) },
        onDismiss = { asking = false },
    )
}

@Composable
private fun EditGameButton(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val label = stringResource(R.string.common_edit)
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.paneItem("game:edit").downToFirstTile().size(44.dp).clip(Shape12)
            .background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.White.copy(alpha = 0.03f))
            .glideBorder(hot, Shape12, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = androidx.compose.ui.semantics.Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = label },
    ) {
        androidx.compose.material3.Icon(Icons.Outlined.Edit, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(18.dp))
    }
}

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

@Composable
internal fun Modifier.arriveIn(arriving: Boolean): Modifier {
    val t = remember { androidx.compose.animation.core.Animatable(if (arriving) 0f else 1f) }
    LaunchedEffect(Unit) { if (t.value < 1f) t.animateTo(1f, Motion.tw(280)) }
    return graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 12.dp.toPx() }
}

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
            .hoverable(src).clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = onSelect)
            .controllerConfirm(onClick = onLaunch)
            .padding(horizontal = 10.dp, vertical = 6.dp),
    ) {
        Box(Modifier.width(30.dp).height(45.dp).clip(RoundedCornerShape(5.dp)).background(artBrush(hueOf(g.name)))) {
            if (g.art != null) AsyncImage(model = g.art, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(g.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                SourceChip(g.source, small = true)
                Text(
                    if (g.library == Library.EPIC_LAUNCHER) libraryLabel(g)
                    else playedSpan(g.lastPlayed)?.replaceFirstChar { it.uppercase() } ?: stringResource(R.string.games_never_played),
                    fontSize = 12.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

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

@Composable
internal fun BusyChip(s: FrontEndState) {
    if (s.busy) ActionChip(if (s.percent >= 0) stringResource(R.string.games_runtime_busy_percent, s.percent) else s.stage, ok = false)
}

@Composable
internal fun RuntimeChip(s: FrontEndState) = when {
    s.busy -> Chip(if (s.percent >= 0) stringResource(R.string.setup_runtime_progress, s.stage, s.percent) else s.stage, ok = false)
    s.removalPending -> Chip(stringResource(R.string.runtime_removal_incomplete), ok = false)
    !s.ready -> Chip(stringResource(R.string.games_runtime_first_play), ok = false)
    s.available != null && s.available != s.installed -> Chip(stringResource(R.string.games_runtime_update), ok = false)
    else -> Chip(stringResource(R.string.games_runtime_ready), ok = true)
}

@Composable
private fun lastPlayedText(lastPlayed: Long): String? =
    playedSpan(lastPlayed)?.let { stringResource(R.string.games_last_played, it.replaceFirstChar { c -> c.lowercase() }) }

private fun playedSpan(lastPlayed: Long): String? {
    if (lastPlayed <= 0L) return null
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        lastPlayed * 1000L, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

@Composable
private fun libraryLabel(g: Library.SteamGame): String = when {
    g.library == Library.EPIC_LAUNCHER -> EpicClient.status.version?.substringBefore('+')?.let { stringResource(R.string.epic_client_version, it) } ?: ""
    g.library != Library.ADDED -> if (g.library == "internal") stringResource(R.string.games_internal) else g.library
    g.source != Library.ADDED -> stringResource(R.string.games_store_storage)
    else -> ""
}

@Composable
private fun GameHero(g: Library.SteamGame, modifier: Modifier, corner: @Composable BoxScope.() -> Unit = {}, content: @Composable ColumnScope.() -> Unit) {
    val colors = MaterialTheme.colorScheme
    Box(modifier = modifier.clip(Shape16).background(artBrush(hueOf(g.name)))) {
        val image = g.hero ?: g.art.takeIf { g.library != Library.EPIC_LAUNCHER }
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
        Box(Modifier.align(Alignment.TopEnd).padding(12.dp), content = corner)
    }
}
