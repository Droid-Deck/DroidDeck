package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.background
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material3.Text
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.droiddeck.launcher.R
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.formatBytes
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.gog.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun GogMaintenanceActions(game: InstalledStoreGame) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val scope = rememberCoroutineScope()
    var menu by remember { mutableStateOf(false) }
    var patches by remember { mutableStateOf<List<GogOfflineDownload>?>(null) }
    var proposal by remember { mutableStateOf<GogMaintenance.Proposal?>(null) }
    var error by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var recoverablePatch by remember { mutableStateOf(false) }
    val key = "gog:${game.sidecar.id}"
    val download = com.droiddeck.launcher.stores.StoresState.download(key)
    val active = download?.isActive == true
    val check = GogMaintenance.checks[game.sidecar.id]
    LaunchedEffect(game.folder.path) { GogMaintenance.check(ctx, game) }
    LaunchedEffect(menu, active) {
        if (menu) {
            recoverablePatch = false
            val result = withContext(Dispatchers.IO) { runCatching {
                GogFiles.read(GogFiles.journal(game.folder))?.let {
                    it.optString("operation") == GogOperation.PATCH.name && it.optString("phase") in setOf("patch-starting", "patch-applying", "patch-uncertain")
                } == true
            } }
            result.onSuccess { recoverablePatch = it }.onFailure { error = it.message }
        }
    }
    fun prepare(operation: GogOperation, patch: GogOfflineDownload? = null) {
        menu = false; patches = null; busy = true; error = null
        scope.launch {
            runCatching { withContext(Dispatchers.IO) { GogMaintenance.prepare(ctx, game, operation, patch) } }
                .onSuccess { proposal = it }.onFailure { error = it.message }
            busy = false
        }
    }
    if (active) {
        ActionChip(downloadLabel(download!!), ok = false)
        if (download.interruptible) {
            SecondaryButton(stringResource(if (download.state == DownloadState.PAUSED) R.string.stores_dl_resume else R.string.stores_dl_pause)) {
                if (download.state == DownloadState.PAUSED) DownloadQueue.resume(ctx, key) else DownloadQueue.pause(key)
            }
        } else Text(stringResource(R.string.gog_patch_in_session), color = MaterialTheme.colorScheme.onSurfaceVariant)
    } else if (!game.sidecar.isInstalled || GogFiles.journal(game.folder).exists()) {
        SecondaryButton(stringResource(R.string.gog_resume), modifier = Modifier.testTag("gog-resume")) {
            busy = true
            scope.launch {
                runCatching {
                    withContext(Dispatchers.IO) {
                        GogMaintenance.resume(ctx, game)
                    }
                }.onSuccess { proposal = it }.onFailure { error = it.message }
                busy = false
            }
        }
    } else if (check?.state == GogMaintenance.State.AVAILABLE) {
        SecondaryButton(stringResource(R.string.gog_update, check.version.ifBlank { game.sidecar.title }), enabled = !busy, modifier = Modifier.testTag("gog-update")) { prepare(GogOperation.UPDATE) }
    }
    androidx.compose.foundation.layout.Box {
        val manageLabel = stringResource(if (busy) R.string.common_checking else R.string.gog_maintenance)
        if (LocalPillButtons.current) androidx.compose.foundation.layout.Box(Modifier.testTag("gog-maintenance")) {
            IconAction(Icons.Outlined.Build, manageLabel, "game:gog-maintenance", enabled = !busy && !active) { menu = !menu }
        } else SecondaryButton(manageLabel, enabled = !busy && !active, modifier = Modifier.testTag("gog-maintenance")) { menu = !menu }
        AnchoredMenu(menu, { menu = false }, title = stringResource(R.string.gog_maintenance), compact = true) { first ->
            MenuItem(stringResource(R.string.common_update), checked = false) { prepare(GogOperation.UPDATE) }
            MenuItem(stringResource(R.string.gog_check), checked = false, focusRequester = first) { menu = false; GogMaintenance.check(ctx, game, true) }
            MenuItem(stringResource(R.string.gog_repair), checked = false, modifier = Modifier.testTag("gog-repair")) { prepare(GogOperation.REPAIR) }
            MenuItem(stringResource(R.string.gog_patches), checked = false, modifier = Modifier.testTag("gog-patches")) {
                menu = false; busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { GogMaintenance.offlinePatches(ctx, game) } }
                        .onSuccess { patches = it }.onFailure { error = it.message }
                    busy = false
                }
            }
            MenuItem(stringResource(R.string.gog_refresh), checked = false) { prepare(GogOperation.REFRESH) }
            if (recoverablePatch) MenuItem(stringResource(R.string.gog_recover), checked = false, modifier = Modifier.testTag("gog-recover")) {
                menu = false; busy = true
                scope.launch {
                    runCatching { withContext(Dispatchers.IO) { GogMaintenance.recoverPatch(ctx, game) } }.onFailure { error = it.message }
                    busy = false
                }
            }
        }
    }
    if (check?.state == GogMaintenance.State.FAILED || check?.state == GogMaintenance.State.UNKNOWN) {
        if (check.reason.isNotBlank()) Text(check.reason, fontSize = 12.sp, color = colors.onSurfaceVariant)
    }
    patches?.let { list ->
        val shown = rememberShown { patches = null }
        val close = { shown.targetState = false }
        AppDialog(shown, close, "gogPatches", wide = false, maxWidth = 460.dp) {
            DialogTitleRow(stringResource(R.string.gog_patches), close)
            Text(game.sidecar.title, fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.gog_patch_requirements), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            if (list.isEmpty()) Text(stringResource(R.string.gog_no_patches), fontSize = 13.sp, color = colors.onSurfaceVariant)
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                list.forEach { patch ->
                    ListRow(onClick = { close(); prepare(GogOperation.PATCH, patch) }) {
                        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(patch.name, fontSize = 13.sp, lineHeight = 18.sp, fontWeight = FontWeight.Medium, color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text(listOf(StoreLanguage.displayName(patch.language).ifBlank { patch.language }, patch.version, formatBytes(patch.size)).filter { it.isNotBlank() }.joinToString(" · "),
                                fontSize = 11.sp, lineHeight = 15.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                        Text(stringResource(R.string.gog_select_patch), fontSize = 12.sp, color = LocalPalette.current.signal)
                    }
                }
            }
            SecondaryButton(stringResource(R.string.gog_refresh)) { prepare(GogOperation.REFRESH) }
        }
    }
    proposal?.let { selected ->
        val shown = rememberShown { proposal = null }
        val close = { shown.targetState = false }
        val action = when (selected.operation) {
            GogOperation.UPDATE -> R.string.common_update
            GogOperation.REPAIR -> R.string.gog_repair_action
            GogOperation.PATCH -> R.string.gog_open_patch
            GogOperation.REFRESH -> R.string.gog_refresh_action
        }
        AppDialog(shown, close, "gogMaintenance", wide = false, maxWidth = 460.dp) {
            DialogTitleRow(stringResource(action), close)
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(listOf(game.sidecar.title, selected.before.version).filter { it.isNotBlank() }.joinToString(" · "), fontSize = 12.sp, lineHeight = 16.sp, color = colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
                if (selected.operation != GogOperation.REPAIR) Text(selected.patch?.name ?: selected.target.version.ifBlank { selected.target.build }, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = colors.onBackground, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().clip(Shape12).background(colors.surface.copy(alpha = 0.65f)).padding(12.dp)) {
                GogMetric(stringResource(R.string.gog_download_label), formatBytes(selected.downloadBytes), Modifier.weight(1f))
                GogMetric(stringResource(if (selected.operation == GogOperation.PATCH || selected.operation == GogOperation.REFRESH) R.string.gog_space_estimate_label else R.string.gog_space_label),
                    formatBytes(selected.diskBytes), Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(stringResource(R.string.gog_managed_files), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                if (selected.fullFiles) Text(stringResource(R.string.gog_full_files), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
                if (selected.operation == GogOperation.PATCH) Text(stringResource(R.string.gog_patch_installer_note), fontSize = 12.sp, lineHeight = 18.sp, color = colors.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(stringResource(action), enabled = !busy, modifier = Modifier.testTag("gog-apply")) {
                    busy = true
                    close()
                    scope.launch {
                        runCatching { withContext(Dispatchers.IO) { GogMaintenance.enqueue(ctx, game, selected) } }.onFailure { error = it.message }
                        busy = false
                    }
                }
                SecondaryButton(stringResource(R.string.common_cancel), onClick = close)
            }
        }
    }
    error?.let { Text(it, fontSize = 12.sp, color = colors.error) }
}

@Composable
private fun GogMetric(label: String, value: String, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, fontSize = 11.sp, lineHeight = 15.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, fontSize = 15.sp, lineHeight = 20.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
    }
}
