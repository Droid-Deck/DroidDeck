package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.maintenance.MaintenanceAction
import com.droiddeck.launcher.stores.maintenance.MaintenanceCheckState
import com.droiddeck.launcher.stores.maintenance.MaintenanceProposal
import com.droiddeck.launcher.stores.maintenance.MaintenanceRecovery
import com.droiddeck.launcher.stores.maintenance.RecoveryState
import com.droiddeck.launcher.stores.maintenance.StoreMaintenance
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

@Composable
internal fun SteamClientMaintenanceActions(actions: FrontEndActions, sessionRunning: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (sessionRunning) SecondaryButton("Open Steam downloads", modifier = Modifier.testTag("steam-downloads")) {
                actions.onSteamUrl("steam://open/downloads")
            } else SecondaryButton("Start Steam", modifier = Modifier.testTag("steam-start")) { actions.onPlay() }
        }
        Text("In Steam, open this game’s Properties → Installed Files → Verify integrity.",
            fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun StoreMaintenanceActions(game: InstalledStoreGame) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val adapter = StoreMaintenance.adapter(game) ?: return
    val capabilities = adapter.capabilities(game)
    val check = StoreMaintenance.checks[game.key]
    val key = game.key
    val download = StoresState.download(key)
    val active = download?.isActive == true
    var busy by remember(key) { mutableStateOf(false) }
    var error by remember(key) { mutableStateOf<String?>(null) }
    var proposal by remember(key) { mutableStateOf<MaintenanceProposal?>(null) }
    var recovery by remember(key) { mutableStateOf(MaintenanceRecovery()) }
    var menu by remember(key) { mutableStateOf(false) }

    LaunchedEffect(game.folder.path) { StoreMaintenance.check(context, game) }
    LaunchedEffect(game.folder.path, active, download?.state) {
        recovery = withContext(Dispatchers.IO) { StoreMaintenance.recovery(context, game) }
    }

    Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
        if (active) {
            ActionChip(downloadLabel(download!!), ok = false)
            if (download.interruptible) SecondaryButton(
                stringResource(if (download.state == com.droiddeck.launcher.stores.download.DownloadState.PAUSED) com.droiddeck.launcher.R.string.stores_dl_resume else com.droiddeck.launcher.R.string.stores_dl_pause),
            ) {
                if (download.state == com.droiddeck.launcher.stores.download.DownloadState.PAUSED) DownloadQueue.resume(context, key) else DownloadQueue.pause(key)
            }
        } else if (recovery.state == RecoveryState.RESUMABLE) {
            SecondaryButton("Resume", enabled = !busy, modifier = Modifier.testTag("store-resume")) {
                busy = true
                scope.launch {
                    try {
                        runCatchingCancellable { withContext(Dispatchers.IO) { StoreMaintenance.resume(context, game) } }
                            .onFailure { error = it.message }
                    } finally { busy = false }
                }
            }
        } else if (MaintenanceAction.UPDATE in capabilities && check?.state == MaintenanceCheckState.AVAILABLE) {
            val target = check.version.takeIf { it.isNotBlank() }
            SecondaryButton(target?.let { "Update to $it" } ?: "Update", enabled = !busy, modifier = Modifier.testTag("store-update")) {
                beginPrepare(MaintenanceAction.UPDATE, context, game, scope, { busy = it }, { proposal = it }, { error = it })
            }
        }
        if (!active) Box {
            SecondaryButton("Maintenance", enabled = !busy, modifier = Modifier.testTag("store-maintenance")) { menu = !menu }
            AnchoredMenu(menu, onDismiss = { menu = false }, title = "Maintenance", compact = true) { first ->
                MenuItem("Check for updates", checked = false, enabled = !busy && game.sidecar.isInstalled,
                    modifier = Modifier.testTag("store-check"), focusRequester = first) {
                    menu = false
                    StoreMaintenance.check(context, game, force = true)
                }
                if (MaintenanceAction.UPDATE in capabilities) {
                    val legacyBootstrap = game.sidecar.store in setOf(com.droiddeck.launcher.stores.Store.EPIC, com.droiddeck.launcher.stores.Store.AMAZON) &&
                        check?.state != MaintenanceCheckState.AVAILABLE
                    MenuItem(if (legacyBootstrap) "Update / verify current build" else "Update", checked = false, enabled = !busy,
                        modifier = Modifier.testTag("store-update-menu")) {
                        beginPrepare(MaintenanceAction.UPDATE, context, game, scope, { busy = it }, { proposal = it }, { error = it })
                    }
                }
                if (MaintenanceAction.REPAIR in capabilities) MenuItem("Verify / Repair", checked = false, enabled = !busy,
                    modifier = Modifier.testTag("store-repair")) {
                    beginPrepare(MaintenanceAction.REPAIR, context, game, scope, { busy = it }, { proposal = it }, { error = it })
                }
                if (MaintenanceAction.REFRESH in capabilities) MenuItem("Refresh", checked = false, enabled = !busy,
                    modifier = Modifier.testTag("store-refresh")) {
                    beginPrepare(MaintenanceAction.REFRESH, context, game, scope, { busy = it }, { proposal = it }, { error = it })
                }
                if (MaintenanceAction.OPEN_CLIENT in capabilities) MenuItem(adapter.openClientLabel(game), checked = false, enabled = !busy,
                    modifier = Modifier.testTag("store-open-client")) {
                    menu = false
                    runCatching { StoreMaintenance.openClient(context, game) }.onFailure { error = it.message }
                }
            }
        }
    }
    if (!active && recovery.state == RecoveryState.NEEDS_ATTENTION && recovery.message.isNotBlank())
        Text(recovery.message, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    check?.let { current ->
        when (current.state) {
            MaintenanceCheckState.CHECKING -> Text("Checking for updates…", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MaintenanceCheckState.CURRENT -> Text("Up to date", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            MaintenanceCheckState.AVAILABLE -> if (MaintenanceAction.UPDATE !in capabilities) {
                val message = if (MaintenanceAction.OPEN_CLIENT in capabilities) {
                    "An update is available. Use ${adapter.openClientLabel(game)} to apply it."
                } else {
                    "An update is available."
                }
                Text(message,
                    fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            MaintenanceCheckState.UNKNOWN, MaintenanceCheckState.FAILED -> if (current.reason.isNotBlank())
                Text(current.reason, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    error?.let { Text(it, fontSize = 12.sp, color = MaterialTheme.colorScheme.error) }

    proposal?.let { selected ->
        val shown = rememberShown { proposal = null }
        val close = { shown.targetState = false }
        AppDialog(shown, close, "storeMaintenance", wide = false, maxWidth = 460.dp) {
            DialogTitleRow(selected.operation.label(), close)
            Column(verticalArrangement = Arrangement.spacedBy(5.dp)) {
                Text(game.sidecar.title, fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(selected.targetLabel, fontSize = 15.sp, color = MaterialTheme.colorScheme.onBackground)
                if (selected.description.isNotBlank()) Text(selected.description, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp), modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                Column(Modifier.weight(1f)) {
                    Text("Download", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(maintenanceEstimate(selected.downloadBytes), fontSize = 14.sp)
                }
                Column(Modifier.weight(1f)) {
                    Text("Required space", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(maintenanceEstimate(selected.diskBytes), fontSize = 14.sp)
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                PrimaryButton(selected.operation.label(), enabled = !busy) {
                    busy = true
                    close()
                    scope.launch {
                        try {
                            runCatchingCancellable { withContext(Dispatchers.IO) { StoreMaintenance.enqueue(context, game, selected) } }
                                .onFailure { error = it.message }
                        } finally { busy = false }
                    }
                }
                SecondaryButton("Cancel", onClick = close)
            }
        }
    }
}

private fun beginPrepare(
    action: MaintenanceAction,
    context: android.content.Context,
    game: InstalledStoreGame,
    scope: kotlinx.coroutines.CoroutineScope,
    setBusy: (Boolean) -> Unit,
    setProposal: (MaintenanceProposal) -> Unit,
    setError: (String?) -> Unit,
) {
    setBusy(true)
    scope.launch {
        try {
            runCatchingCancellable { withContext(Dispatchers.IO) { StoreMaintenance.prepare(context, game, action) } }
                .onSuccess(setProposal).onFailure { setError(it.message) }
        } finally { setBusy(false) }
    }
}

private suspend fun <T> runCatchingCancellable(block: suspend () -> T): Result<T> = try {
    Result.success(block())
} catch (cancelled: CancellationException) {
    throw cancelled
} catch (error: Throwable) {
    Result.failure(error)
}

private fun MaintenanceAction.label(): String = when (this) {
    MaintenanceAction.UPDATE -> "Update"
    MaintenanceAction.REPAIR -> "Repair files"
    MaintenanceAction.REFRESH -> "Refresh installation"
    MaintenanceAction.OPEN_CLIENT -> "Open client"
}

private fun maintenanceEstimate(bytes: Long): String =
    if (bytes < 0L) "Unknown (client-determined)" else com.droiddeck.launcher.stores.formatBytes(bytes)
