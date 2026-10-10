package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.runtime.DeckyManager
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.SessionService

/** [tag] is shown beside the name: [BUNDLED], [DOWNLOADED], [IMPORTED], or "" for Auto / Runtime default. */
class DriverRow(val id: String, val name: String, val detail: String, val removable: Boolean, val tag: String = "") {
    companion object {
        const val BUNDLED = "BUNDLED"
        const val DOWNLOADED = "DOWNLOADED"
        const val IMPORTED = "IMPORTED"
        /** One half of an Android + Linux bundle: picked and deleted together with the other. */
        const val BUNDLE = "ANDROID + LINUX"
    }
}

/** A [DriverRow.tag] as shown, in the app's language. */
@Composable
internal fun driverTagLabel(tag: String): String = when (tag) {
    DriverRow.BUNDLED -> stringResource(R.string.driver_tag_bundled)
    DriverRow.DOWNLOADED -> stringResource(R.string.driver_tag_downloaded)
    DriverRow.IMPORTED -> stringResource(R.string.driver_tag_imported)
    DriverRow.BUNDLE -> stringResource(R.string.driver_tag_bundle)
    else -> tag
}

/** A release driver (Banners-Turnip, WinNative) that is not installed yet; [key] is its asset name. */
class DownloadRow(val key: String, val label: String, val detail: String, val progress: Int? = null)

class ModeSettings(
    val mode: String,
    val resolution: String,
    val panelSize: Pair<Int, Int> = 1280 to 720,
    val hdr: Boolean,
    val hdrReason: String?,
    /** The GPU drivers in use, as the row that opens them on the Components page says it. */
    /** The drivers line; null shows Auto. */
    val gpuDrivers: String? = null,
    /** Frames per second the session is capped at; 0 = none. */
    val fpsLimit: Int = 0,
    val upscaler: Int = 0,
    val upscaleSharpness: Int = 75,
    val touchMode: String,
    val suspendPolicy: String,
    val steamDownloadsInBackground: Boolean = false,
    val pipSupported: Boolean = false,
    val pipAutoEnter: Boolean = false,
    /** Steam only. */
    val oscMode: String?,
    /** Steam only: whether single and double Back actions are swapped. */
    val backActionsInverted: Boolean = false,
    val directAudio: Boolean?,
    val mic: Boolean?,
    val gameStorage: String? = null,
    val storageOptions: List<com.droiddeck.launcher.session.GameStorage.Option> = emptyList(),
    /** Steam only: record allocation and sampled storage timings in the next session's Share logs. */
    val storageDiagnostics: Boolean = false,
    val fexPreset: String? = null,
    /** Steam only: Force SSBS for Proton games (SessionPrefs.forceSsbs). */
    val forceSsbs: Boolean = false,
    /** Steam only: SessionPrefs.SYNC_* chosen for Proton games; null outside Steam. */
    val syncBackend: String? = null,
    val stretch16x9: Boolean? = null,
    /** Steam only: the client branch forced on the command line. */
    val steamChannel: String? = null,
    /** Steam only: enable the SteamOS client interface and its performance controls. */
    val steamDeckMode: Boolean = false,
    val steamRepairQueued: Boolean = false,
    /** Steam, Deck mode: the QAM's performance overlay (mangoapp) is started. */
    val mangoapp: Boolean = true,
    /** Steam only: what the pad is to the client (SessionPrefs.CONTROLLER_*); null outside Steam. */
    val steamController: String? = null,
    /** Unfolded controls (Steam only; null hides the row). */
    val unfoldedControls: Boolean? = null,
    /** Steam only: start a Steam session when DroidDeck opens. */
    val runSteamAtStartup: Boolean = false,
    /** Null outside Steam; the saved choice is separate from Android's access and device switch. */
    val wifiDiscovery: Boolean? = null,
    val wifiDiscoveryPermission: Boolean = false,
    val wifiDiscoveryLocation: Boolean = false,
    val wifiDiscoveryAsked: Boolean = false,
    val wifiDiscoveryBlocked: Boolean = false,
    /** Latest Banners-Turnip release: what each driver menu offers to download, and the refresh line. */
    /** Steam only: Decky Loader is managed from the Steam session settings. */
    val deckyInstalled: String? = null,
    val deckyLatestRelease: DeckyManager.Release? = null,
    val deckyChecking: Boolean = false,
    val deckyStage: String? = null,
    val deckyPercent: Int = -1,
    /** The stage is the Decky plugin's binary download. */
    val deckyDownloadingBinary: Boolean = false,
    val deckyEnabled: Boolean = false,
    val deckySessionRunning: Boolean = false,
)

