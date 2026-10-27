package com.droiddeck.launcher.ui

import androidx.compose.foundation.shape.CircleShape
import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import com.droiddeck.launcher.ui.GlideAlertDialog as AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.input.SecondScreenDisplay

@Composable
fun ChooseAppDisplayDialog(
    app: HomeApp.LaunchableApp,
    secondaryDisplay: SecondScreenDisplay?,
    onPrimary: () -> Unit,
    onSecondary: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.main_launch_named, app.label)) },
        text = {
            Text(
                secondaryDisplay?.let { stringResource(R.string.main_choose_screen, it.label) }
                    ?: stringResource(R.string.main_secondary_gone),
            )
        },
        confirmButton = { TextButton(onClick = onPrimary, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.main_primary)) } },
        dismissButton = {
            TextButton(onClick = onSecondary, enabled = secondaryDisplay != null, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.main_secondary))
            }
        },
    )
}

@Composable
fun ConfirmDialog(title: String, text: String, confirm: String, onConfirm: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }, modifier = Modifier.focusGlide(CircleShape)) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
}

@Composable
fun RuntimeActionsDialog(onRepair: () -> Unit, onRemove: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.runtime_actions_title)) },
        text = { Text(stringResource(R.string.runtime_actions_text)) },
        confirmButton = { TextButton(onClick = onRepair, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.runtime_repair)) } },
        dismissButton = {
            Row {
                TextButton(onClick = onRemove, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.store_remove)) }
                TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@Composable
fun ResetSettingsDialog(onConfirm: (gameProfiles: Boolean) -> Unit, onDismiss: () -> Unit) {
    var gameProfiles by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.setup_reset_settings_title)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.setup_reset_settings_body))
                Row(
                    Modifier.fillMaxWidth().focusGlide().toggleable(gameProfiles, role = Role.Checkbox) { gameProfiles = it },
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Checkbox(checked = gameProfiles, onCheckedChange = null)
                    Spacer(Modifier.width(8.dp))
                    Text(stringResource(R.string.setup_reset_settings_profiles), style = MaterialTheme.typography.bodyMedium)
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onDismiss(); onConfirm(gameProfiles) }, modifier = Modifier.focusGlide(CircleShape)) {
                Text(stringResource(R.string.setup_reset_settings_button), color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onDismiss, modifier = Modifier.focusGlide(CircleShape)) { Text(stringResource(R.string.common_cancel)) } },
    )
}
