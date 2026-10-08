package com.droiddeck.launcher.ui

import java.io.File
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R

import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.foundation.focusGroup
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.onFocusChanged
import android.provider.Settings
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.key
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.HomeApp
import com.droiddeck.launcher.frontend.Library
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.session.SessionPrefs
import kotlin.math.roundToInt
import kotlinx.coroutines.withTimeoutOrNull

class FrontEndState(
    val installed: String?,
    val ready: Boolean,
    val available: String?,
    val busy: Boolean,
    val stage: String,
    val percent: Int,
    val desktopInstalled: Boolean,
    val offlineAccount: String?,
    val offline: Boolean,
    /** The client's remembered accounts; the Setup page offers a choice from two on. */
    val steamAccounts: List<com.droiddeck.launcher.session.SteamAccounts.Account> = emptyList(),
    /** The account the next session will start as, when the user picked one. */
    val nextAccount: Long? = null,
    val frameGenLabel: String,
    val romsDir: String?,
    val logsEnabled: Boolean,
    /** Setup's Debugging tools toggle (AgentAccess): off unless the user turns it on. */
    val agentCommands: Boolean = false,
    val steamGames: List<Library.SteamGame>,
    val emulators: List<Library.Emulator>,
    val running: String?,
    val frameGen: FrameGen.Mode = FrameGen.Mode.OFF,
    val lossless: Lossless.State = Lossless.State.NONE,
    val shortcutPicker: Boolean = false,
    val shortcutLibraryScanning: Boolean = false,
    val gameSyncFolder: String? = null,
    val pageKey: String? = null,
    val theme: String = Themes.GRAPHITE,
    /** The language picked in Setup, or AppLanguage.SYSTEM to follow the system. */
    val language: String = "",
    val appScale: Int = com.droiddeck.launcher.core.AppUiPrefs.DEFAULT_SCALE,
    val isHomeApp: Boolean = false,
    val homeScreenEnabled: Boolean = false,
    val defaultHomeLabel: String? = null,
    val androidApps: List<HomeApp.LaunchableApp> = emptyList(),
    val secondScreenDisplays: List<SecondScreenDisplay> = emptyList(),
    val packages: List<PackageRow>? = null,
    val packageCatalogLoading: Boolean = false,
    val packageBusyId: String? = null,
    val packageStage: String? = null,
    val packagePercent: Int = -1,
    val sessionRunning: Boolean = false,
    val removalPending: Boolean = false,
    val runtimeActionsBlocked: Boolean = false,
    val backActionsInverted: Boolean = false,
    val buildLabel: String = "local",
    val oscMode: String = SessionPrefs.OSC_AUTO,
    val controller: com.droiddeck.launcher.input.ControllerPrefs.Settings? = null,
    val phantomProcessStatus: PhantomProcessStatus = PhantomProcessStatus.NOT_APPLICABLE,
    val showPhantomGate: Boolean = false,
    val launcherFullscreen: Boolean = true,
    val animationsEnabled: Boolean = true,
    /** The Flathub Store, a beta the user turns on in Setup. */
    val storeEnabled: Boolean = false,
    /** The Updates page: DroidDeck's own builds and the channel followed. */
    val updates: UpdatesState = UpdatesState(),
)