class ModeSettingsActions(
    val onResolution: (String) -> Unit,
    val onHdr: (Boolean) -> Unit,
    /** Opens the GPU drivers on the Components page: they are shared by every session. */
    val onGpuDrivers: () -> Unit = {},
    val onFpsLimit: (Int) -> Unit = {},
    val onUpscaler: (Int) -> Unit = {},
    val onUpscaleSharpness: (Int) -> Unit = {},
    val onTouch: (String) -> Unit,
    val onSuspendPolicy: (String) -> Unit,
    val onSteamDownloadsInBackground: (Boolean) -> Unit = {},
    val onPipAutoEnter: (Boolean) -> Unit = {},
    val onOsc: (String) -> Unit,
    val onBackActionsInverted: (Boolean) -> Unit = {},
    val onDirectAudio: (Boolean) -> Unit,
    val onMic: (Boolean) -> Unit,
    val onGameStorage: (path: String, label: String) -> Unit = { _, _ -> },
    val onPickGameStorageFolder: () -> Unit = {},
    val onStorageDiagnostics: (Boolean) -> Unit = {},
    val onFexPreset: (String) -> Unit = {},
    val onForceSsbs: (Boolean) -> Unit = {},
    val onSyncBackend: (String) -> Unit = {},
    val onStretch16x9: (Boolean) -> Unit = {},
    val onSteamChannel: (String) -> Unit = {},
    val onSteamRepair: () -> Unit = {},
    val onSteamDeckMode: (Boolean) -> Unit = {},
    val onMangoapp: (Boolean) -> Unit = {},
    val onSteamController: (String) -> Unit = {},
    val onUnfoldedControls: (Boolean) -> Unit = {},
    val onRunSteamAtStartup: (Boolean) -> Unit = {},
    val onWifiDiscovery: (Boolean) -> Unit = {},
    val onWifiDiscoverySettings: () -> Unit = {},
    val onDeckyInstall: (DeckyManager.Release) -> Unit = {},
    val onDeckyCheck: () -> Unit = {},
    val onDeckyEnabled: (Boolean) -> Unit = {},
    val onDeckyUninstall: () -> Unit = {},
    val onPickDeckyPluginZip: () -> Unit = {},
    val onDismiss: () -> Unit,
)

/**
 * The chips of a mode's lifted sheet, in order: Steam's six; the desktop's Display, Controls and
 * Session (its Files chip is the Desktop page's own).
 */
internal fun modeSheetTabs(steam: Boolean): List<String> =
    if (steam) listOf(SheetTab.DISPLAY, SheetTab.CONTROLS, SheetTab.AUDIO, SheetTab.SESSION, SheetTab.LIBRARY, SheetTab.STEAM)
    else listOf(SheetTab.DISPLAY, SheetTab.CONTROLS, SheetTab.SESSION)

/** A sheet tab's chip label. */
@Composable
internal fun sheetTabLabel(tab: String): String = when (tab) {
    SheetTab.DISPLAY -> stringResource(R.string.drawer_page_display)
    SheetTab.CONTROLS -> stringResource(R.string.drawer_page_controls)
    SheetTab.AUDIO -> stringResource(R.string.mode_audio)
    SheetTab.SESSION -> stringResource(R.string.mode_session)
    SheetTab.LIBRARY -> stringResource(R.string.stores_tab_library)
    SheetTab.STEAM -> stringResource(R.string.mode_tab_steam)
    else -> stringResource(R.string.setup_tool_files)
}

