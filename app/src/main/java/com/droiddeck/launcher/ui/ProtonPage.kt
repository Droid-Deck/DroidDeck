package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R

class ProtonRow(val id: String, val name: String, val installed: String?, val queued: Boolean)

@Composable
fun ProtonPage(
    rows: List<ProtonRow>,
    busyId: String?,
    stage: String?,
    percent: Int,
    runtimeReady: Boolean,
    sessionRunning: Boolean,
    onInstall: (String) -> Unit,
    onCancel: (String) -> Unit,
    onRemove: (String) -> Unit,
    onBack: () -> Unit,
) {
    val host = rememberMenuHost()
    val colors = MaterialTheme.colorScheme
    SettingsPage(
        host,
        title = stringResource(R.string.setup_tool_protons),
        eyebrow = stringResource(R.string.setup_title),
        lede = stringResource(R.string.proton_lede),
        onBack = onBack,
    ) {
        if (!runtimeReady) Text(
            stringResource(R.string.content_needs_runtime),
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        if (sessionRunning) Text(
            stringResource(R.string.proton_stop_first),
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        val compatible = stringResource(R.string.steam_compat_label)
        Text(
            stringResource(R.string.proton_compatible_note, compatible),
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 8.dp, bottom = 6.dp),
        )
        SettingsGroup(stringResource(R.string.proton_available)) {
            for (row in rows) {
                SettingsRow(
                    row.name,
                    when {
                        row.installed != null -> stringResource(R.string.proton_installed_compatible, row.installed, compatible)
                        row.queued -> stringResource(R.string.proton_queued)
                        else -> stringResource(R.string.proton_not_installed)
                    },
                ) {
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        when {
                            row.installed != null -> SecondaryButton(stringResource(R.string.store_remove), enabled = busyId == null && runtimeReady && !sessionRunning) { onRemove(row.id) }
                            busyId != null -> SecondaryButton(if (busyId == row.id) stringResource(R.string.store_installing) else stringResource(R.string.store_install), enabled = false) {}
                            else -> SecondaryButton(stringResource(R.string.proton_install_now), enabled = runtimeReady && !sessionRunning) { onInstall(row.id) }
                        }
                        if (row.queued && row.installed == null && busyId == null) {
                            SecondaryButton(stringResource(R.string.proton_cancel_queued), enabled = !sessionRunning) { onCancel(row.id) }
                        }
                    }
                }
                if (busyId == row.id) Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                    Text(
                        if (stage != null && percent >= 0) stringResource(R.string.store_busy_percent_short, stage, percent) else stage ?: stringResource(R.string.store_starting),
                        fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp),
                    )
                    if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                }
            }
        }
    }
}
