package com.droiddeck.launcher.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Block
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.material.icons.outlined.WarningAmber
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.sync.SyncDevice
import com.droiddeck.launcher.sync.SyncFolder
import com.droiddeck.launcher.sync.SyncPendingFolder
import com.droiddeck.launcher.sync.SyncSnapshot
import com.droiddeck.launcher.sync.Syncthing
import com.droiddeck.launcher.sync.SyncthingWebActivity
import java.io.File

@Composable
internal fun SyncPanel(host: MenuHost, runtimeReady: Boolean, onAdvanced: () -> Unit) {
    val ctx = LocalContext.current
    DisposableEffect(Unit) { val release = Syncthing.watch(); onDispose { release() } }
    var showId by rememberSaveable { mutableStateOf(false) }
    var confirmOff by rememberSaveable { mutableStateOf(false) }
    val snap = Syncthing.snapshot
    if (showId && snap != null) DeviceIdDialog(snap.myId) { showId = false }
    if (confirmOff) TurnOffDialog(onDismiss = { confirmOff = false }) { forget ->
        Syncthing.setEnabled(ctx, false, forget)
    }
    val phase = Syncthing.phase
    when {
        !Syncthing.enabled -> SyncOff(host, runtimeReady) { Syncthing.setEnabled(ctx, true) }
        phase == Syncthing.Phase.FAILED -> SyncFailed(
            if (Syncthing.failure == Syncthing.FAILURE_MISSING) stringResource(R.string.sync_missing) else Syncthing.failure.orEmpty(),
            onRetry = { Syncthing.restart() }, onAdvanced = onAdvanced, onOff = { confirmOff = true })
        phase != Syncthing.Phase.RUNNING || snap == null -> SyncStarting()
        else -> SyncRunning(snap, onShowId = { showId = true }, onAdvanced = onAdvanced, onOff = { confirmOff = true })
    }
}

@Composable
private fun SyncOff(host: MenuHost, runtimeReady: Boolean, onEnable: () -> Unit) {
    val missing = Syncthing.failure == Syncthing.FAILURE_MISSING
    val hint = when {
        missing -> R.string.sync_missing
        !runtimeReady -> R.string.sync_needs_runtime
        else -> R.string.sync_enable_hint
    }
    SettingsGroup(stringResource(R.string.sync_group)) {
        ToggleRow(
            host, "sync-enable", stringResource(R.string.sync_enable), stringResource(hint), false, enabled = !missing && runtimeReady,
        ) { if (it) onEnable() }
    }
}

@Composable
private fun SyncStarting() {
    val colors = MaterialTheme.colorScheme
    Column(
        horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth().padding(vertical = 96.dp),
    ) {
        CircularProgressIndicator(strokeWidth = 3.dp, color = LocalPalette.current.signal, modifier = Modifier.size(40.dp))
        Text(stringResource(R.string.sync_starting), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onSurfaceVariant)
    }
}

@Composable
private fun SyncFailed(reason: String, onRetry: () -> Unit, onAdvanced: () -> Unit, onOff: () -> Unit) {
    SettingsGroup(stringResource(R.string.sync_group)) {
        SettingsRow(stringResource(R.string.sync_status_failed), reason) {
            SecondaryButton(stringResource(R.string.sync_failed_retry), compact = true, onClick = onRetry)
        }
        SettingsRow(stringResource(R.string.sync_advanced), stringResource(R.string.sync_advanced_hint)) {
            SecondaryButton(stringResource(R.string.sync_open), compact = true, onClick = onAdvanced)
        }
        SettingsRow(stringResource(R.string.sync_running), stringResource(R.string.sync_running_hint)) {
            ToggleSwitch(true, label = stringResource(R.string.sync_running)) { if (!it) onOff() }
        }
    }
}

@Composable
private fun SyncRunning(snap: SyncSnapshot, onShowId: () -> Unit, onAdvanced: () -> Unit, onOff: () -> Unit) {
    PairedColumns(
        { extra ->
            SettingsGroup(stringResource(R.string.sync_group)) {
                StatusCard(snap)
                SettingsRow(stringResource(R.string.sync_running), stringResource(R.string.sync_running_hint)) {
                    ToggleSwitch(true, label = stringResource(R.string.sync_running)) { if (!it) onOff() }
                }
            }
            SettingsGroup(stringResource(R.string.sync_this_device), extra = extra) {
                SettingsRow(stringResource(R.string.sync_device_id), shortId(snap.myId)) {
                    SecondaryButton(stringResource(R.string.sync_show_qr), compact = true, onClick = onShowId)
                }
                SettingsRow(stringResource(R.string.sync_advanced), stringResource(R.string.sync_advanced_hint)) {
                    SecondaryButton(stringResource(R.string.sync_open), compact = true, onClick = onAdvanced)
                }
            }
        },
        { extra ->
            Waiting(snap)
            SettingsGroup(stringResource(R.string.sync_folders)) {
                if (snap.folders.isEmpty()) SettingsRow(stringResource(R.string.sync_no_folders), null) {}
                snap.folders.sortedByDescending { it.blocked }.forEach { FolderRow(it) }
                AddFolderRow(snap.devices)
            }
            SettingsGroup(stringResource(R.string.sync_devices), extra = extra) {
                if (snap.devices.isEmpty()) SettingsRow(stringResource(R.string.sync_no_devices), null) {}
                snap.devices.forEach { DeviceRow(it) }
            }
        },
    )
}