/**
 * Rows the front end adds to a sheet's tabs: frame generation (Display), Performance (Session) and
 * the Graphics drivers link to Components (Display, given the drivers' summary).
 */
class SheetExtras(
    val frameGen: @Composable () -> Unit = {},
    val performance: @Composable () -> Unit = {},
    val drivers: @Composable (summary: String?) -> Unit = {},
)

/** One tab of a mode's lifted sheet: the rows the settings page had, grouped by what they are for. */
@Composable
fun ModeSheetRows(s: ModeSettings, a: ModeSettingsActions, tab: String, extras: SheetExtras = SheetExtras()) {
    val steam = s.mode == SessionService.MODE_STEAM
    val host = rememberMenuHost()
    var explainWifiDiscovery by remember { mutableStateOf(false) }
    if (tab == SheetTab.DISPLAY) {
        val displaySize = com.droiddeck.launcher.session.SessionDisplay.resolveChoice(s.panelSize, s.resolution)
        SettingsGroup(stringResource(R.string.display_session)) {
            ResolutionRow(host, s.resolution, s.panelSize, a.onResolution)
            if (s.stretch16x9 != null && (com.droiddeck.launcher.session.SessionDisplay.canStretch16x9(s.panelSize) ||
                    com.droiddeck.launcher.session.SessionDisplay.canStretch16x9(displaySize))) {
                val canStretch = com.droiddeck.launcher.session.SessionDisplay.canStretch16x9(displaySize)
                ChoiceRow(host, "game-fit", stringResource(R.string.display_game_fit),
                    stringResource(if (canStretch) R.string.display_game_fit_hint else R.string.display_game_fit_unavailable),
                    listOf(false to stringResource(R.string.display_preserve), true to stringResource(R.string.display_stretch)),
                    s.stretch16x9 && canStretch, enabled = canStretch, onPick = a.onStretch16x9)
            }
        }
        SettingsGroup(stringResource(R.string.display_image_scaling)) {
            ChoiceRow(
                host, "upscaler", stringResource(R.string.display_filter), null,
                SessionPrefs.upscalerChoices(LocalContext.current), s.upscaler,
                note = stringResource(R.string.display_filter_note), onPick = a.onUpscaler,
            )
            if (SessionPrefs.upscalerHasSharpness(s.upscaler)) SliderRow(
                stringResource(R.string.display_sharpness), null, s.upscaleSharpness, 0..100, step = 5,
                format = { "$it%" }, onChange = a.onUpscaleSharpness,
            )
        }
        SettingsGroup(stringResource(R.string.display_frame_rate)) {
            ChoiceRow(host, "fps", stringResource(R.string.mode_fps), stringResource(R.string.common_applies_next_session),
                SessionPrefs.fpsLimitChoices(LocalContext.current), s.fpsLimit, note = stringResource(R.string.mode_fps_note), onPick = a.onFpsLimit)
        }
        SettingsGroup(stringResource(R.string.mode_hdr)) {
            ToggleRow(
                host, "hdr", stringResource(R.string.mode_hdr10),
                s.hdrReason?.let { stringResource(R.string.mode_hdr_unavailable, it) }
                    ?: stringResource(R.string.mode_hdr_restart),
                checked = s.hdr && s.hdrReason == null, enabled = s.hdrReason == null, onChange = a.onHdr,
            )
        }
        extras.frameGen()
        extras.drivers(s.gpuDrivers)
    }
    if (tab == SheetTab.CONTROLS) {
        SettingsGroup(if (steam) stringResource(R.string.mode_touch_controls) else stringResource(R.string.mode_touch)) {
            ChoiceRow(
                host, "touch", stringResource(R.string.mode_touch), null,
                listOf(SessionPrefs.TOUCH_AUTO to stringResource(R.string.common_auto), SessionPrefs.TOUCH_PAD to stringResource(R.string.mode_touch_touchpad), SessionPrefs.TOUCH_DIRECT to stringResource(R.string.mode_touch_direct), SessionPrefs.TOUCH_OFF to stringResource(R.string.widgets_off)), s.touchMode,
                note = stringResource(R.string.mode_touch_note),
                onPick = a.onTouch,
            )
            if (steam && s.oscMode != null) ChoiceRow(
                host, "osc", stringResource(R.string.mode_osc), null,
                listOf(
                    SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto),
                    SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always),
                    SessionPrefs.OSC_STEAM_TOUCH to stringResource(R.string.osc_steam_touch),
                    SessionPrefs.OSC_STEAM_QAM to stringResource(R.string.mode_osc_qam),
                    SessionPrefs.OSC_NEVER to stringResource(R.string.common_never),
                ), s.oscMode,
                note = stringResource(R.string.mode_osc_note), onPick = a.onOsc,
            )
            if (steam && s.steamController != null) ChoiceRow(
                host, "controller", stringResource(R.string.mode_controller), stringResource(R.string.mode_controller_hint),
                listOf(
                    SessionPrefs.CONTROLLER_DECK to stringResource(R.string.mode_controller_deck),
                    SessionPrefs.CONTROLLER_XBOX360 to stringResource(R.string.mode_controller_x360),
                ), s.steamController,
                note = stringResource(R.string.mode_controller_note),
                onPick = a.onSteamController,
            )
            if (steam && s.unfoldedControls != null) ToggleRow(
                host, "unfolded-controls", stringResource(R.string.unfolded_controls),
                stringResource(R.string.unfolded_controls_hint),
                s.unfoldedControls, onChange = a.onUnfoldedControls,
            )
            if (steam) ChoiceRow(
                host, "back-actions", stringResource(R.string.mode_back), stringResource(SessionPrefs.backActionsOrder(s.backActionsInverted)),
                listOf(
                    false to stringResource(SessionPrefs.BACK_MENU_THEN_QAM),
                    true to stringResource(SessionPrefs.BACK_QAM_THEN_MENU),
                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
            )
        }
    }
    if (tab == SheetTab.AUDIO && steam) {
        if (s.directAudio != null && s.mic != null) SettingsGroup(stringResource(R.string.mode_audio)) {
            ToggleRow(host, "da", stringResource(R.string.mode_directaudio), stringResource(R.string.mode_directaudio_hint), s.directAudio, onChange = a.onDirectAudio)
            ToggleRow(host, "mic", stringResource(R.string.mode_mic), stringResource(R.string.mode_mic_hint), s.mic, onChange = a.onMic)
        }
    }
    if (tab == SheetTab.SESSION) {
        SettingsGroup(stringResource(R.string.mode_session)) {
            ChoiceRow(
                host, "suspend", stringResource(R.string.mode_suspend),
                stringResource(R.string.mode_suspend_hint),
                listOf(
                    SessionPrefs.SUSPEND_AUTO to stringResource(R.string.common_auto),
                    SessionPrefs.SUSPEND_MANUAL to stringResource(R.string.mode_suspend_manual),
                    SessionPrefs.SUSPEND_NEVER to stringResource(R.string.common_never),
                ) + if (steam) listOf(
                    SessionPrefs.SUSPEND_NATIVE to stringResource(R.string.mode_suspend_native),
                ) else emptyList(),
                s.suspendPolicy,
                note = stringResource(if (steam) R.string.mode_suspend_steam_note else R.string.mode_suspend_note),
                onPick = a.onSuspendPolicy,
            )
            if (steam && s.suspendPolicy != SessionPrefs.SUSPEND_NEVER) ToggleRow(
                host, "background-downloads", stringResource(R.string.mode_background_downloads),
                stringResource(R.string.mode_background_downloads_hint),
                s.steamDownloadsInBackground, onChange = a.onSteamDownloadsInBackground,
            )
        }
        if (s.pipSupported) SettingsGroup(stringResource(R.string.pip_title)) {
            ToggleRow(host, "pip-auto", stringResource(R.string.pip_auto), null,
                s.pipAutoEnter, onChange = a.onPipAutoEnter)
        }
        if (steam) SettingsGroup(stringResource(R.string.mode_startup)) {
            ToggleRow(
                host, "steam-startup", stringResource(R.string.mode_steam_startup),
                stringResource(R.string.mode_steam_startup_hint),
                s.runSteamAtStartup, onChange = a.onRunSteamAtStartup,
            )
        }
        extras.performance()
    }
    if (tab == SheetTab.LIBRARY && steam) {
        if (s.gameStorage != null) SettingsGroup(stringResource(R.string.mode_storage)) {
            val custom = s.gameStorage.isNotEmpty() && s.gameStorage != "off" && s.storageOptions.none { it.path == s.gameStorage }
            // Each choice: its path, its menu line, the chip's shorter line, and the label it is saved with.
            class StorageChoice(val path: String, val label: String, val chip: String, val saved: String)
            val internal = stringResource(R.string.mode_storage_internal)
            val options = buildList {
                add(StorageChoice(
                    "", if (s.storageOptions.isEmpty()) stringResource(R.string.mode_storage_auto_none) else stringResource(R.string.mode_storage_auto),
                    stringResource(R.string.mode_storage_auto_short), "",
                ))
                add(StorageChoice("off", internal, internal, ""))
                for (o in s.storageOptions) add(StorageChoice(o.path, o.label, o.label, o.name))
                if (custom) stringResource(R.string.mode_storage_folder, s.gameStorage).let { add(StorageChoice(s.gameStorage, it, it, it)) }
            }
            val open = host.open == "storage"
            SettingsRow(
                stringResource(R.string.mode_second_library),
                stringResource(R.string.second_library_import_hint),
                highlighted = open,
            ) {
                androidx.compose.foundation.layout.Box {
                    ValueChip(options.firstOrNull { it.path == s.gameStorage }?.chip ?: "-", open) { host.open = if (open) null else "storage" }
                    AnchoredMenu(
                        open, onDismiss = { if (host.open == "storage") host.open = null }, title = stringResource(R.string.mode_second_library),
                        note = stringResource(R.string.mode_storage_note),
                    ) { firstItemFocus ->
                        options.forEachIndexed { index, choice ->
                            MenuItem(choice.label, checked = choice.path == s.gameStorage, focusRequester = if (index == 0) firstItemFocus else null) {
                                a.onGameStorage(choice.path, choice.saved)
                                host.open = null
                            }
                        }
                        MenuItem(stringResource(R.string.mode_choose_folder), checked = false) { host.open = null; a.onPickGameStorageFolder() }
                    }
                }
            }
            ToggleRow(
                host, "storageDiagnostics", stringResource(R.string.mode_storage_diagnostics),
                stringResource(R.string.mode_storage_diagnostics_hint), s.storageDiagnostics,
                onChange = a.onStorageDiagnostics,
            )
        }
    }
    if (tab == SheetTab.STEAM && steam) {
        SettingsGroup(stringResource(R.string.mode_decky)) {
            val updateAvailable = s.deckyInstalled != null && s.deckyLatestRelease != null &&
                s.deckyInstalled != s.deckyLatestRelease.tag
            val status = when {
                s.deckyStage != null -> s.deckyStage
                s.deckyChecking -> stringResource(R.string.mode_decky_checking)
                s.deckyInstalled == null && s.deckyLatestRelease != null -> stringResource(R.string.mode_decky_ready, s.deckyLatestRelease.tag)
                s.deckyInstalled == null -> stringResource(R.string.mode_decky_none)
                s.deckyLatestRelease == null -> stringResource(R.string.mode_decky_installed, s.deckyInstalled)
                updateAvailable -> stringResource(R.string.mode_decky_update, s.deckyLatestRelease.tag)
                else -> stringResource(R.string.mode_decky_current, s.deckyInstalled)
            }
            SettingsRow(stringResource(R.string.mode_decky_loader), status) {
                val action = when {
                    s.deckyStage != null -> stringResource(R.string.common_working)
                    s.deckyChecking -> stringResource(R.string.common_checking)
                    s.deckyInstalled == null && s.deckyLatestRelease != null -> stringResource(R.string.mode_decky_install_latest)
                    s.deckyInstalled != null && updateAvailable -> stringResource(R.string.common_update)
                    else -> stringResource(R.string.common_check)
                }
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp)) {
                    SecondaryButton(
                        action,
                        enabled = s.deckyStage == null && !s.deckyChecking && !s.deckySessionRunning,
                    ) {
                        if (s.deckyLatestRelease == null || (s.deckyInstalled != null && !updateAvailable)) a.onDeckyCheck()
                        else s.deckyLatestRelease?.let(a.onDeckyInstall)
                    }
                    if (s.deckyInstalled != null) DeckyUninstall(enabled = s.deckyStage == null && !s.deckySessionRunning, onUninstall = a.onDeckyUninstall)
                }
            }
            if (s.deckyInstalled != null) ToggleRow(
                host, "decky-enabled", stringResource(R.string.mode_decky),
                if (s.deckyEnabled) stringResource(R.string.mode_decky_on)
                else stringResource(R.string.mode_decky_off),
                s.deckyEnabled,
                enabled = !s.deckySessionRunning && s.deckyStage == null,
                onChange = a.onDeckyEnabled,
            )
            SettingsRow(
                stringResource(R.string.mode_decky_plugins),
                stringResource(R.string.mode_decky_plugins_hint),
            ) {
                SecondaryButton(
                    if (s.deckyStage != null && s.deckyDownloadingBinary) stringResource(R.string.mode_decky_plugin_downloading) else stringResource(R.string.mode_decky_plugin_install_zip),
                    enabled = s.deckyInstalled != null && s.deckyStage == null && !s.deckySessionRunning,
                    onClick = a.onPickDeckyPluginZip,
                )
            }
            if (s.deckyStage != null && s.deckyPercent >= 0) {
                LinearProgressIndicator(
                    progress = { s.deckyPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (s.steamChannel != null) SettingsGroup(stringResource(R.string.mode_client)) {
            ToggleRow(
                host, "steamdeck", stringResource(R.string.mode_deck_mode),
                stringResource(R.string.mode_deck_mode_hint),
                s.steamDeckMode, onChange = a.onSteamDeckMode,
            )
            if (s.steamDeckMode) ToggleRow(
                host, "mangoapp", stringResource(R.string.mode_mangoapp),
                stringResource(R.string.mode_mangoapp_hint),
                s.mangoapp, onChange = a.onMangoapp,
            )
            // Deck mode fixes the branch (SessionPrefs.steamChannel); the choice is for Deck mode off.
            if (s.steamDeckMode) SettingsRow(stringResource(R.string.mode_branch), stringResource(R.string.mode_branch_deck)) {}
            else ChoiceRow(
                host, "channel", stringResource(R.string.mode_branch), stringResource(R.string.mode_branch_hint),
                listOf("publicbeta" to stringResource(R.string.mode_branch_public), "steamdeck_publicbeta" to stringResource(R.string.mode_branch_deck_beta)), s.steamChannel,
                note = stringResource(R.string.mode_branch_note),
                onPick = a.onSteamChannel,
            )
            SettingsRow(
                stringResource(R.string.mode_steam_repair),
                stringResource(if (s.steamRepairQueued) R.string.mode_steam_repair_queued else R.string.mode_steam_repair_hint),
            ) {
                SecondaryButton(stringResource(R.string.mode_steam_repair_button), enabled = !s.steamRepairQueued, onClick = a.onSteamRepair)
            }
        }
        if (s.wifiDiscovery != null) SettingsGroup(stringResource(R.string.mode_network)) {
            val hint = when {
                s.wifiDiscovery && !s.wifiDiscoveryLocation -> stringResource(R.string.mode_wifi_location_off)
                !s.wifiDiscoveryPermission && s.wifiDiscoveryAsked -> stringResource(R.string.mode_wifi_permission_denied)
                else -> stringResource(R.string.mode_wifi_discovery_hint)
            }
            SettingsRow(stringResource(R.string.mode_wifi_discovery), hint) {
                Row(verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp)) {
                    if (s.wifiDiscoveryBlocked || (s.wifiDiscovery && !s.wifiDiscoveryLocation)) {
                        SecondaryButton(stringResource(R.string.mode_wifi_open_settings), onClick = a.onWifiDiscoverySettings)
                    }
                    ToggleSwitch(s.wifiDiscovery, label = stringResource(R.string.mode_wifi_discovery)) { on ->
                        host.open = null
                        if (on) explainWifiDiscovery = true else a.onWifiDiscovery(false)
                    }
                }
            }
        }
    }
    if (explainWifiDiscovery) AlertDialog(
        onDismissRequest = { explainWifiDiscovery = false },
        title = { Text(stringResource(R.string.mode_wifi_explain_title)) },
        text = { Text(stringResource(R.string.mode_wifi_explain_text)) },
        confirmButton = {
            TextButton(onClick = { explainWifiDiscovery = false; a.onWifiDiscovery(true) }) {
                Text(stringResource(if (s.wifiDiscoveryBlocked) R.string.mode_wifi_open_settings else R.string.mode_wifi_continue))
            }
        },
        dismissButton = {
            TextButton(onClick = { explainWifiDiscovery = false }) { Text(stringResource(R.string.common_cancel)) }
        },
    )
}

