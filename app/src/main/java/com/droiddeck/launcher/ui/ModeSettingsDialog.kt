package com.droiddeck.launcher.ui

import androidx.compose.ui.res.pluralStringResource
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
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

/** A release driver (Banners-Turnip, WinNative) that is not installed yet; [key] is its asset name. */
class DownloadRow(val key: String, val label: String, val detail: String, val progress: Int? = null)

class ModeSettings(
    val mode: String,
    val resolutionCap: Int,
    /** A fixed session size, or null for the cap and shape. */
    val customResolution: Pair<Int, Int>? = null,
    val shapeMode: String,
    val hdr: Boolean,
    val hdrReason: String?,
    /** The GPU drivers in use, as the row that opens them on the Components page says it. */
    val gpuDrivers: String = "Auto",
    /** Frames per second the session is capped at; 0 = none. */
    val fpsLimit: Int = 0,
    val upscaler: Int = 0,
    val upscaleSharpness: Int = 75,
    val touchMode: String,
    val suspendPolicy: String,
    /** Steam only. */
    val oscMode: String?,
    /** Steam only: whether single and double Back actions are swapped. */
    val backActionsInverted: Boolean = false,
    val directAudio: Boolean?,
    val clientDirectAudio: Boolean = false,
    val mic: Boolean?,
    val renderer: String?,
    val gameStorage: String? = null,
    val storageOptions: List<Pair<String, String>> = emptyList(),
    /** Steam only: record allocation and sampled storage timings in the next session's Share logs. */
    val storageDiagnostics: Boolean = false,
    val fexPreset: String? = null,
    /** Steam only: games are stretched to fill the screen (null = not a Steam page). */
    val forceFullscreen: Boolean? = null,
    val stretch16x9: Boolean? = null,
    /** Steam only: the client branch forced on the command line. */
    val steamChannel: String? = null,
    /** Steam only: enable the SteamOS client interface and its performance controls. */
    val steamDeckMode: Boolean = false,
    /** Steam, Deck mode: the QAM's performance overlay (mangoapp) is started. */
    val mangoapp: Boolean = true,
    /** Steam only: what the pad is to the client (SessionPrefs.CONTROLLER_*); null outside Steam. */
    val steamController: String? = null,
    /** Steam only: start a Steam session when DroidDeck opens. */
    val runSteamAtStartup: Boolean = false,
    /** Null outside Steam; the saved choice is separate from Android's access and device switch. */
    val wifiDiscovery: Boolean? = null,
    val wifiDiscoveryPermission: Boolean = false,
    val wifiDiscoveryLocation: Boolean = false,
    val wifiDiscoveryAsked: Boolean = false,
    val wifiDiscoveryBlocked: Boolean = false,
    /** Steam only: the user's chosen Games folders; null outside Steam. */
    val addedGamesDirs: List<String>? = null,
    val addedGames: List<AddedGameRow> = emptyList(),
    val addedGamesArt: Boolean = true,
    /** Latest Banners-Turnip release: what each driver menu offers to download, and the refresh line. */
    /** Steam only: Decky Loader is managed from the Steam session settings. */
    val deckyInstalled: String? = null,
    val deckyLatestRelease: DeckyManager.Release? = null,
    val deckyChecking: Boolean = false,
    val deckyStage: String? = null,
    val deckyPercent: Int = -1,
    val deckyEnabled: Boolean = false,
    val deckySessionRunning: Boolean = false,
)

/** One added game as the settings page shows it: its folder, the chosen .exe, the other .exe files it could be. */
class AddedGameRow(val folderPath: String, val folderName: String, val exePath: String, val exeName: String, val candidates: List<Pair<String, String>>)