/** Two columns of settings groups that end at the same height; the shorter column's last group gets [extra] space. */
@Composable
internal fun PairedColumns(
    left: @Composable ColumnScope.(extra: Dp) -> Unit,
    right: @Composable ColumnScope.(extra: Dp) -> Unit,
) {
    if (LocalNarrowPane.current) {
        Column { left(0.dp); right(0.dp) }
        return
    }
    val density = LocalDensity.current
    var leftHeight by remember { mutableIntStateOf(0) }
    var rightHeight by remember { mutableIntStateOf(0) }
    val tallest = maxOf(leftHeight, rightHeight)
    val leftExtra = tallest - leftHeight
    val rightExtra = tallest - rightHeight
    Row(horizontalArrangement = Arrangement.spacedBy(20.dp), verticalAlignment = Alignment.Top, modifier = Modifier.fillMaxWidth()) {
        Column(Modifier.weight(1f).onSizeChanged { leftHeight = it.height - leftExtra }) { left(with(density) { leftExtra.toDp() }) }
        Column(Modifier.weight(1f).onSizeChanged { rightHeight = it.height - rightExtra }) { right(with(density) { rightExtra.toDp() }) }
    }
}

@Composable
private fun StatusCard(snap: SyncSnapshot) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val paused = Syncthing.userPaused || Syncthing.autoPause != null
    val (headline, tint) = when {
        paused -> stringResource(R.string.sync_status_paused) to colors.onSurfaceVariant
        snap.needsAttention -> stringResource(R.string.sync_status_attention) to AttentionAmber
        snap.syncing -> stringResource(R.string.sync_status_syncing) to pal.signal
        else -> stringResource(R.string.sync_status_up_to_date) to pal.good
    }
    val detail = when (Syncthing.autoPause) {
        Syncthing.AutoPause.WIFI -> stringResource(R.string.sync_paused_wifi)
        Syncthing.AutoPause.GAME -> stringResource(R.string.sync_paused_game)
        null -> if (snap.syncing && !paused) stringResource(R.string.sync_left, Formatter.formatShortFileSize(ctx, snap.needBytes))
            else stringResource(R.string.sync_summary, snap.folders.size, snap.devices.count { it.connected }, snap.devices.size)
    }
    Column(Modifier.fillMaxWidth().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            when {
                snap.syncing && !paused -> Icon(Icons.Outlined.Sync, null, tint = tint, modifier = Modifier.size(22.dp))
                snap.needsAttention && !paused -> Icon(Icons.Outlined.WarningAmber, null, tint = tint, modifier = Modifier.size(22.dp))
                else -> Box(Modifier.size(12.dp).clip(CircleShape).background(tint))
            }
            Text(headline, fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground, modifier = Modifier.weight(1f))
            if (snap.syncing && !paused) Text("${snap.percent}%", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = pal.signal)
        }
        if (snap.syncing && !paused) LinearProgressIndicator(
            progress = { snap.percent / 100f }, color = pal.signal, trackColor = pal.line, modifier = Modifier.fillMaxWidth().height(4.dp).clip(CircleShape),
        )
        Text(detail, fontSize = 12.5.sp, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Text("↓ ${rate(ctx, snap.inBytesPerSecond)}", fontSize = 13.sp, color = colors.onSurfaceVariant)
            Text("↑ ${rate(ctx, snap.outBytesPerSecond)}", fontSize = 13.sp, color = colors.onSurfaceVariant)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PrimaryButton(stringResource(R.string.sync_open_web_ui), compact = true, modifier = Modifier.weight(1f)) { openWebUi(ctx) }
            SecondaryButton(stringResource(if (Syncthing.userPaused) R.string.sync_resume else R.string.sync_pause), compact = true) {
                Syncthing.setPaused(ctx, !Syncthing.userPaused)
            }
        }
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
private fun Waiting(snap: SyncSnapshot) {
    if (snap.pendingFolders.isEmpty() && snap.pendingDevices.isEmpty()) return
    val ctx = LocalContext.current
    var accepting by remember { mutableStateOf<SyncPendingFolder?>(null) }
    accepting?.let { f ->
        SyncFolderPicker(stringResource(R.string.sync_accept_title, f.label), f.label, onDismiss = { accepting = null }) { dir ->
            Syncthing.addFolder(ctx, dir, f.label, listOf(f.deviceId), f.id) { ok -> if (!ok) addFailed(ctx) }
        }
    }
    SettingsGroup(stringResource(R.string.sync_waiting)) {
        snap.pendingDevices.forEach { name ->
            SettingsRow(stringResource(R.string.sync_pending_device, name), null, highlighted = true) {
                SecondaryButton(stringResource(R.string.sync_review), compact = true) { openWebUi(ctx) }
            }
        }
        snap.pendingFolders.forEach { f ->
            SettingsRow(stringResource(R.string.sync_pending_folder, f.offeredBy, f.label), stringResource(R.string.sync_pending_folder_hint), highlighted = true) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    SecondaryButton(stringResource(R.string.sync_review), compact = true) { openWebUi(ctx) }
                    PrimaryButton(stringResource(R.string.sync_accept), compact = true) { accepting = f }
                }
            }
        }
    }
}

