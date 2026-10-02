package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import androidx.compose.ui.res.stringResource
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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
        confirmButton = { TextButton(onClick = onPrimary) { Text(stringResource(R.string.main_primary)) } },
        dismissButton = {
            TextButton(onClick = onSecondary, enabled = secondaryDisplay != null) {
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
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