class ModeSettingsActions(
    val onResolution: (Int) -> Unit,
    /** Null clears it. */
    val onCustomResolution: (Pair<Int, Int>?) -> Unit = {},
    val onShape: (String) -> Unit,
    val onHdr: (Boolean) -> Unit,
    /** Opens the GPU drivers on the Components page: they are shared by every session. */
    val onGpuDrivers: () -> Unit = {},
    val onFpsLimit: (Int) -> Unit = {},
    val onUpscaler: (Int) -> Unit = {},
    val onUpscaleSharpness: (Int) -> Unit = {},
    val onTouch: (String) -> Unit,
    val onSuspendPolicy: (String) -> Unit,
    val onOsc: (String) -> Unit,
    val onBackActionsInverted: (Boolean) -> Unit = {},
    val onDirectAudio: (Boolean) -> Unit,
    val onClientDirectAudio: (Boolean) -> Unit = {},
    val onMic: (Boolean) -> Unit,
    val onRenderer: (String) -> Unit,
    val onGameStorage: (path: String, label: String) -> Unit = { _, _ -> },
    val onPickGameStorageFolder: () -> Unit = {},
    val onStorageDiagnostics: (Boolean) -> Unit = {},
    val onFexPreset: (String) -> Unit = {},
    val onForceFullscreen: (Boolean) -> Unit = {},
    val onStretch16x9: (Boolean) -> Unit = {},
    val onSteamChannel: (String) -> Unit = {},
    val onSteamDeckMode: (Boolean) -> Unit = {},
    val onMangoapp: (Boolean) -> Unit = {},
    val onSteamController: (String) -> Unit = {},
    val onRunSteamAtStartup: (Boolean) -> Unit = {},
    val onWifiDiscovery: (Boolean) -> Unit = {},
    val onWifiDiscoverySettings: () -> Unit = {},
    val onPickAddedGamesDir: () -> Unit = {},
    val onForgetAddedGamesDir: (path: String) -> Unit = {},
    val onAddedGamesArt: (Boolean) -> Unit = {},
    val onAddedGameExe: (folderPath: String, path: String) -> Unit = { _, _ -> },
    val onPickAddedGameExe: (folderPath: String) -> Unit = {},
    val onDeckyInstall: (DeckyManager.Release) -> Unit = {},
    val onDeckyCheck: () -> Unit = {},
    val onDeckyEnabled: (Boolean) -> Unit = {},
    val onDeckyUninstall: () -> Unit = {},
    val onDismiss: () -> Unit,
)