@Composable
private fun AddFolderRow(devices: List<SyncDevice>) {
    val ctx = LocalContext.current
    var picking by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf<File?>(null) }
    if (picking) SyncFolderPicker(stringResource(R.string.sync_add_folder), null, onDismiss = { picking = false }) { picked = it }
    picked?.let { dir ->
        ShareFolderDialog(dir, devices, onDismiss = { picked = null }) { chosen ->
            Syncthing.addFolder(ctx, dir, dir.name, chosen) { ok -> if (!ok) addFailed(ctx) }
        }
    }
    SettingsRow(stringResource(R.string.sync_add_folder), stringResource(R.string.sync_add_folder_hint)) {
        SecondaryButton(stringResource(R.string.sync_choose), compact = true) { picking = true }
    }
}

@Composable
private fun ShareFolderDialog(folder: File, devices: List<SyncDevice>, onDismiss: () -> Unit, onAdd: (List<String>) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val chosen = remember { mutableStateListOf<String>() }
    AppDialog(shown, close, "syncShareFolder", wide = false, maxWidth = 520.dp) {
        DialogTitleRow(folder.name, onClose = close)
        Text(folder.path, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        if (devices.isEmpty()) {
            Text(stringResource(R.string.sync_share_none), fontSize = 13.5.sp, color = colors.onSurfaceVariant)
        } else {
            Text(stringResource(R.string.sync_share_with), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            devices.forEach { d ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().clip(Shape12).border(1.dp, pal.line, Shape12).background(colors.background).padding(end = 12.dp),
                ) {
                    Checkbox(d.id in chosen, { if (it) chosen.add(d.id) else chosen.remove(d.id) })
                    Text(d.name, fontSize = 14.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = close)
            PrimaryButton(stringResource(R.string.sync_add), compact = true) { onAdd(chosen.toList()); close() }
        }
    }
}

private fun addFailed(context: Context) {
    android.widget.Toast.makeText(context, R.string.sync_add_failed, android.widget.Toast.LENGTH_LONG).show()
}

@Composable
private fun FolderRow(f: SyncFolder) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    if (f.blocked) {
        Column(Modifier.fillMaxWidth().background(colors.error.copy(alpha = 0.06f)).padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Icon(Icons.Outlined.Block, null, tint = colors.error, modifier = Modifier.size(18.dp))
                Text(f.label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, modifier = Modifier.weight(1f))
                Text(stringResource(R.string.sync_folder_blocked), fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
            }
            Text(f.path, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(stringResource(R.string.sync_folder_blocked_hint), fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(top = 4.dp)) {
                SecondaryButton(stringResource(R.string.sync_remove_folder), compact = true) { Syncthing.removeFolder(f.id) }
                SecondaryButton(stringResource(R.string.sync_open_web_ui), compact = true) { openWebUi(ctx) }
            }
            Text(stringResource(R.string.sync_remove_folder_hint), fontSize = 12.sp, color = colors.onSurfaceVariant)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        return
    }
    val (state, tint) = when {
        f.paused -> stringResource(R.string.sync_folder_paused) to colors.onSurfaceVariant
        f.syncing -> stringResource(R.string.sync_folder_syncing, percent(f)) to pal.signal
        f.outOfSync -> stringResource(R.string.sync_folder_out_of_sync) to AttentionAmber
        f.state == "scanning" || f.state == "scan-waiting" -> stringResource(R.string.sync_folder_scanning) to colors.onSurfaceVariant
        else -> stringResource(R.string.sync_folder_up_to_date) to pal.good
    }
    val detail = when {
        f.errors > 0 -> stringResource(R.string.sync_folder_errors, f.errors)
        f.sharedWith.isEmpty() -> Formatter.formatShortFileSize(ctx, f.globalBytes)
        else -> stringResource(R.string.sync_folder_detail, Formatter.formatShortFileSize(ctx, f.globalBytes), f.sharedWith.joinToString(", "))
    }
    SettingsRow(f.label, detail) { StateLabel(state, tint) }
}

@Composable
private fun DeviceRow(d: SyncDevice) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val detail = when {
        d.connected -> d.address?.substringAfter("://")
        d.lastSeen != null -> stringResource(R.string.sync_last_seen, DateUtils.getRelativeTimeSpanString(d.lastSeen).toString())
        else -> null
    }
    SettingsRow(d.name, detail) {
        if (d.connected) StateLabel(stringResource(R.string.sync_connected), pal.good)
        else StateLabel(stringResource(R.string.sync_offline), colors.onSurfaceVariant)
    }
}

@Composable
private fun StateLabel(text: String, tint: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(7.dp)) {
        Box(Modifier.size(9.dp).clip(CircleShape).background(tint))
        Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = tint, maxLines = 1)
    }
}

@Composable
private fun DeviceIdDialog(id: String, onDismiss: () -> Unit) {
    val ctx = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    val qr = remember(id) { qrBitmap(id) }
    AppDialog(shown, close, "syncDeviceId", wide = true, maxWidth = 640.dp) {
        DialogTitleRow(stringResource(R.string.sync_device_id_title), onClose = close)
        Row(horizontalArrangement = Arrangement.spacedBy(22.dp), verticalAlignment = Alignment.CenterVertically) {
            Image(qr, stringResource(R.string.sync_device_id), filterQuality = FilterQuality.None,
                modifier = Modifier.size(208.dp).clip(Shape14).background(Color.White).padding(10.dp))
            Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.weight(1f)) {
                Text(id.chunked(32).joinToString("\n"), fontSize = 16.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace,
                    color = colors.onBackground, lineHeight = 26.sp)
                Text(stringResource(R.string.sync_device_id_hint), fontSize = 13.sp, color = colors.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.sync_copy), compact = true) {
                ctx.getSystemService(ClipboardManager::class.java)?.setPrimaryClip(ClipData.newPlainText("Syncthing device ID", id))
            }
            SecondaryButton(stringResource(R.string.sync_share), compact = true) {
                ctx.startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, id), null))
            }
            PrimaryButton(stringResource(R.string.sync_done), compact = true, onClick = close)
        }
    }
}

