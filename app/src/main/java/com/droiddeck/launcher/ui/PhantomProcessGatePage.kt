package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.core.WirelessAdbPairingService
import com.droiddeck.launcher.core.WirelessAdbPairingService.Stage

@Composable
fun PhantomProcessGatePage(
    status: PhantomProcessStatus,
    onDismiss: () -> Unit,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onEnterAddressManually: () -> Unit,
    onCopyCommand: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onAlreadyApplied: () -> Unit,
) {
    BackHandler(onBack = onDismiss)
    val context = LocalContext.current
    val stage by WirelessAdbPairingService.stage.collectAsState()
    val environment by produceState(GateEnvironment.read(context)) {
        while (true) {
            value = GateEnvironment.read(context)
            kotlinx.coroutines.delay(2_000)
        }
    }
    val hasToggle = PhantomProcessLimit.hasDeveloperToggle()

    BoxWithConstraints(Modifier.fillMaxWidth()) {
        val compact = maxWidth < 620.dp && maxHeight < 500.dp
        SettingsPage(
            host = rememberMenuHost(),
            title = stringResource(R.string.gate_title),
            eyebrow = stringResource(R.string.gate_eyebrow),
            lede = if (compact) null else stringResource(R.string.gate_lede),
            onBack = onDismiss,
            scrollContent = true,
            compactLayout = compact,
        ) {
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val wide = maxWidth >= 620.dp
                val fix: @Composable ColumnScope.() -> Unit = {
                    FixGroup(hasToggle, stage, environment, compact, onOpenDeveloperOptions, onFixWithWirelessDebugging,
                        onOpenNotificationSettings, onCancel = { WirelessAdbPairingService.cancel(context) })
                }
                val side: @Composable ColumnScope.() -> Unit = {
                    StatusGroup(status, compact, onAlreadyApplied)
                    OtherWays(hasToggle, compact, onOpenDeveloperOptions, onFixWithWirelessDebugging, onEnterAddressManually, onCopyCommand, onAlreadyApplied)
                }
                if (wide) {
                    Row(horizontalArrangement = Arrangement.spacedBy(14.dp), modifier = Modifier.widthIn(max = 900.dp).fillMaxWidth()) {
                        Column(Modifier.weight(1.2f), content = fix)
                        Column(Modifier.weight(1f), content = side)
                    }
                } else {
                    Column(Modifier.widthIn(max = 600.dp).fillMaxWidth()) {
                        fix()
                        side()
                    }
                }
            }
        }
    }
}

private data class GateEnvironment(val onWifi: Boolean, val notifications: Boolean) {
    companion object {
        fun read(context: Context): GateEnvironment {
            val connectivity = context.getSystemService(ConnectivityManager::class.java)
            val wifi = runCatching {
                connectivity?.getNetworkCapabilities(connectivity.activeNetwork)
                    ?.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) == true
            }.getOrDefault(true)
            return GateEnvironment(wifi, WirelessAdbPairingService.notificationsEnabled(context))
        }
    }
}

@Composable
private fun FixGroup(
    hasToggle: Boolean,
    stage: Stage,
    environment: GateEnvironment,
    compact: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onOpenNotificationSettings: () -> Unit,
    onCancel: () -> Unit,
) {
    val pairing = stage is Stage.Waiting || stage is Stage.CodeNeeded || stage is Stage.Working
    SettingsGroup(if (hasToggle) stringResource(R.string.setup_dev_options) else stringResource(R.string.gate_fix_for_me), compact = compact) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            when {
                pairing -> PairingProgress(stage, onCancel)
                hasToggle -> {
                    Body(stringResource(R.string.gate_toggle_steps))
                    PrimaryButton(stringResource(R.string.gate_open_dev), compact = compact, onClick = onOpenDeveloperOptions)
                }
                else -> {
                    Body(stringResource(R.string.gate_no_toggle))
                    (stage as? Stage.Failed)?.let { Body(it.error, error = true) }
                    PrimaryButton(
                        if (stage is Stage.Failed) stringResource(R.string.store_try_again) else stringResource(R.string.gate_fix_for_me),
                        compact = compact,
                        enabled = environment.onWifi && environment.notifications,
                        onClick = onFixWithWirelessDebugging,
                    )
                }
            }
            if (!hasToggle && !pairing) {
                if (!environment.onWifi) {
                    Body(stringResource(R.string.gate_needs_wifi), warn = true)
                }
                if (!environment.notifications) {
                    Body(stringResource(R.string.gate_needs_notifications), warn = true)
                    SecondaryButton(stringResource(R.string.gate_allow_notifications), compact = compact, onClick = onOpenNotificationSettings)
                }
            }
        }
    }
}

