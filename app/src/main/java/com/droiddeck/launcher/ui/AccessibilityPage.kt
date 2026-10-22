package com.droiddeck.launcher.ui

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Download
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R

/**
 * The Accessibility tab: what Orca is, installing it into the runtime, opening its settings, and
 * the switch that keeps it on for every session that enters the runtime (Steam, the desktop, a
 * program). Installed and enabled follow the page the moment the app hears the installer finish,
 * so the switch is there as soon as an install can be turned on.
 */
@Composable
internal fun AccessibilityPage(s: FrontEndState, a: FrontEndActions) {
    val context = LocalContext.current
    Rise(0) {
        PageHeader(stringResource(R.string.drawer_accessibility)) {
            if (s.orcaInstalled) Chip(stringResource(R.string.accessibility_installed), ok = true)
            else Chip(stringResource(R.string.accessibility_not_installed), ok = false)
        }
    }
    Rise(2) { Note(stringResource(R.string.accessibility_about)) }
    Rise(2) { Note(stringResource(R.string.accessibility_voice_note)) }
    if (!s.orcaInstalled) {
        Rise(3) {
            Actions {
                PrimaryButton(
                    stringResource(R.string.accessibility_install),
                    enabled = !s.orcaBusy && !s.sessionRunning && s.installed != null,
                    main = true,
                    icon = Icons.Outlined.Download,
                    onClick = a.onOrcaInstall,
                )
            }
        }
        if (s.orcaBusy) Rise(4) {
            Note(s.orcaStage?.let { context.getString(R.string.accessibility_installing_stage, it) }
                ?: stringResource(R.string.accessibility_installing))
        }
    } else {
        Rise(3) {
            Actions {
                SecondaryButton(stringResource(R.string.accessibility_settings), enabled = !s.orcaBusy, onClick = a.onOrcaSettings)
            }
        }
        Rise(4) {
            SettingsRow(stringResource(R.string.accessibility_enable), stringResource(R.string.accessibility_enable_hint)) {
                ToggleSwitch(s.orcaEnabled, enabled = !s.orcaBusy, label = stringResource(R.string.accessibility_enable)) { a.onOrcaToggle(it) }
            }
        }
    }
    if (s.orcaError != null) Rise(5) { Note(s.orcaError) }
}
