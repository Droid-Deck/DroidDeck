package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.input.SecondScreenMode
import com.droiddeck.launcher.session.SessionPrefs

/**
 * Debug-only support for Compose previews: the production theme around a screen, and sample state and no-op actions so
 * production composables render without a device, a runtime or a network. See docs/compose-previews.md.
 */

/** The sizes the previews come in. The Duo's top screen is 1920x1080 at xhdpi, i.e. 960x540 dp. */
@Preview(name = "Handheld", widthDp = 960, heightDp = 540, device = "spec:width=960dp,height=540dp,dpi=320,orientation=landscape")
@Preview(name = "Portrait", widthDp = 540, heightDp = 960, device = "spec:width=540dp,height=960dp,dpi=320")
internal annotation class DroidDeckPreviews

@Composable
internal fun PreviewHost(content: @Composable () -> Unit) {
    DroidDeckTheme {
        Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) { content() }
    }
}

/** A case the render test draws: a name for its PNG and the preview's content. */
internal class PreviewCase(val name: String, val content: @Composable () -> Unit) {
    override fun toString() = name
}

internal val sampleGames: List<Library.SteamGame> = listOf(
    "Portal 2" to 620, "Hades" to 1145360, "Celeste" to 504230, "Stardew Valley" to 413150, "Hollow Knight" to 367520,
    "Balatro" to 2379780, "Dead Cells" to 588650, "Terraria" to 105600, "Vampire Survivors" to 1794680, "Slay the Spire" to 646570,
).mapIndexed { i, (name, appId) ->
    Library.SteamGame(appId, name, art = null, library = "steam", lastPlayed = 1_700_000_000_000L - i * 86_400_000L)
}

internal fun sampleState(
    games: List<Library.SteamGame> = sampleGames,
    navigate: String? = null,
    ready: Boolean = true,
): FrontEndState = FrontEndState(
    installed = "ubuntu-r1", ready = ready, available = "ubuntu-r1", busy = false, stage = "", percent = 0,
    desktopInstalled = true, offlineAccount = null, offline = false, frameGenLabel = "Off", romsDir = null, logsEnabled = true,
    steamGames = games, emulators = emptyList(), running = null, navigate = navigate,
)

internal val sampleActions: FrontEndActions = FrontEndActions(
    onPlay = {}, onPlayDesktopUi = {}, onSteamGame = {}, onDesktop = {}, onEmulator = {}, onRom = {}, onResume = {},
    onSteamSettings = {}, onDesktopSettings = {}, onInstallPackage = {}, onRemovePackage = {}, onRuntime = {},
    onFrameGenPick = {}, onImportLossless = {}, onProtons = {}, onComponents = {}, onPerformance = {}, onRoms = {},
    onFiles = {}, onLogs = {}, onOffline = {},
    onWirelessAdbPair = { _, _, _, _ -> }, onWirelessAdbApply = { _, _, _, _ -> }, onSetPhantomProcessLimit = { _, _ -> },
)

/** The in-game menu's actions with settled sample values; its switches do nothing. */
internal fun sampleDrawerActions(steam: Boolean = true, desktop: Boolean = false): DrawerActions = DrawerActions(
    steam = steam, desktop = desktop, title = if (!steam && !desktop) "Hades" else null,
    isHomeApp = false, androidApps = emptyList(),
    hudOn = true, onHud = {},
    frameGen = FrameGen.Mode.OFF, onFrameGenPick = {},
    lossless = Lossless.State.NONE,
    oscMode = SessionPrefs.OSC_AUTO, onOsc = {},
    suspendPolicy = SessionPrefs.SUSPEND_MANUAL, onSuspendPolicy = {},
    backActionsInverted = false, onBackActionsInverted = {},
    touchMode = SessionPrefs.TOUCH_AUTO, onTouch = {}, touchAuto = "Auto (direct)",
    fexPreset = "", onFexPreset = {},
    secondScreenMode = SecondScreenMode.NONE, onSecondScreenMode = {},
    secondScreenDisplays = emptyList(), selectedSecondScreenDisplay = -1, onSecondScreenDisplay = {},
    onImportLossless = {}, onKeyboard = {}, onHardwareKeyboard = {},
    onSteamMenu = if (steam) ({}) else null, onQam = if (steam) ({}) else null,
    onLaunchAndroidApp = { _, _ -> }, onBackground = {}, onShareLogs = {}, onStop = {}, onClose = {},
)
