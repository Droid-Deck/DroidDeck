package com.droiddeck.launcher.ui

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
        title = { Text("Launch ${app.label}") },
        text = {
            Text(
                secondaryDisplay?.let { "Choose a screen. Secondary: ${it.label}." }
                    ?: "The secondary display is no longer available.",
            )
        },
        confirmButton = { TextButton(onClick = onPrimary) { Text("Primary screen") } },
        dismissButton = {
            TextButton(onClick = onSecondary, enabled = secondaryDisplay != null) {
                Text("Secondary screen")
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
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

@Composable
fun CreditsDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Credits") },
        text = {
            Column(modifier = Modifier.verticalScroll(rememberScrollState())) {
                Text("The412Banner: app, compositor, runtime, and Steam session.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text("maxjivi05: gamescope runtime and controller support, based on WinNative.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text("LSFG frame generation: from the work of Camille LaVey / the Eden emulator project, ported to WinNative and DroidDeck by maxjivi05.", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(10.dp))
                Text(
                    "GPL-3.0. Steam, Steam Deck, and Proton are Valve trademarks. Not affiliated with Valve. Third-party software remains under its authors' licenses.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("OK") } },
    )
}
