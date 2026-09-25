package com.droiddeck.launcher.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.runtime.DeckyManager

@Composable
fun DeckyPage(
    installed: String?, releases: List<DeckyManager.Release>, prerelease: Boolean, checking: Boolean, stableAvailable: Boolean,
    stage: String?, percent: Int, supervisor: Boolean, sessionRunning: Boolean,
    onChannel: (Boolean) -> Unit, onRefresh: () -> Unit, onInstall: (DeckyManager.Release) -> Unit,
    onUninstall: (Boolean) -> Unit, onSupervisor: (Boolean) -> Unit, onBack: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    var removeChoice by remember { mutableStateOf(false) }
    BackHandler(onBack = onBack)
    val host = rememberMenuHost()
    SettingsPage(host, "Decky Loader", onBack = onBack, eyebrow = "Setup", lede = "Manage the optional Decky service for the Steam session.") {
        SettingsGroup("Installed") {
            SettingsRow("PluginLoader", installed?.let { "Version $it" } ?: "Not installed") {
                SecondaryButton("Uninstall", enabled = installed != null && !sessionRunning && stage == null) { removeChoice = true }
            }
            SettingsRow("Start with Steam", "Runs PluginLoader for this session and stops it when Steam exits") {
                SecondaryButton(if (supervisor) "On" else "Off", enabled = installed != null && !sessionRunning) { onSupervisor(!supervisor) }
            }
        }
        SettingsGroup("Release channel") {
            SettingsRow("Channel", if (prerelease) "Prerelease builds" else "Stable builds") {
                Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    SecondaryButton("Stable", enabled = stableAvailable && prerelease) { onChannel(false) }
                    SecondaryButton("Prerelease", enabled = !prerelease) { onChannel(true) }
                }
            }
            SettingsRow("Releases", if (checking) "Checking GitHub…" else if (releases.isEmpty()) "No compatible release asset is available." else "Select a release to install or update.") {
                SecondaryButton("Refresh", enabled = !checking && stage == null) { onRefresh() }
            }
            releases.forEach { release ->
                SettingsRow(release.tag, "${release.asset} · ${"%.1f".format(release.size / 1_000_000.0)} MB") {
                    SecondaryButton(if (installed == release.tag) "Reinstall" else "Install", enabled = !sessionRunning && stage == null) { onInstall(release) }
                }
            }
        }
        if (stage != null) Column(Modifier.fillMaxWidth().padding(12.dp)) {
            Text(if (percent >= 0) "$stage · $percent%" else stage, fontSize = 12.sp, color = colors.onSurfaceVariant)
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.fillMaxWidth().padding(top = 6.dp))
        }
        if (sessionRunning) Text("Stop the active session before changing Decky files or its supervisor setting.", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 10.dp))
    }
    if (removeChoice) AlertDialog(
        onDismissRequest = { removeChoice = false },
        title = { Text("Uninstall Decky Loader?") },
        text = { Text("Keep homebrew plugins and settings, or remove them with the loader.") },
        confirmButton = { TextButton(onClick = { removeChoice = false; onUninstall(false) }) { Text("Keep data") } },
        dismissButton = { Row { TextButton(onClick = { removeChoice = false; onUninstall(true) }) { Text("Wipe data") }; TextButton(onClick = { removeChoice = false }) { Text("Cancel") } } },
    )
}