/**
 * The options that apply to every Proton - how Wine syncs, FEX's preset, Force SSBS and the games'
 * environment - for the Components page's Proton layer.
 */
@Composable
internal fun ProtonOptions(
    syncBackend: String, fexPreset: String, forceSsbs: Boolean,
    onSyncBackend: (String) -> Unit, onFexPreset: (String) -> Unit, onForceSsbs: (Boolean) -> Unit,
) {
    val host = rememberMenuHost()
    SettingsRow(stringResource(R.string.sync_backend_title), stringResource(R.string.sync_backend_hint)) {
        SegmentedTabs(
            listOf(
                SessionPrefs.SYNC_NTSYNC to stringResource(R.string.sync_backend_ntsync),
                SessionPrefs.SYNC_FSYNC to stringResource(R.string.sync_backend_fsync),
                SessionPrefs.SYNC_ESYNC to stringResource(R.string.sync_backend_esync),
                SessionPrefs.SYNC_WINESERVER to stringResource(R.string.sync_backend_wineserver),
            ),
            syncBackend,
        ) { id -> host.open = null; onSyncBackend(id) }
    }
    ChoiceRow(
        host, "fex", stringResource(R.string.fex_preset_title), stringResource(R.string.fex_next_launch),
        FexPreset.all.map { it.id to stringResource(it.label) }, fexPreset,
        note = stringResource(FexPreset.byId(fexPreset).detail), onPick = onFexPreset,
    )
    ToggleRow(host, "ssbs", stringResource(R.string.force_ssbs_title), stringResource(R.string.force_ssbs_hint), forceSsbs, onChange = onForceSsbs)
    GameEnvironmentRow()
}