class FrontEndActions(
    val onPlay: () -> Unit,
    val onPlayDesktopUi: () -> Unit,
    val onSteamGame: (Library.SteamGame) -> Unit,
    val onGameShortcut: (Library.SteamGame) -> Unit = {},
    val onExportGameFile: (Library.SteamGame) -> Unit = {},
    val onSyncGameFiles: () -> Unit = {},
    val onStopGameFileSync: () -> Unit = {},
    val onCopyGameLink: (Library.SteamGame) -> Unit = {},
    val onDesktop: () -> Unit,
    val onEmulator: (Library.Emulator) -> Unit,
    /** A Flatpak app by id and name, from the Store. */
    val onFlatpakApp: (String, String) -> Unit = { _, _ -> },
    /** An app added on the Desktop page. */
    val onUserApp: (com.droiddeck.launcher.runtime.UserApps.App) -> Unit = {},
    val onRom: (Library.Rom) -> Unit,
    val onResume: () -> Unit,
    val onSteamSettings: () -> Unit,
    val onDesktopSettings: () -> Unit,
    val onInstallPackage: (String) -> Unit,
    val onRemovePackage: (String) -> Unit,
    val onRuntime: () -> Unit,
    val onFrameGenPick: (FrameGen.Mode) -> Unit,
    val onImportLossless: () -> Unit,
    val onProtons: () -> Unit,
    /** The Components page: FEX / DXVK / VKD3D-Proton per Proton. */
    val onComponents: (focusContent: Boolean) -> Unit,
    /** A game page's Manage saves: import a save zip into this game, or export its saves in a layout. */
    val onSaveImport: (Library.SteamGame) -> Unit = {},
    val onSaveExport: (Library.SteamGame, com.droiddeck.launcher.session.GameSaves.Layout) -> Unit = { _, _ -> },
    val onPerformance: () -> Unit,
    val onRoms: () -> Unit,
    val onFiles: () -> Unit,
    val onBrowseFiles: (File) -> Unit = {},
    val onLogs: () -> Unit,
    val onAgentCommands: () -> Unit = {},
    val onShareLogs: () -> Unit = {},
    val onClearLogs: () -> Unit = {},
    val onOffline: () -> Unit,
    /** A SteamID64 for the next session to start as. */
    val onPickAccount: (Long) -> Unit = {},
    val onPageBack: () -> Unit = {},
    val onTheme: (String) -> Unit = {},
    val onLanguage: (String) -> Unit = {},
    val onAppScale: (Int) -> Unit = {},
    val onLauncherFullscreen: (Boolean) -> Unit = {},
    val onAnimationsEnabled: (Boolean) -> Unit = {},
    val onStoreEnabled: (Boolean) -> Unit = {},
    val onHomeApp: () -> Unit = {},
    val onHomeScreen: (Boolean) -> Unit = {},
    val onAndroidApp: (HomeApp.LaunchableApp, Int?) -> Unit = { _, _ -> },
    val onBackActionsInverted: (Boolean) -> Unit = {},
    val onRefreshPhantomStatus: () -> Unit = {},
    val onOpenDeveloperOptions: (Int?) -> Unit = {},
    val onWirelessAdbPair: (String, Int, String, (String?) -> Unit) -> Unit,
    val onFindWirelessAdbPort: (String, (Int?) -> Unit) -> Unit = { _, done -> done(null) },
    val onWirelessAdbApply: (String, Int, Boolean, (String?) -> Unit) -> Unit,
    val onSetPhantomProcessLimit: (Boolean, (String?) -> Unit) -> Unit,
    val onCopyPhantomCommand: (Boolean) -> Unit = {},
    val onDismissPhantomGate: () -> Unit = {},
    val onStartWirelessAdbPairing: () -> Unit = {},
    val onOpenNotificationSettings: () -> Unit = {},
    val controller: ControllerActions? = null,
    val updates: UpdatesActions = UpdatesActions(),
)

internal object Motion {
    var scale by mutableStateOf(1f)
        private set
    /** App animations can be disabled independently; Android's "Remove animations" always wins. */
    fun refresh(context: android.content.Context) {
        scale = if (SessionPrefs.animationsEnabled(context))
            Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        else 0f
    }
    val Ease = CubicBezierEasing(0.2f, 0.8f, 0.2f, 1f)
    /** A wall-clock wait (a coroutine delay) scaled as the animations around it are. Compose already
     *  scales its own animation clock by the same setting, so animation specs take unscaled times. */
    fun ms(base: Int) = (base * scale).roundToInt()
    fun <T> tw(base: Int, delay: Int = 0, easing: Easing = Ease): FiniteAnimationSpec<T> = if (scale == 0f) snap() else tween(base, delay, easing)
    fun <T> sp(damping: Float = 0.7f, stiffness: Float = Spring.StiffnessMediumLow): FiniteAnimationSpec<T> =
        if (scale == 0f) snap() else spring(damping, stiffness)
}

private val Shape10 = RoundedCornerShape(10.dp)

internal val Shape12 = RoundedCornerShape(12.dp)

internal fun hueOf(name: String) = (name.hashCode().toUInt() % 360u).toFloat()