@Composable
fun ModeSettingsPage(s: ModeSettings, a: ModeSettingsActions) {
    val steam = s.mode == SessionService.MODE_STEAM
    val host = rememberMenuHost()
    var confirmDeckyRemoval by remember { mutableStateOf(false) }
    var explainWifiDiscovery by remember { mutableStateOf(false) }
    val pageScroll = androidx.compose.foundation.rememberScrollState()
    val firstChip = remember { androidx.compose.ui.focus.FocusRequester() }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        // One frame first: the box has to be laid out before it can take focus. Opened from the
        // cog, focus starts on the first control (Resolution) so the d-pad works at once.
        androidx.compose.runtime.withFrameNanos { }
        runCatching { firstChip.requestFocus() }
    }
    SettingsPage(
        host,
        title = if (steam) stringResource(R.string.mode_steam_title) else stringResource(R.string.mode_desktop_title),
        onBack = a.onDismiss,
        scroll = pageScroll,
    ) {
        SettingsGroup(stringResource(R.string.mode_display)) {
            val default = SessionPrefs.defaultResolutionCap(s.mode)
            var editCustom by remember { mutableStateOf(false) }
            val custom = s.customResolution
            ChoiceRow(
                host, "res", stringResource(R.string.mode_resolution), stringResource(R.string.common_applies_next_session),
                listOf(720 to stringResource(R.string.mode_res_720), 900 to stringResource(R.string.mode_res_900), 1080 to stringResource(R.string.mode_res_1080), 0 to stringResource(R.string.mode_res_native))
                    .map { (cap, label) -> cap to (if (cap == default) stringResource(R.string.mode_res_default, label) else label) } +
                    (CUSTOM to (custom?.let { stringResource(R.string.mode_res_custom_value, it.first, it.second) } ?: stringResource(R.string.mode_res_custom))),
                if (custom != null) CUSTOM else s.resolutionCap, note = stringResource(R.string.mode_res_note),
                chipModifier = androidx.compose.ui.Modifier.focusRequester(firstChip),
                onPick = { v -> if (v == CUSTOM) editCustom = true else { a.onCustomResolution(null); a.onResolution(v) } },
            )
            ChoiceRow(
                host, "shape", stringResource(R.string.mode_ratio),
                if (custom != null) stringResource(R.string.mode_ratio_custom) else stringResource(R.string.mode_ratio_auto),
                com.droiddeck.launcher.session.SessionPrefs.shapeChoices, s.shapeMode, enabled = custom == null, onPick = a.onShape,
            )
            ChoiceRow(
                host, "fps", stringResource(R.string.mode_fps), stringResource(R.string.common_applies_next_session),
                com.droiddeck.launcher.session.SessionPrefs.fpsLimitChoices, s.fpsLimit,
                note = stringResource(R.string.mode_fps_note),
                onPick = a.onFpsLimit,
            )
            ChoiceRow(
                host, "upscaler", "Upscaler", "Sharpens the picture where it is enlarged to the screen.",
                com.droiddeck.launcher.session.SessionPrefs.upscalerChoices, s.upscaler,
                note = "Works only when the session is smaller than the screen; Sharpen only works at any size. Costs a little GPU time.",
                onPick = a.onUpscaler,
            )
            ChoiceRow(
                host, "upscale-sharpness", "Upscaler sharpness", null,
                com.droiddeck.launcher.session.SessionPrefs.upscaleSharpnessChoices, s.upscaleSharpness,
                enabled = s.upscaler != 0, onPick = a.onUpscaleSharpness,
            )
            if (editCustom) CustomResolutionDialog(
                initial = custom,
                onSave = { size -> editCustom = false; a.onCustomResolution(size) },
                onDismiss = { editCustom = false },
            )
        }
        SettingsGroup(stringResource(R.string.mode_hdr)) {
            ToggleRow(
                host, "hdr", stringResource(R.string.mode_hdr10),
                s.hdrReason?.let { stringResource(R.string.mode_hdr_unavailable, it) }
                    ?: stringResource(R.string.mode_hdr_restart),
                checked = s.hdr && s.hdrReason == null, enabled = s.hdrReason == null, onChange = a.onHdr,
            )
        }
        SettingsGroup(stringResource(R.string.mode_drivers)) {
            SettingsRow(stringResource(R.string.mode_gpu_drivers), stringResource(R.string.mode_gpu_drivers_hint)) {
                ValueChip(s.gpuDrivers, open = false) { a.onGpuDrivers() }
            }
        }
        SettingsGroup(if (steam) stringResource(R.string.mode_touch_controls) else stringResource(R.string.mode_touch)) {
            ChoiceRow(
                host, "touch", stringResource(R.string.mode_touch), null,
                listOf("auto" to stringResource(R.string.common_auto), "touchpad" to stringResource(R.string.mode_touch_touchpad), "direct" to stringResource(R.string.mode_touch_direct)), s.touchMode,
                note = stringResource(R.string.mode_touch_note),
                onPick = a.onTouch,
            )
            if (steam && s.oscMode != null) ChoiceRow(
                host, "osc", stringResource(R.string.mode_osc), null,
                listOf(
                    SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto),
                    SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always),
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
            if (steam) ChoiceRow(
                host, "back-actions", stringResource(R.string.mode_back), SessionPrefs.backActionsOrder(s.backActionsInverted),
                listOf(
                    false to SessionPrefs.BACK_MENU_THEN_QAM,
                    true to SessionPrefs.BACK_QAM_THEN_MENU,
                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
            )
        }
        SettingsGroup(stringResource(R.string.mode_session)) {
            ChoiceRow(
                host, "suspend", stringResource(R.string.mode_suspend),
                stringResource(R.string.mode_suspend_hint),
                listOf(
                    SessionPrefs.SUSPEND_AUTO to stringResource(R.string.common_auto),
                    SessionPrefs.SUSPEND_MANUAL to stringResource(R.string.mode_suspend_manual),
                    SessionPrefs.SUSPEND_NEVER to stringResource(R.string.common_never),
                ),
                s.suspendPolicy,
                note = stringResource(R.string.mode_suspend_note),
                onPick = a.onSuspendPolicy,
            )
        }
        if (steam) SettingsGroup(stringResource(R.string.mode_startup)) {
            ToggleRow(
                host, "steam-startup", stringResource(R.string.mode_steam_startup),
                stringResource(R.string.mode_steam_startup_hint),
                s.runSteamAtStartup, onChange = a.onRunSteamAtStartup,
            )
        }
        if (steam) SettingsGroup(stringResource(R.string.mode_decky)) {
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
                    if (s.deckyInstalled != null) SecondaryButton(
                        stringResource(R.string.common_uninstall),
                        enabled = s.deckyStage == null && !s.deckySessionRunning,
                    ) { confirmDeckyRemoval = true }
                }
            }
            if (s.deckyInstalled != null) SettingsRow(
                stringResource(R.string.mode_decky),
                if (s.deckyEnabled) stringResource(R.string.mode_decky_on)
                else stringResource(R.string.mode_decky_off),
            ) {
                Switch(
                    checked = s.deckyEnabled,
                    onCheckedChange = a.onDeckyEnabled,
                    enabled = !s.deckySessionRunning && s.deckyStage == null,
                )
            }
            if (s.deckyStage != null && s.deckyPercent >= 0) {
                LinearProgressIndicator(
                    progress = { s.deckyPercent / 100f },
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp),
                )
            }
        }
        if (steam && s.steamChannel != null) SettingsGroup(stringResource(R.string.mode_client)) {
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
        }
        if (steam && s.wifiDiscovery != null) SettingsGroup(stringResource(R.string.mode_network)) {
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
        if (steam && s.addedGamesDirs != null) SettingsGroup(stringResource(R.string.mode_added_games)) {
            for (dir in s.addedGamesDirs) {
                val n = s.addedGames.count { it.folderPath.startsWith("$dir/") }
                ActionRow(
                    dir.substringAfterLast('/').ifEmpty { dir }, dir + " · " + (if (n == 0) stringResource(R.string.mode_added_none) else pluralStringResource(R.plurals.mode_added_count, n, n)) + ". " + stringResource(R.string.added_games_forget_hint),
                    stringResource(R.string.common_forget), onClick = { a.onForgetAddedGamesDir(dir) },
                )
            }
            ActionRow(
                if (s.addedGamesDirs.isEmpty()) stringResource(R.string.mode_games_folder) else stringResource(R.string.mode_games_folder_another),
                stringResource(R.string.added_games_import_hint),
                stringResource(R.string.common_add_ellipsis), onClick = a.onPickAddedGamesDir,
            )
            ToggleRow(
                host, "addedArt", stringResource(R.string.mode_added_art),
                stringResource(R.string.mode_added_art_hint),
                s.addedGamesArt, onChange = a.onAddedGamesArt,
            )
            for (g in s.addedGames) ChoiceRow(
                host, "added:" + g.folderPath, g.folderName, if (s.addedGamesDirs.size > 1) stringResource(R.string.mode_added_launches_in, g.exeName, g.folderPath.substringBeforeLast('/').substringAfterLast('/')) else stringResource(R.string.mode_added_launches, g.exeName),
                g.candidates + ("__pick__" to stringResource(R.string.mode_added_choose)), g.exePath,
                note = stringResource(R.string.mode_added_exe_note),
                onPick = { path -> if (path == "__pick__") a.onPickAddedGameExe(g.folderPath) else a.onAddedGameExe(g.folderPath, path) },
            )
        }
        if (steam && s.fexPreset != null) SettingsGroup(stringResource(R.string.game_settings_title)) {
            ChoiceRow(
                host, "fex", stringResource(R.string.fex_preset_title), stringResource(R.string.fex_next_launch),
                FexPreset.all.map { it.id to stringResource(it.label) }, s.fexPreset,
                note = stringResource(FexPreset.byId(s.fexPreset).detail), onPick = a.onFexPreset,
            )
            GameEnvironmentRow()
            if (s.forceFullscreen != null) ToggleRow(
                host, "fill", stringResource(R.string.mode_fill),
                stringResource(R.string.mode_fill_hint),
                s.forceFullscreen, onChange = a.onForceFullscreen,
            )
            if (s.stretch16x9 != null) ToggleRow(
                host, "stretch169", stringResource(R.string.mode_stretch169),
                stringResource(R.string.mode_stretch169_hint),
                s.stretch16x9, onChange = a.onStretch16x9,
            )
        }
        if (steam && s.directAudio != null && s.mic != null) SettingsGroup(stringResource(R.string.mode_audio)) {
            ToggleRow(host, "da", stringResource(R.string.mode_directaudio), stringResource(R.string.mode_directaudio_hint), s.directAudio, onChange = a.onDirectAudio)
            ChoiceRow(
                host, "clientAudio", stringResource(R.string.mode_client_audio), stringResource(R.string.mode_client_audio_hint),
                listOf("classic" to stringResource(R.string.mode_client_audio_classic), "directaudio" to stringResource(R.string.mode_client_audio_direct)), if (s.clientDirectAudio) "directaudio" else "classic",
                onPick = { id -> a.onClientDirectAudio(id == "directaudio") },
            )
            ToggleRow(host, "mic", stringResource(R.string.mode_mic), stringResource(R.string.mode_mic_hint), s.mic, onChange = a.onMic)
        }
        if (steam && s.gameStorage != null) SettingsGroup(stringResource(R.string.mode_storage)) {
            val custom = s.gameStorage.isNotEmpty() && s.gameStorage != "off" && s.storageOptions.none { it.second == s.gameStorage }
            val options = buildList {
                add("" to (if (s.storageOptions.isEmpty()) stringResource(R.string.mode_storage_auto_none) else stringResource(R.string.mode_storage_auto)))
                add("off" to stringResource(R.string.mode_storage_internal))
                for ((label, path) in s.storageOptions) add(path to label)
                if (custom) add(s.gameStorage to stringResource(R.string.mode_storage_folder, s.gameStorage))
            }
            val open = host.open == "storage"
            SettingsRow(
                stringResource(R.string.mode_second_library),
                stringResource(R.string.second_library_import_hint),
                highlighted = open,
            ) {
                androidx.compose.foundation.layout.Box {
                    ValueChip(options.firstOrNull { it.first == s.gameStorage }?.second?.substringBefore(" -") ?: "-", open) { host.open = if (open) null else "storage" }
                    AnchoredMenu(
                        open, onDismiss = { if (host.open == "storage") host.open = null }, title = stringResource(R.string.mode_second_library),
                        note = stringResource(R.string.mode_storage_note),
                    ) { firstItemFocus ->
                        options.forEachIndexed { index, (path, label) ->
                            MenuItem(label, checked = path == s.gameStorage, focusRequester = if (index == 0) firstItemFocus else null) {
                                a.onGameStorage(path, if (path.isEmpty() || path == "off") "" else label.substringBefore(" ·"))
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
        if (!steam && s.renderer != null) SettingsGroup(stringResource(R.string.mode_renderer)) {
            ChoiceRow(
                host, "renderer", stringResource(R.string.mode_desktop_renderer), stringResource(R.string.mode_renderer_hint),
                listOf("vulkan" to stringResource(R.string.mode_renderer_vulkan), "gles2" to stringResource(R.string.mode_renderer_gles2), "pixman" to stringResource(R.string.mode_renderer_pixman)), s.renderer,
                note = stringResource(R.string.mode_renderer_note),
                onPick = a.onRenderer,
            )
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
    if (confirmDeckyRemoval) AlertDialog(
        onDismissRequest = { confirmDeckyRemoval = false },
        title = { Text(stringResource(R.string.mode_decky_remove_title)) },
        text = { Text(stringResource(R.string.mode_decky_remove_text)) },
        confirmButton = {
            TextButton(onClick = { confirmDeckyRemoval = false; a.onDeckyUninstall() }) { Text(stringResource(R.string.common_uninstall)) }
        },
        dismissButton = { TextButton(onClick = { confirmDeckyRemoval = false }) { Text(stringResource(R.string.common_cancel)) } },
    )
}

/** The Resolution menu's "Custom…" entry. */
private const val CUSTOM = -1

/** Width × height for the session, with the common handheld shapes one tap away. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun CustomResolutionDialog(initial: Pair<Int, Int>?, onSave: (Pair<Int, Int>) -> Unit, onDismiss: () -> Unit) {
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