/**
 * Uninstall for Decky Loader: are you sure steps out of it in red, Cancel first, so a stray A
 * costs nothing.
 */
@Composable
private fun DeckyUninstall(enabled: Boolean, onUninstall: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val label = stringResource(R.string.common_uninstall)
    val title = stringResource(R.string.mode_decky_remove_title)
    val note = stringResource(R.string.mode_decky_remove_text)
    val cancel = stringResource(R.string.common_cancel)
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    androidx.compose.foundation.layout.Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        SecondaryButton(label, enabled = enabled) {
            val from = at ?: return@SecondaryButton
            Steps.ask(StepAsk(
                anchor = from, side = StepSide.Left, accent = colors.error, pillCorner = 12.dp, items = 3,
                handle = { Text(label, fontSize = 15.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold, color = colors.error, maxLines = 1) },
            ) {
                StepTitle(title)
                Text(note, fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.stepItem(1).padding(start = 4.dp, bottom = 12.dp))
                Row(horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(8.dp, androidx.compose.ui.Alignment.End), modifier = Modifier.stepItem(2)) {
                    StepChoice(cancel, enabled = open, modifier = Modifier.focusRequester(first)) { Steps.fold() }
                    StepChoice(label, danger = true, enabled = open) { Steps.fold(); onUninstall() }
                }
            })
        }
    }
}

