package com.droiddeck.launcher.ui

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
    val onFexPreset: (String) -> Unit = {},
    val onForceFullscreen: (Boolean) -> Unit = {},
    val onStretch16x9: (Boolean) -> Unit = {},
    val onSteamChannel: (String) -> Unit = {},
    val onSteamDeckMode: (Boolean) -> Unit = {},
    val onMangoapp: (Boolean) -> Unit = {},
    val onSteamController: (String) -> Unit = {},
    val onRunSteamAtStartup: (Boolean) -> Unit = {},
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
        title = if (steam) "Steam session" else "Desktop session",
        onBack = a.onDismiss,
        scroll = pageScroll,
    ) {
        SettingsGroup("Display") {
            val default = SessionPrefs.defaultResolutionCap(s.mode)
            var editCustom by remember { mutableStateOf(false) }
            val custom = s.customResolution
            ChoiceRow(
                host, "res", "Resolution", "Applies next session.",
                listOf(720 to "Up to 720p", 900 to "Up to 900p", 1080 to "Up to 1080p", 0 to "The panel's own")
                    .map { (cap, label) -> cap to (if (cap == default) "$label - the default" else label) } +
                    (CUSTOM to (custom?.let { "Custom · ${it.first}×${it.second}" } ?: "Custom…")),
                if (custom != null) CUSTOM else s.resolutionCap, note = "720p can improve menu responsiveness.",
                chipModifier = androidx.compose.ui.Modifier.focusRequester(firstChip),
                onPick = { v -> if (v == CUSTOM) editCustom = true else { a.onCustomResolution(null); a.onResolution(v) } },
            )
            ChoiceRow(
                host, "shape", "Screen ratio",
                if (custom != null) "Set by the custom resolution." else "Auto uses at least 16:9.",
                com.droiddeck.launcher.session.SessionPrefs.shapeChoices, s.shapeMode, enabled = custom == null, onPick = a.onShape,
            )
            ChoiceRow(
                host, "fps", "Frame limit", "Applies next session.",
                com.droiddeck.launcher.session.SessionPrefs.fpsLimitChoices, s.fpsLimit,
                note = "Caps the whole session. The screen switches to a rate the cap divides evenly.",
                onPick = a.onFpsLimit,
            )
            if (editCustom) CustomResolutionDialog(
                initial = custom,
                onSave = { size -> editCustom = false; a.onCustomResolution(size) },
                onDismiss = { editCustom = false },
            )
        }
        SettingsGroup("HDR") {
            ToggleRow(
                host, "hdr", "HDR10 output",
                s.hdrReason?.let { "Not available: $it." }
                    ?: "Restart the app to apply.",
                checked = s.hdr && s.hdrReason == null, enabled = s.hdrReason == null, onChange = a.onHdr,
            )
        }
        SettingsGroup("Drivers") {
            SettingsRow("GPU drivers", "Shared by Steam and the desktop, on the Components page.") {
                ValueChip(s.gpuDrivers, open = false) { a.onGpuDrivers() }
            }
        }
        SettingsGroup(if (steam) "Touch & controls" else "Touch") {
            ChoiceRow(
                host, "touch", "Touch", null,
                listOf("auto" to "Auto", "touchpad" to "Touchpad", "direct" to "Direct"), s.touchMode,
                note = "Auto uses touchpad on desktop and direct input in Steam. Touchpad: drag to move, tap to click.",
                onPick = a.onTouch,
            )
            if (steam && s.oscMode != null) ChoiceRow(
                host, "osc", "On-screen controls", null,
                listOf(
                    SessionPrefs.OSC_AUTO to "Auto",
                    SessionPrefs.OSC_ALWAYS to "Always",
                    SessionPrefs.OSC_STEAM_QAM to "Steam + QAM",
                    SessionPrefs.OSC_NEVER to "Never",
                ), s.oscMode,
                note = "Auto shows all controls without a controller. Steam + QAM shows only those buttons.", onPick = a.onOsc,
            )
            if (steam && s.steamController != null) ChoiceRow(
                host, "controller", "Controller", "What your controller is to Steam. Applies next session.",
                listOf(
                    SessionPrefs.CONTROLLER_DECK to "Steam Deck controller",
                    SessionPrefs.CONTROLLER_XBOX360 to "Xbox 360 controller",
                ), s.steamController,
                note = "Steam Deck controller: Steam reads it as a Deck's own, with its Quick Access button and the device's gyro. " +
                    "Xbox 360 controller: the plain pad of earlier versions; Quick Access opens with Guide+A.",
                onPick = a.onSteamController,
            )
            if (steam) ChoiceRow(
                host, "back-actions", "Back", SessionPrefs.backActionsOrder(s.backActionsInverted),
                listOf(
                    false to SessionPrefs.BACK_MENU_THEN_QAM,
                    true to SessionPrefs.BACK_QAM_THEN_MENU,
                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
            )
        }
        SettingsGroup("Session") {
            ChoiceRow(
                host, "suspend", "Background behavior",
                "How this session behaves when the app leaves the screen or the display turns off.",
                listOf(
                    SessionPrefs.SUSPEND_AUTO to "Auto",
                    SessionPrefs.SUSPEND_MANUAL to "Manual",
                    SessionPrefs.SUSPEND_NEVER to "Never",
                ),
                s.suspendPolicy,
                note = "Auto pauses in the background and resumes when visible. Manual pauses there and waits for Resume. Never keeps the session running.",
                onPick = a.onSuspendPolicy,
            )
        }
        if (steam) SettingsGroup("Startup") {
            ToggleRow(
                host, "steam-startup", "Run Steam when DroidDeck starts",
                "Open the Steam session when you launch DroidDeck.",
                s.runSteamAtStartup, onChange = a.onRunSteamAtStartup,
            )
        }
        if (steam) SettingsGroup("Decky") {
            val updateAvailable = s.deckyInstalled != null && s.deckyLatestRelease != null &&
                s.deckyInstalled != s.deckyLatestRelease.tag
            val status = when {
                s.deckyStage != null -> s.deckyStage
                s.deckyChecking -> "Checking compatible releases…"
                s.deckyInstalled == null && s.deckyLatestRelease != null -> "Ready to install ${s.deckyLatestRelease.tag}."
                s.deckyInstalled == null -> "No compatible build found. Check again later."
                s.deckyLatestRelease == null -> "Installed · ${s.deckyInstalled}"
                updateAvailable -> "Update available · ${s.deckyLatestRelease.tag}"
                else -> "Up to date · ${s.deckyInstalled}"
            }
            SettingsRow("Loader", status) {
                val action = when {
                    s.deckyStage != null -> "Working…"
                    s.deckyChecking -> "Checking…"
                    s.deckyInstalled == null && s.deckyLatestRelease != null -> "Install latest"
                    s.deckyInstalled != null && updateAvailable -> "Update"
                    else -> "Check"
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
                        "Uninstall",
                        enabled = s.deckyStage == null && !s.deckySessionRunning,
                    ) { confirmDeckyRemoval = true }
                }
            }
            if (s.deckyInstalled != null) SettingsRow(
                "Decky",
                if (s.deckyEnabled) "Starts with Steam. Other apps on this device may be able to control Steam while enabled."
                else "Off. Decky stays off and Steam's remote debugging port stays closed.",
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
        if (steam && s.steamChannel != null) SettingsGroup("Client") {
            ToggleRow(
                host, "steamdeck", "Steam Deck mode",
                "Enables Steam's Deck interface and Quick Access performance overlay controls. Applies next session.",
                s.steamDeckMode, onChange = a.onSteamDeckMode,
            )
            if (s.steamDeckMode) ToggleRow(
                host, "mangoapp", "Performance overlay",
                "The Quick Access Menu's frame-rate and usage overlay. Turn off if games crash or go black with Deck mode on. Applies next session.",
                s.mangoapp, onChange = a.onMangoapp,
            )
            // Deck mode fixes the branch (SessionPrefs.steamChannel); the choice is for Deck mode off.
            if (s.steamDeckMode) SettingsRow("Client branch", "Steam Deck public beta: Deck mode needs it, so Steam doesn't keep offering an update") {}
            else ChoiceRow(
                host, "channel", "Client branch", "The Steam client build the session forces. Applies at the next session start; the client may update itself once.",
                listOf("publicbeta" to "Public beta", "steamdeck_publicbeta" to "Steam Deck public beta"), s.steamChannel,
                note = "Public beta is what every session ran on before. Steam Deck public beta is the one Armada bootstraps from, and the one Deck mode always uses.",
                onPick = a.onSteamChannel,
            )
        }
        if (steam && s.addedGamesDirs != null) SettingsGroup("Added games") {
            for (dir in s.addedGamesDirs) {
                val n = s.addedGames.count { it.folderPath.startsWith("$dir/") }
                ActionRow(
                    dir.substringAfterLast('/').ifEmpty { dir }, dir + " · " + (if (n == 0) "no game folders with a .exe found" else "$n game${if (n == 1) "" else "s"}") + ". " + stringResource(R.string.added_games_forget_hint),
                    "Forget", onClick = { a.onForgetAddedGamesDir(dir) },
                )
            }
            ActionRow(
                if (s.addedGamesDirs.isEmpty()) "Games folder" else "Another games folder",
                stringResource(R.string.added_games_import_hint),
                "Add…", onClick = a.onPickAddedGamesDir,
            )
            ToggleRow(
                host, "addedArt", "Artwork from Steam",
                "A game with no art of its own gets the store's capsule, header, hero and logo for the same title, looked up by folder name. Your own art wins: drop cover.jpg (or poster, boxart, folder, the folder's name), header.jpg, hero.jpg, logo.png or icon.png into the game's folder or its art subfolder.",
                s.addedGamesArt, onChange = a.onAddedGamesArt,
            )
            for (g in s.addedGames) ChoiceRow(
                host, "added:" + g.folderPath, g.folderName, "Launches ${g.exeName}" + (if (s.addedGamesDirs.size > 1) " · in " + g.folderPath.substringBeforeLast('/').substringAfterLast('/') else ""),
                g.candidates + ("__pick__" to "Choose another file…"), g.exePath,
                note = "The .exe files found in the game's folder; the one named after the folder, else the largest, is picked unless you choose.",
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
                host, "fill", "Stretch games to fill the screen",
                "Keeps games that resize their own window (FlatOut) full screen. Turn it off if a game shows up small in a corner (Quake 3). Applies next session.",
                s.forceFullscreen, onChange = a.onForceFullscreen,
            )
            if (s.stretch16x9 != null) ToggleRow(
                host, "stretch169", "Stretch 16:9 games to a narrower screen",
                "On a screen squarer than 16:9 (a foldable's inner panel), games render at 16:9 and are stretched to fill it: no bars, slightly wider-looking. Applies next session.",
                s.stretch16x9, onChange = a.onStretch16x9,
            )
        }
        if (steam && s.directAudio != null && s.mic != null) SettingsGroup("Audio") {
            ToggleRow(host, "da", "DirectAudio for games", "Bypasses PulseAudio for lower latency in games.", s.directAudio, onChange = a.onDirectAudio)
            ChoiceRow(
                host, "clientAudio", "Steam client audio", "Classic is the AAudio sink from 0.1.5. DirectAudio goes through the relay. Applies next session.",
                listOf("classic" to "Classic", "directaudio" to "DirectAudio"), if (s.clientDirectAudio) "directaudio" else "classic",
                onPick = { id -> a.onClientDirectAudio(id == "directaudio") },
            )
            ToggleRow(host, "mic", "Microphone", "Uses the device microphone for voice chat.", s.mic, onChange = a.onMic)
        }
        if (steam && s.gameStorage != null) SettingsGroup("Game storage") {
            val custom = s.gameStorage.isNotEmpty() && s.gameStorage != "off" && s.storageOptions.none { it.second == s.gameStorage }
            val options = buildList {
                add("" to ("Automatic - the SD card when one is in" + (if (s.storageOptions.isEmpty()) " (none right now)" else "")))
                add("off" to "Internal only")
                for ((label, path) in s.storageOptions) add(path to label)
                if (custom) add(s.gameStorage to "Folder: ${s.gameStorage}")
            }
            val open = host.open == "storage"
            SettingsRow(
                "Second library",
                stringResource(R.string.second_library_import_hint),
                highlighted = open,
            ) {
                androidx.compose.foundation.layout.Box {
                    ValueChip(options.firstOrNull { it.first == s.gameStorage }?.second?.substringBefore(" -") ?: "-", open) { host.open = if (open) null else "storage" }
                    AnchoredMenu(
                        open, onDismiss = { if (host.open == "storage") host.open = null }, title = "Second library",
                        note = "Games that stream assets from SD or shared storage may stutter. Keep them internal.",
                    ) { firstItemFocus ->
                        options.forEachIndexed { index, (path, label) ->
                            MenuItem(label, checked = path == s.gameStorage, focusRequester = if (index == 0) firstItemFocus else null) {
                                a.onGameStorage(path, if (path.isEmpty() || path == "off") "" else label.substringBefore(" ·"))
                                host.open = null
                            }
                        }
                        MenuItem("Choose a folder…", checked = false) { host.open = null; a.onPickGameStorageFolder() }
                    }
                }
            }
        }
        if (!steam && s.renderer != null) SettingsGroup("Renderer") {
            ChoiceRow(
                host, "renderer", "Desktop renderer", "Composites the desktop.",
                listOf("vulkan" to "vulkan - GPU", "gles2" to "gles2 - GPU (experimental)", "pixman" to "pixman - software"), s.renderer,
                note = "On Vulkan, programs on the desktop draw with the GPU in their own windows; if it cannot start, " +
                    "the desktop comes up on pixman. On pixman, games and emulators from the menu open full screen " +
                    "on the GPU instead (right-click one for a desktop window).",
                onPick = a.onRenderer,
            )
        }
    }
    if (confirmDeckyRemoval) AlertDialog(
        onDismissRequest = { confirmDeckyRemoval = false },
        title = { Text("Uninstall Decky Loader?") },
        text = { Text("Remove the loader and keep your plugins and settings.") },
        confirmButton = {
            TextButton(onClick = { confirmDeckyRemoval = false; a.onDeckyUninstall() }) { Text("Uninstall") }
        },
        dismissButton = { TextButton(onClick = { confirmDeckyRemoval = false }) { Text("Cancel") } },
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
        title = { Text("Custom resolution") },
        text = {
            androidx.compose.foundation.layout.Column {
                Text(
                    "The session's display size. It replaces the cap and the shape; a size that does not match the panel's shape gets bars.",
                    fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                androidx.compose.foundation.layout.Row(
                    verticalAlignment = androidx.compose.ui.Alignment.CenterVertically,
                    modifier = Modifier.padding(top = 12.dp),
                ) {
                    val numbers = androidx.compose.foundation.text.KeyboardOptions(keyboardType = androidx.compose.ui.text.input.KeyboardType.Number)
                    androidx.compose.material3.OutlinedTextField(
                        w, { v -> w = v.filter(Char::isDigit).take(4) }, label = { Text("Width") },
                        singleLine = true, keyboardOptions = numbers, modifier = Modifier.weight(1f),
                    )
                    Text("×", fontSize = 18.sp, modifier = Modifier.padding(horizontal = 10.dp))
                    androidx.compose.material3.OutlinedTextField(
                        h, { v -> h = v.filter(Char::isDigit).take(4) }, label = { Text("Height") },
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
                    "Between 320×240 and 3840×2160.", fontSize = 12.sp, color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        },
        confirmButton = { androidx.compose.material3.TextButton(enabled = parsed != null, onClick = { parsed?.let(onSave) }) { Text("Use") } },
        dismissButton = { androidx.compose.material3.TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