internal fun tint(h: Float, s: Float = 0.7f, v: Float = 0.58f) = Color.hsv(h, s, v)

internal fun artBrush(h: Float) = Brush.linearGradient(listOf(tint(h), tint((h + 32f) % 360f, 0.65f, 0.30f), tint((h + 64f) % 360f, 0.6f, 0.14f)))

@Composable
internal fun Rise(i: Int, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    val state = remember { MutableTransitionState(false) }.apply { targetState = true }
    AnimatedVisibility(
        visibleState = state, modifier = modifier,
        enter = fadeIn(Motion.tw(450, i * 60)) + slideInVertically(Motion.tw(450, i * 60)) { it / 3 },
        exit = fadeOut(Motion.tw(120)),
        label = "rise",
    ) { content() }
}

@Composable
private fun Modifier.staggerIn(i: Int): Modifier {
    val t = remember { Animatable(0f) }
    LaunchedEffect(Unit) { t.animateTo(1f, Motion.tw(360, i * 30)) }
    return graphicsLayer { alpha = t.value; translationY = (1f - t.value) * 10.dp.toPx() }
}

@Composable
internal fun Modifier.shine(trigger: Boolean, strength: Float = 0.22f): Modifier {
    val x = remember { Animatable(-1f) }
    LaunchedEffect(trigger) { if (trigger) { x.snapTo(-1f); x.animateTo(1f, Motion.tw(800)) } }
    return drawWithContent {
        drawContent()
        val p = x.value
        if (p > -1f && p < 1f) {
            val w = size.width
            val c = w * 0.5f + p * w * 0.9f
            drawRect(
                Brush.linearGradient(
                    listOf(Color.Transparent, Color.White.copy(alpha = strength), Color.Transparent),
                    start = Offset(c - w * 0.35f, 0f), end = Offset(c + w * 0.35f, size.height),
                ),
            )
        }
    }
}

/**
 * Controller focus on the front end: each rail item's requester and the page's main button (Play,
 * Open desktop, Open <emulator>, Launch...). The pane leaves, to the left, for the selected rail
 * item - whatever tile or button it leaves from - and remembers that control, so coming back in
 * lands on it again; a page not yet visited enters on its main button.
 */
internal class FrontFocus {
    val rail = HashMap<String, FocusRequester>()
    val primary = FocusRequester()
    var primaryAttached by mutableStateOf(0)
    var focusedRail by mutableStateOf<String?>(null)
    fun railFor(key: String): FocusRequester = rail.getOrPut(key) { FocusRequester() }
    // The pane's controls by id (a tile's key, a button's label), how many of each are on screen,
    // and the last one focused.
    val items = HashMap<String, FocusRequester>()
    val attached = HashMap<String, Int>()
    // State, so the controller hints follow it: A launches from a game row but selects elsewhere.
    var last by mutableStateOf<String?>(null)
    // The first tile of the page's grid: Down from the page's buttons goes to it, not to whichever
    // tile happens to sit under the button.
    val firstTile = FocusRequester()
    var firstTileAttached = 0
    fun paneEntry(): FocusRequester {
        val id = last
        return when {
            id == PRIMARY && primaryAttached > 0 -> primary
            id != null && id != PRIMARY && (attached[id] ?: 0) > 0 -> items.getValue(id)
            primaryAttached > 0 -> primary
            else -> FocusRequester.Default
        }
    }
    companion object { const val PRIMARY = "\u0000primary" }
}

/** Down from this button goes to the first tile of the page's grid, when there is one. */
@Composable
internal fun Modifier.downToFirstTile(): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    return this.focusProperties { down = if (ff.firstTileAttached > 0) ff.firstTile else FocusRequester.Default }
}

/** Marks the first tile of the page's grid. */
@Composable
internal fun Modifier.firstTile(): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    DisposableEffect(Unit) {
        ff.firstTileAttached++
        onDispose { ff.firstTileAttached-- }
    }
    return this.focusRequester(ff.firstTile)
}