/** Width × height for the session, with the common handheld shapes one tap away. */
/** Entry for the user's SteamGridDB API key; the saved key is never shown back. */
@Composable
internal fun SgdbKeyDialog(hasKey: Boolean, onSave: (String) -> Unit, onDismiss: () -> Unit) {
    var key by remember { mutableStateOf("") }
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_sgdb_key)) },
        text = {
            androidx.compose.material3.OutlinedTextField(
                key, { v -> key = v.trim() }, label = { Text(stringResource(R.string.mode_sgdb_key)) },
                singleLine = true, visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Password),
                modifier = Modifier.fillMaxWidth(),
            )
        },
        confirmButton = { androidx.compose.material3.TextButton(onClick = { onSave(key) }, enabled = key.isNotEmpty()) { Text(stringResource(R.string.common_ok)) } },
        dismissButton = {
            androidx.compose.foundation.layout.Row {
                if (hasKey) androidx.compose.material3.TextButton(onClick = { onSave("") }) { Text(stringResource(R.string.common_delete)) }
                androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) }
            }
        },
    )
}

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
internal fun CustomResolutionDialog(initial: Pair<Int, Int>?, onSave: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit) {
    var w by remember { mutableStateOf(initial?.first?.toString() ?: "") }
    var h by remember { mutableStateOf(initial?.second?.toString() ?: "") }
    val parsed = SessionPrefs.parseResolution("${w}x$h")
    androidx.compose.material3.AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.mode_custom_res_title)) },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    stringResource(R.string.mode_custom_res_text),
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    val numbers = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    androidx.compose.material3.OutlinedTextField(
                        w, { v -> w = v.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.mode_width)) },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                    Text("×", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        h, { v -> h = v.filter(Char::isDigit).take(4) }, label = { Text(stringResource(R.string.mode_height)) },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                }
                androidx.compose.foundation.layout.FlowRow(
                    horizontalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(6.dp),
                    modifier = Modifier.padding(top = 10.dp),
                ) {
                    for ((pw, ph, tag) in listOf(Triple(960, 720, "4:3"), Triple(1024, 768, "4:3"), Triple(1280, 960, "4:3"), Triple(1280, 800, "16:10"), Triple(1152, 648, "16:9"), Triple(1280, 720, "16:9"))) {
                        androidx.compose.material3.AssistChip(
                            onClick = { w = pw.toString(); h = ph.toString() },
                            label = { Text("$pw×$ph · $tag", fontSize = 12.sp) },
                        )
                    }
                }
                if (parsed == null && (w.isNotEmpty() || h.isNotEmpty())) Text(
                    stringResource(R.string.mode_custom_res_range), fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text(stringResource(R.string.common_use)) } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text(stringResource(R.string.common_cancel)) } },
    )
}
