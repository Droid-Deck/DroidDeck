package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.testTag
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.ui.semantics.semantics
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.frontend.Library
import kotlinx.coroutines.launch
import androidx.compose.runtime.setValue
import androidx.compose.runtime.getValue


@Composable
internal fun GameFileFolderActions(s: FrontEndState, a: FrontEndActions) {
    if (s.shortcutPicker) return
    var open by remember { androidx.compose.runtime.mutableStateOf(false) }
    Box {
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
internal fun UninstallEpicLauncher(s: FrontEndState, a: FrontEndActions) {
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
internal fun ClientTag() {
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
internal fun GamesDialogs(a: FrontEndActions, games: List<Library.SteamGame>) {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = androidx.compose.runtime.rememberCoroutineScope()
    val st = GamesDialogState
    if (st.adding) ExePickerDialog(
        stringResource(R.string.games_add_title),
        onAddAll = { dir, showHidden ->
            st.summary = emptyList<com.droiddeck.launcher.frontend.AddedGames.Game>() to 0
            st.checking = dir.name
            scope.launch {
                val result = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    com.droiddeck.launcher.frontend.AddedGames.addFolder(
                        ctx.applicationContext, dir,
                        onGame = { g -> scope.launch { st.summary?.let { (list, n) -> st.summary = (list + g) to n } } },
                        onChecking = { name -> scope.launch { st.checking = name } },
                        showHidden = showHidden,
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
internal fun AddGameButton(onClick: () -> Unit) {
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
    s.available != null && s.available != s.installed -> Chip(stringResource(R.string.games_runtime_update), ok = false)
    else -> Chip(stringResource(R.string.games_runtime_ready), ok = true)
}

internal fun playedSpan(lastPlayed: Long): String? {
    if (lastPlayed <= 0L) return null
    return android.text.format.DateUtils.getRelativeTimeSpanString(
        lastPlayed * 1000L, System.currentTimeMillis(), android.text.format.DateUtils.MINUTE_IN_MILLIS,
    ).toString()
}

@Composable
internal fun libraryLabel(g: Library.SteamGame): String = when {
    g.library == Library.XBOX_CLOUD -> stringResource(R.string.xbox_cloud_gaming)
    g.library != Library.ADDED -> if (g.library == "internal") stringResource(R.string.games_internal) else g.library
    g.source != Library.ADDED -> stringResource(R.string.games_store_storage)
    else -> ""
}
