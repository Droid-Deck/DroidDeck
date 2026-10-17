package com.droiddeck.launcher.ui

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.sync.SyncPrefs
import com.droiddeck.launcher.sync.Syncthing
import java.io.File

@Composable
internal fun SyncAdvancedPage(host: MenuHost, onBack: () -> Unit) {
    val ctx = LocalContext.current
    var runMode by remember { mutableStateOf(SyncPrefs.runMode(ctx)) }
    var wifiOnly by remember { mutableStateOf(SyncPrefs.wifiOnly(ctx)) }
    var pauseInGames by remember { mutableStateOf(SyncPrefs.pauseInGames(ctx)) }
    var confirmReset by rememberSaveable { mutableStateOf(false) }
    if (confirmReset) ResetDialog(onDismiss = { confirmReset = false }) { Syncthing.reset(ctx) }
    val ports = Syncthing.ports
    SettingsPage(host, stringResource(R.string.sync_advanced), onBack, eyebrow = stringResource(R.string.setup_tab_sync)) {
        PairedColumns(
            { extra ->
                SettingsGroup(stringResource(R.string.sync_ports), extra = extra) {
                    PortRow(stringResource(R.string.sync_port_gui), ports?.let { "127.0.0.1:${it.gui}" })
                    PortRow(stringResource(R.string.sync_port_sync), ports?.sync?.toString())
                    PortRow(stringResource(R.string.sync_port_discovery), ports?.discovery?.toString())
                    ActionRow(stringResource(R.string.sync_repick), stringResource(R.string.sync_repick_hint), stringResource(R.string.sync_repick_button), onClick = { Syncthing.restart() })
                }
            },
            { extra ->
                SettingsGroup(stringResource(R.string.sync_running_group), extra = extra) {
                    ChoiceRow(
                        host, "sync-run", stringResource(R.string.sync_run), null,
                        listOf(SyncPrefs.RUN_WHILE_OPEN to stringResource(R.string.sync_run_open), SyncPrefs.RUN_BACKGROUND to stringResource(R.string.sync_run_background)),
                        runMode, stacked = true,
                    ) { SyncPrefs.setRunMode(ctx, it); runMode = it; Syncthing.settingsChanged() }
                    ToggleRow(host, "sync-wifi", stringResource(R.string.sync_wifi_only), null, wifiOnly) {
                        SyncPrefs.setWifiOnly(ctx, it); wifiOnly = it; Syncthing.settingsChanged()
                    }
                    ToggleRow(host, "sync-games", stringResource(R.string.sync_pause_games), null, pauseInGames) {
                        SyncPrefs.setPauseInGames(ctx, it); pauseInGames = it; Syncthing.settingsChanged()
                    }
                    ActionRow(stringResource(R.string.sync_logs), stringResource(R.string.sync_logs_hint), stringResource(R.string.sync_logs_share), onClick = { shareLog(ctx) })
                }
            },
        )
        SettingsGroup(stringResource(R.string.sync_reset_group)) {
            SettingsRow(stringResource(R.string.sync_api_key), Syncthing.apiKeyTail?.let { "••••••••••••••••••••$it" }) {
                SecondaryButton(stringResource(R.string.sync_regenerate), enabled = Syncthing.phase == Syncthing.Phase.RUNNING, compact = true) { Syncthing.regenerateApiKey() }
            }
            ActionRow(stringResource(R.string.sync_reset), stringResource(R.string.sync_reset_hint), stringResource(R.string.sync_reset_button), onClick = { confirmReset = true })
        }
    }
}

@Composable
private fun PortRow(label: String, value: String?) {
    val colors = MaterialTheme.colorScheme
    SettingsRow(label, null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(value ?: "—", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, fontFamily = FontFamily.Monospace, color = colors.onBackground)
            Text(stringResource(R.string.common_auto).uppercase(), fontSize = 11.sp, fontWeight = FontWeight.Bold, color = colors.onSurfaceVariant)
        }
    }
}

@Composable
private fun ResetDialog(onDismiss: () -> Unit, onReset: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val shown = rememberShown(onDismiss)
    val close = { shown.targetState = false }
    AppDialog(shown, close, "syncReset", wide = false, maxWidth = 480.dp) {
        DialogTitleRow(stringResource(R.string.sync_reset_confirm_title), onClose = close)
        Text(stringResource(R.string.sync_reset_hint), fontSize = 13.5.sp, color = colors.onSurfaceVariant)
        Row(horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End), modifier = Modifier.fillMaxWidth()) {
            SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = close)
            PrimaryButton(stringResource(R.string.sync_reset_button), compact = true) { onReset(); close() }
        }
    }
}

private fun shareLog(context: Context) {
    val log = Syncthing.logFile(context).takeIf { it.isFile } ?: return
    val out = File(File(context.cacheDir, "share").apply { mkdirs() }, "syncthing-log.txt")
    log.copyTo(out, overwrite = true)
    val uri = FileProvider.getUriForFile(context, context.packageName + ".logs", out)
    val send = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    send.clipData = ClipData.newRawUri(out.name, uri)
    context.startActivity(Intent.createChooser(send, context.getString(R.string.logshare_chooser)).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
}