/** Lets the pane come back to this control: it is remembered when focused. */
@Composable
internal fun Modifier.paneItem(id: String): Modifier {
    val ff = LocalFrontFocus.current ?: return this
    val req = remember(id) { ff.items.getOrPut(id) { FocusRequester() } }
    DisposableEffect(id) {
        ff.attached[id] = (ff.attached[id] ?: 0) + 1
        onDispose { ff.attached[id] = (ff.attached[id] ?: 1) - 1 }
    }
    return this.focusRequester(req).onFocusChanged { if (it.isFocused) ff.last = id }
}

internal val LocalFrontFocus = staticCompositionLocalOf<FrontFocus?> { null }

/** The pane's exit animation (170ms) and a frame's margin: a new page is in after this. */
private const val PAGE_EXIT_MS = 200

/**
 * Asks [target] for focus once a frame until [done] (or a focus request lands), for at most half a
 * second: a control can take focus only once it is laid out, and that is a frame or two, not a
 * fixed number of milliseconds.
 */
internal suspend fun focusWithinFrames(done: () -> Boolean, target: () -> FocusRequester) {
    repeat(30) {
        androidx.compose.runtime.withFrameNanos { }
        if (done()) return
        if (runCatching { target().requestFocus() }.isSuccess && done()) return
    }
}

@Composable
fun FrontEndScreen(s: FrontEndState, a: FrontEndActions, page: (@Composable () -> Unit)? = null) {
    val frontFocus = remember { FrontFocus() }
    CompositionLocalProvider(LocalFrontFocus provides frontFocus) { FrontEndScreenBody(s, a, page, frontFocus) }
}

