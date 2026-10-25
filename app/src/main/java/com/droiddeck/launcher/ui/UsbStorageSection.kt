package com.droiddeck.launcher.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.session.GameStorage
import com.droiddeck.launcher.session.UsbRootMount

/** The USB drive in Setup > Storage. */
class UsbUi(
    val access: GameStorage.UsbAccess? = null,
    /** Free space on the drive in use without root; null = none. */
    val free: String? = null,
    /** Only when su exists. */
    val root: UsbRootUi? = null,
)

class UsbRootUi(
    val enabled: Boolean,
    val drives: List<UsbRootMount.Drive>,
    val drive: String,
    val mounted: Boolean,
    /** The last mount/eject error, or null. */
    val status: String?,
)

class UsbActions(
    val onRefresh: () -> Unit = {},
    val onAccess: () -> Unit = {},
    val onRestart: () -> Unit = {},
    val onRoot: (Boolean) -> Unit = {},
    val onDrive: (String) -> Unit = {},
    val onMount: () -> Unit = {},
    val onEject: () -> Unit = {},
    val onPrepare: () -> Unit = {},
)

@Composable
internal fun UsbSection(host: MenuHost, usb: UsbUi, a: UsbActions, sessionRunning: Boolean) {
    LaunchedEffect(Unit) { a.onRefresh() }
    if (usb.access == null && usb.free == null && usb.root == null) return
    SettingsGroup(stringResource(R.string.usb_root_volume)) {
        when (usb.access) {
            GameStorage.UsbAccess.PERMISSION -> SettingsRow(stringResource(R.string.usb_access_title), stringResource(R.string.usb_access_hint)) {
                SecondaryButton(stringResource(R.string.usb_access_allow), onClick = a.onAccess)
            }
            GameStorage.UsbAccess.RESTART -> SettingsRow(stringResource(R.string.usb_access_title), stringResource(R.string.usb_access_restart_hint)) {
                SecondaryButton(stringResource(R.string.steamvr_recovery_restart), enabled = !sessionRunning, onClick = a.onRestart)
            }
            null -> {}
        }
        usb.free?.let { SettingsRow(stringResource(R.string.usb_root_mounted), stringResource(R.string.stores_target_free, it)) {} }
        usb.root?.let { RootRows(host, it, a, sessionRunning) }
    }
}

@Composable
private fun RootRows(host: MenuHost, usb: UsbRootUi, a: UsbActions, sessionRunning: Boolean) {
    ToggleRow(host, "usb-root", stringResource(R.string.usb_root_title), usb.status ?: stringResource(R.string.usb_root_hint), usb.enabled, onChange = a.onRoot)
    // Still mounted after turning off (busy, or a session holds it): keep Eject reachable.
    if (!usb.enabled && !usb.mounted) return
    val chosen = usb.drives.firstOrNull { it.uuid == usb.drive }
    val unnamed = stringResource(R.string.usb_root_unnamed)
    val name: (UsbRootMount.Drive) -> String = {
        val label = it.label.ifEmpty { if (it.size > 0) String.format(unnamed, FileUtils.sizeToString(it.size)) else it.uuid }
        "$label (${it.type})"
    }
    val driveOpen = host.open == "usb-drive"
    SettingsRow(stringResource(R.string.usb_root_volume), null, highlighted = driveOpen) {
        Box {
            val chip = chosen?.let(name) ?: stringResource(if (usb.drives.isEmpty()) R.string.usb_root_no_drive else R.string.usb_root_choose)
            ValueChip(chip, driveOpen, enabled = usb.drives.isNotEmpty()) {
                host.open = if (driveOpen) null else "usb-drive"
            }
            AnchoredMenu(driveOpen, onDismiss = { if (host.open == "usb-drive") host.open = null }, title = stringResource(R.string.usb_root_volume)) { firstItemFocus ->
                usb.drives.forEachIndexed { index, d ->
                    MenuItem(name(d), checked = d.uuid == usb.drive, focusRequester = if (index == 0) firstItemFocus else null) {
                        a.onDrive(d.uuid)
                        host.open = null
                    }
                }
            }
        }
    }
    if (chosen == null) return
    val ext4 = !chosen.fat
    val hint = when {
        usb.mounted && sessionRunning -> stringResource(R.string.usb_root_eject_running)
        !usb.mounted && ext4 -> stringResource(R.string.usb_root_prepare_hint)
        else -> null
    }
    SettingsRow(stringResource(if (usb.mounted) R.string.usb_root_mounted else R.string.usb_root_unmounted), hint) {
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            if (usb.mounted) SecondaryButton(stringResource(R.string.usb_root_eject), enabled = !sessionRunning, onClick = a.onEject)
            else {
                SecondaryButton(stringResource(R.string.usb_root_mount), onClick = a.onMount)
                if (ext4) ConfirmButton(stringResource(R.string.usb_root_prepare), stringResource(R.string.usb_root_prepare_confirm), onConfirm = a.onPrepare)
            }
        }
    }
}