@Composable
private fun PairingProgress(stage: Stage, onCancel: () -> Unit) {
    val steps = listOf(
        stringResource(R.string.gate_step1),
        stringResource(R.string.gate_step2),
        stringResource(R.string.gate_step3),
    )
    val active = when (stage) {
        Stage.Waiting -> 1
        is Stage.CodeNeeded -> 2
        else -> 3
    }
    steps.forEachIndexed { index, step ->
        val done = index < active
        val current = index == active
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(if (done) "✓" else "${index + 1}", fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                color = if (done) LocalPalette.current.good else MaterialTheme.colorScheme.onSurfaceVariant)
            Text(step, style = MaterialTheme.typography.bodySmall,
                color = if (current) MaterialTheme.colorScheme.onBackground else MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        CircularProgressIndicator(modifier = Modifier.size(16.dp), strokeWidth = 2.dp)
        Text(
            when (stage) {
                Stage.Waiting -> stringResource(R.string.gate_waiting)
                is Stage.CodeNeeded -> stage.error ?: stringResource(R.string.gate_code_needed)
                is Stage.Working -> stage.step
                else -> ""
            },
            style = MaterialTheme.typography.bodySmall,
            color = if (stage is Stage.CodeNeeded && stage.error != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        SecondaryButton(stringResource(R.string.common_cancel), compact = true, onClick = onCancel)
    }
}

@Composable
private fun StatusGroup(status: PhantomProcessStatus, compact: Boolean, onAlreadyApplied: () -> Unit) {
    SettingsGroup(stringResource(R.string.gate_status), compact = compact) {
        Column(Modifier.fillMaxWidth().padding(if (compact) 10.dp else 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(PhantomProcessLimit.title(LocalContext.current, status), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onBackground)
            Body(stringResource(R.string.gate_polling))
            if (status == PhantomProcessStatus.UNREADABLE) {
                Body(stringResource(R.string.gate_unreadable_note))
                SecondaryButton(stringResource(R.string.gate_already_applied), compact = true, onClick = onAlreadyApplied)
            }
        }
    }
}

@Composable
private fun OtherWays(
    hasToggle: Boolean,
    compact: Boolean,
    onOpenDeveloperOptions: () -> Unit,
    onFixWithWirelessDebugging: () -> Unit,
    onEnterAddressManually: () -> Unit,
    onCopyCommand: () -> Unit,
    onAlreadyApplied: () -> Unit,
) {
    var open by rememberSaveable { mutableStateOf(false) }
    SettingsGroup(stringResource(R.string.gate_other_ways), compact = compact) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp),
        ) {
            Body(if (hasToggle) stringResource(R.string.gate_other_toggle) else stringResource(R.string.gate_other_no_toggle), modifier = Modifier.weight(1f))
            Text(if (open) "▴" else "▾", fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (open) Column(
            modifier = Modifier.fillMaxWidth().padding(start = 14.dp, end = 14.dp, bottom = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (hasToggle) {
                SecondaryButton(stringResource(R.string.gate_use_wireless_instead), compact = true, onClick = onFixWithWirelessDebugging)
            } else {
                Body(stringResource(R.string.gate_some_roms))
                SecondaryButton(stringResource(R.string.gate_open_dev), compact = true, onClick = onOpenDeveloperOptions)
            }
            Body(stringResource(R.string.gate_manual_hint))
            SecondaryButton(stringResource(R.string.gate_enter_manually), compact = true, onClick = onEnterAddressManually)
            Body(stringResource(R.string.gate_from_computer))
            Text(
                PhantomProcessLimit.adbCommand(),
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace, fontSize = 11.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SecondaryButton(stringResource(R.string.gate_copy_command), compact = true, onClick = onCopyCommand)
                SecondaryButton(stringResource(R.string.gate_already_applied), compact = true, onClick = onAlreadyApplied)
            }
        }
    }
}

@Composable
private fun Body(text: String, modifier: Modifier = Modifier, error: Boolean = false, warn: Boolean = false) {
    Text(
        text,
        modifier = modifier,
        style = MaterialTheme.typography.bodySmall,
        color = when {
            error -> MaterialTheme.colorScheme.error
            warn -> androidx.compose.ui.graphics.Color(0xFFFFB86B)
            else -> MaterialTheme.colorScheme.onSurfaceVariant
        },
    )
}

@Composable
fun DeveloperDisplayChoiceDialog(
    displays: List<Pair<Int, String>>,
    onMainScreen: () -> Unit,
    onSecondaryScreen: (Int) -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.gate_open_dev)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp), modifier = Modifier.fillMaxWidth()) {
                Text(stringResource(R.string.gate_choose_display))
                TextButton(modifier = Modifier.fillMaxWidth(), onClick = onMainScreen) { Text(stringResource(R.string.gate_main_screen)) }
                displays.forEach { (id, label) ->
                    TextButton(modifier = Modifier.fillMaxWidth(), onClick = { onSecondaryScreen(id) }) { Text(label) }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