@OptIn(ExperimentalComposeUiApi::class)
@Composable
private fun FrontEndScreenBody(s: FrontEndState, a: FrontEndActions, page: (@Composable () -> Unit)?, frontFocus: FrontFocus) {
    var selected by rememberSaveable { mutableStateOf(if (s.shortcutPicker) "games" else "steam") }
    LaunchedEffect(s.shortcutPicker) { if (s.shortcutPicker) selected = "games" }
    var showWirelessAdbFix by rememberSaveable { mutableStateOf(false) }
    var showDeveloperDisplayChoice by rememberSaveable { mutableStateOf(false) }
    var wirelessAdbDesiredEnabled by rememberSaveable { mutableStateOf(false) }
    var appToChooseDisplay by remember { mutableStateOf<HomeApp.LaunchableApp?>(null) }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ctx = LocalContext.current
    val phantomGateVisible = s.showPhantomGate && PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    val processSettingsPageVisible = phantomGateVisible || showWirelessAdbFix || showDeveloperDisplayChoice
    val requestDeveloperOptions = {
        if (s.secondScreenDisplays.isEmpty()) a.onOpenDeveloperOptions(null)
        else showDeveloperDisplayChoice = true
    }
    val requestWirelessAdbFix: (Boolean) -> Unit = { enabled ->
        wirelessAdbDesiredEnabled = enabled
        showWirelessAdbFix = true
    }
    LaunchedEffect(s.showPhantomGate, s.phantomProcessStatus) {
        if (phantomGateVisible) {
            while (true) {
                kotlinx.coroutines.delay(2_000)
                a.onRefreshPhantomStatus()
            }
        }
    }
    BackHandler(enabled = !processSettingsPageVisible && s.pageKey != null && page != null) { a.onPageBack() }
    // Back (and B) from a ROM, an emulator or an added app steps out one level, as its "‹" link
    // does, instead of leaving the app: a ROM -> its emulator, the others -> Desktop.
    BackHandler(
        enabled = !processSettingsPageVisible && (s.pageKey == null || page == null) &&
            (selected.startsWith("emu:") || selected.startsWith("rom:") || selected.startsWith("user:")),
    ) {
        selected = if (selected.startsWith("rom:")) "emu:" + selected.removePrefix("rom:").substringBefore(':') else "desktop"
    }
    LaunchedEffect(s.isHomeApp) { if (!s.isHomeApp && selected == "android-apps") selected = "steam" }
    // The Store turned off in Setup takes its page with it.
    LaunchedEffect(s.storeEnabled) { if (!s.storeEnabled && selected == "store") selected = "steam" }
    // The last game uninstalled leaves the Games tab on its empty state.
    LaunchedEffect(s.steamGames.isEmpty()) {
        if (s.steamGames.isEmpty() && selected.startsWith("app:")) selected = "games"
    }
    remember { Motion.refresh(ctx); true }

    val railSelection = when {
        s.pageKey == "performance" || s.pageKey == "protons" || s.pageKey == "controller-mapping" -> "setup"
        s.pageKey?.startsWith("settings:steam") == true -> "steam"
        s.pageKey?.startsWith("settings:") == true -> "desktop"
        selected.startsWith("app:") -> "games"
        selected.startsWith("emu:") || selected.startsWith("rom:") || selected.startsWith("user:") -> "desktop"
        else -> s.pageKey ?: selected
    }
    // Bumped each time a rail item is picked, so a controller moves on into the new page.
    var railPicks by remember { mutableStateOf(0) }
    val showRailPage: (String, Boolean) -> Unit = { key, focusContent ->
        // Components is a full page like Protons or Performance, opened over the current rail
        // selection rather than replacing it.
        if (key == "components") a.onComponents(focusContent)
        else {
            if (s.pageKey != null) a.onPageBack()
            selected = key
        }
    }
    val onRailFocus: (String) -> Unit = { key ->
        if (key != railSelection) showRailPage(key, false)
    }
    val onRailSelect: (String) -> Unit = { key ->
        showRailPage(key, true)
        railPicks++
    }
    val inputModeManager = LocalInputModeManager.current
    val window = LocalWindowInfo.current
    var anyFocused by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier.fillMaxSize().background(colors.background)
            .then(if (s.launcherFullscreen) Modifier else Modifier.systemBarsPadding())
            .onFocusChanged { anyFocused = it.hasFocus },
    ) {
        // Start controllers on the current page's main action, else on the rail.
        LaunchedEffect(processSettingsPageVisible) {
            if (processSettingsPageVisible) return@LaunchedEffect
            snapshotFlow { window.isWindowFocused }.first { it }
            if (inputModeManager.inputMode != InputMode.Keyboard) inputModeManager.requestInputMode(InputMode.Keyboard)
            focusWithinFrames({ anyFocused }) { if (frontFocus.primaryAttached > 0) frontFocus.primary else frontFocus.railFor(railSelection) }
        }
        // A tile or button that opens a page goes away with the page it was on, and focus with it;
        // the pad then had nothing to move from (a press landed back on the rail's first item). So
        // once the new page is in, a controller lands on its main button.
        // Backing out of a page: it stays on screen while it leaves (and holds focus there), so hand
        // focus back now to the control it was opened from. Not when the page closed because
        // another rail item was picked: focus is on the rail then, and stays there.
        var openPage by remember { mutableStateOf<String?>(null) }
        LaunchedEffect(s.pageKey) {
            val closed = openPage != null && s.pageKey == null
            openPage = s.pageKey
            if (!closed || frontFocus.focusedRail != null || processSettingsPageVisible || inputModeManager.inputMode != InputMode.Keyboard) return@LaunchedEffect
            withFrameNanos { }
            runCatching { frontFocus.paneEntry().requestFocus() }
        }
        LaunchedEffect(selected, s.pageKey, processSettingsPageVisible) {
            if (processSettingsPageVisible) return@LaunchedEffect
            // Past the old page's exit (170ms), then the first frame the new page takes focus.
            kotlinx.coroutines.delay(Motion.ms(PAGE_EXIT_MS).toLong())
            if (anyFocused || inputModeManager.inputMode != InputMode.Keyboard) return@LaunchedEffect
            focusWithinFrames({ anyFocused }) { if (frontFocus.primaryAttached > 0) frontFocus.primary else frontFocus.railFor(railSelection) }
        }
        // A rail item picked with a controller moves on into its page, once the page is in.
        LaunchedEffect(railPicks) {
            if (railPicks == 0 || processSettingsPageVisible) return@LaunchedEffect
            kotlinx.coroutines.delay(Motion.ms(PAGE_EXIT_MS).toLong())
            if (inputModeManager.inputMode != InputMode.Keyboard) return@LaunchedEffect
            // Waits for the new page's main button rather than a fixed guess at how long it takes.
            withTimeoutOrNull(1_000) { snapshotFlow { frontFocus.primaryAttached > 0 }.first { it } } ?: return@LaunchedEffect
            focusWithinFrames({ frontFocus.focusedRail == null && anyFocused }) { frontFocus.paneEntry() }
        }
        val paneFocus = Modifier
            .focusProperties { enter = { frontFocus.paneEntry() } }
            .focusGroup()
        val railFocus = Modifier
            .focusProperties { enter = { frontFocus.railFor(railSelection) } }
            .focusGroup()
        Row(modifier = Modifier.fillMaxSize()) {
            SideRail(s, railSelection, onRailSelect, onRailFocus, a, railFocus.fillMaxHeight())
            Box(Modifier.width(1.dp).fillMaxHeight().background(pal.line))
            Column(modifier = Modifier.weight(1f).fillMaxHeight()) {
                Pane(
                    s, selected, a, page, Modifier.weight(1f).fillMaxWidth().then(paneFocus), { selected = it },
                    onAndroidAppClick = { app ->
                        if (s.secondScreenDisplays.isEmpty()) a.onAndroidApp(app, null)
                        else appToChooseDisplay = app
                    },
                    onOpenDeveloperOptions = requestDeveloperOptions,
                    onRequestWirelessAdb = requestWirelessAdbFix,
                )
            }
        }

        appToChooseDisplay?.let { app ->
            val secondaryDisplay = s.secondScreenDisplays.firstOrNull()
            ChooseAppDisplayDialog(
                app = app,
                secondaryDisplay = secondaryDisplay,
                onPrimary = {
                    appToChooseDisplay = null
                    a.onAndroidApp(app, Display.DEFAULT_DISPLAY)
                },
                onSecondary = {
                    appToChooseDisplay = null
                    secondaryDisplay?.let { a.onAndroidApp(app, it.id) }
                },
                onDismiss = { appToChooseDisplay = null },
            )
        }
        if (phantomGateVisible && !showWirelessAdbFix) {
            Box(Modifier.fillMaxSize().background(colors.background)) {
                PhantomProcessGatePage(
                    status = s.phantomProcessStatus,
                    onDismiss = a.onDismissPhantomGate,
                    onOpenDeveloperOptions = requestDeveloperOptions,
                    onFixWithWirelessDebugging = {
                        a.onStartWirelessAdbPairing()
                        requestDeveloperOptions()
                    },
                    onEnterAddressManually = { requestWirelessAdbFix(false) },
                    onCopyCommand = { a.onCopyPhantomCommand(false) },
                    onOpenNotificationSettings = a.onOpenNotificationSettings,
                )
            }
        }
        if (showWirelessAdbFix) {
            Box(Modifier.fillMaxSize().background(colors.background)) {
                WirelessAdbFixPage(
                    onBack = { showWirelessAdbFix = false },
                    desiredEnabled = wirelessAdbDesiredEnabled,
                    onOpenDeveloperOptions = requestDeveloperOptions,
                    onPair = a.onWirelessAdbPair,
                    onFindConnectPort = a.onFindWirelessAdbPort,
                    onApply = a.onWirelessAdbApply,
                )
            }
        }
        if (showDeveloperDisplayChoice) {
            DeveloperDisplayChoiceDialog(
                displays = s.secondScreenDisplays.map { display ->
                    display.id to if (s.secondScreenDisplays.size == 1) stringResource(R.string.screen_bottom) else display.label
                },
                onMainScreen = {
                    showDeveloperDisplayChoice = false
                    a.onOpenDeveloperOptions(null)
                },
                onSecondaryScreen = { displayId ->
                    showDeveloperDisplayChoice = false
                    a.onOpenDeveloperOptions(displayId)
                },
                onDismiss = { showDeveloperDisplayChoice = false },
            )
        }
    }
    val hasBackTarget = (s.pageKey != null && page != null) ||
        ((s.pageKey == null || page == null) &&
            (selected.startsWith("emu:") || selected.startsWith("rom:") || selected.startsWith("user:")))
    // At the top of a section, Back goes to the rail - the launcher itself is never backed out of.
    BackHandler(enabled = !processSettingsPageVisible && !hasBackTarget) {
        if (inputModeManager.inputMode != InputMode.Keyboard) inputModeManager.requestInputMode(InputMode.Keyboard)
        runCatching { frontFocus.railFor(railSelection).requestFocus() }
    }
}