@Composable
private fun TurnOffDialog(onDismiss: () -> Unit, onTurnOff: (Boolean) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    var forget by remember { mutableStateOf(false) }
    AppDialog(shown, close, "syncTurnOff", wide = false, maxWidth = 520.dp) {
        DialogTitleRow(stringResource(R.string.sync_off_title), onClose = close)
        Text(stringResource(R.string.sync_off_body), fontSize = 13.5.sp, color = colors.onSurfaceVariant)
        Row(
            verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.fillMaxWidth().clip(Shape12).border(1.dp, pal.line, Shape12).background(colors.background).padding(end = 12.dp, top = 2.dp, bottom = 8.dp),
        ) {
            Checkbox(forget, { forget = it })
            Column(Modifier.padding(top = 12.dp)) {
                Text(stringResource(R.string.sync_off_forget), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text(stringResource(R.string.sync_off_forget_hint), fontSize = 12.5.sp, color = colors.onSurfaceVariant)
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = close)
            PrimaryButton(stringResource(R.string.sync_turn_off), compact = true) { onTurnOff(forget); close() }
        }
    }
}

internal fun openWebUi(context: Context) {
    context.startActivity(Intent(context, SyncthingWebActivity::class.java))
}

private fun shortId(id: String) = if (id.length > 16) "${id.take(7)}-…-${id.takeLast(7)}" else id

private fun rate(context: Context, bytesPerSecond: Long) = Formatter.formatShortFileSize(context, bytesPerSecond) + "/s"

private fun percent(f: SyncFolder): Int =
    if (f.globalBytes <= 0) 0 else (((f.globalBytes - f.needBytes).coerceAtLeast(0) * 100) / f.globalBytes).toInt().coerceIn(0, 99)

private fun qrBitmap(text: String): ImageBitmap {
    val matrix = com.google.zxing.qrcode.QRCodeWriter().encode(text, com.google.zxing.BarcodeFormat.QR_CODE, 0, 0)
    val bitmap = android.graphics.Bitmap.createBitmap(matrix.width, matrix.height, android.graphics.Bitmap.Config.ARGB_8888)
    for (x in 0 until matrix.width) for (y in 0 until matrix.height) {
        bitmap.setPixel(x, y, if (matrix[x, y]) android.graphics.Color.BLACK else android.graphics.Color.WHITE)
    }
    return bitmap.asImageBitmap()
}
