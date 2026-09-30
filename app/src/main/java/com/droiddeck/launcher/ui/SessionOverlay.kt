package com.droiddeck.launcher.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Menu
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.ui.draw.clipToBounds
import android.view.Display
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.material3.ButtonDefaults
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.interaction.PressInteraction
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.HomeApp
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.res.stringResource
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenMode
import kotlinx.coroutines.flow.collect

private val drawerPageTitles = listOf("Display", "Controls", "Components", "Settings")

/** One icon per drawer page, in page order (QAM-style tabs). */
private val drawerPageIcons = listOf(Icons.Outlined.DesktopWindows, Icons.Outlined.SportsEsports, Icons.Outlined.Layers, Icons.Outlined.Settings)

private val drawerPageEntries = listOf("hud", "touch", "cmp-proton", "suspend")

private class DrawerFocus {
    private val requesters = HashMap<String, FocusRequester>()
    private val last = arrayOfNulls<String>(DRAWER_PAGES)
    var focused by mutableStateOf<String?>(null)
        private set

    private fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    fun track(page: Int, key: String): Modifier = Modifier.focusRequester(requester(key)).onFocusChanged {
        if (it.isFocused) {
            last[page] = key
            focused = key
        } else if (focused == key) focused = null
    }

    fun target(page: Int) = last[page] ?: drawerPageEntries[page]
    fun request(key: String) = runCatching { requester(key).requestFocus() }
    fun forget(page: Int) { last[page] = null }
}

/** Everything the drawer shows and does. */
class DrawerActions(
    val steam: Boolean,
    /** The drawer's heading: the emulator for a program from the rail, else Steam or Desktop. */
    val title: String? = null,
    val isHomeApp: Boolean,
    val androidApps: List<HomeApp.LaunchableApp>,
    val hudOn: Boolean,
    val frameGenEngine: String,
    val frameGenMultiplier: Int,
    val lsfgReady: Boolean,
    val oscMode: String,
    val suspendPolicy: String,
    val backActionsInverted: Boolean,
    val touchMode: String,
    val touchAuto: String,
    val shapeMode: String,
    val fexPreset: String,
    /** Steam only: games stretched to the screen's size, changed live (null = not Steam). */
    val fillScreen: Boolean? = null,
    val secondScreenMode: SecondScreenMode,
    val secondScreenDisplays: List<SecondScreenDisplay>,
    val selectedSecondScreenDisplay: Int,
    val onHud: (Boolean) -> Unit,
    val onFrameGenPick: (engine: String, multiplier: Int) -> Unit,
    /** The Android keyboard (text, turned into key presses). */
    val onKeyboard: () -> Unit,
    /** The on-screen PC keyboard: real keys, Esc, F1-F12, Ctrl, Alt... */
    val onHardwareKeyboard: () -> Unit,
    val onSteamMenu: (() -> Unit)?,
    val onQam: (() -> Unit)?,
    /** Steam sessions: end Big Picture and open the desktop with Steam's desktop client in it. */
    val onSwitchToDesktop: (() -> Unit)? = null,
    val onOsc: (String) -> Unit,
    val onSuspendPolicy: (String) -> Unit,
    val onBackActionsInverted: (Boolean) -> Unit,
    val onTouch: (String) -> Unit,
    val onShape: (String) -> Unit,
    val onFexPreset: (String) -> Unit,
    val onFillScreen: (Boolean) -> Unit = {},
    val onSecondScreenMode: (SecondScreenMode) -> Unit,
    val onSecondScreenDisplay: (Int) -> Unit,
    val onLaunchAndroidApp: (HomeApp.LaunchableApp, Int?) -> Unit,
    val onBackground: () -> Unit,
    val onShareLogs: () -> Unit,
    val onStop: () -> Unit,
    /** Stop with the dialog's button's bounds on screen, for the flood to grow out of; null falls back to [onStop]. */
    val onStopFrom: ((androidx.compose.ui.geometry.Rect?) -> Unit)? = null,
    val onClose: () -> Unit,
    /** Components tab: every Proton with what it uses; null until first read. */
    val components: ComponentsManager.Snapshot? = null,
    /** Re-reads the Protons (and runs swaps that waited for a game to close). */
    val onComponentsRefresh: () -> Unit = {},
    /** Swaps [value] ("orig:<build>" or a stored package file) into a Proton's component. */
    val onComponentSwap: (protonId: String, comp: String, value: String) -> Unit = { _, _, _ -> },
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
fun SessionDrawer(open: Boolean, page: Int, controllerActive: Boolean, onPageChange: (Int) -> Unit, a: DrawerActions) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val host = rememberMenuHost()
    var androidAppsExpanded by rememberSaveable { mutableStateOf(false) }
    var appToChooseDisplay by remember { mutableStateOf<HomeApp.LaunchableApp?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    val pageScroll = remember { List(DRAWER_PAGES) { ScrollState(0) } }
    val veil by animateFloatAsState(if (open) 1f else 0f, Motion.tw(260), label = "veil")
    val focus = remember { DrawerFocus() }
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = open) {
        if (host.open != null) host.open = null else a.onClose()
    }
    BackHandler(enabled = open && confirmStop) { confirmStop = false }
    LaunchedEffect(page) { host.open = null; appToChooseDisplay = null }
    LaunchedEffect(open, page) { if (open && page == DRAWER_PAGE_COMPONENTS) a.onComponentsRefresh() }
    LaunchedEffect(open, controllerActive) {
        if (open && !controllerActive) focusManager.clearFocus(force = true)
    }
    LaunchedEffect(open, page, controllerActive, host.open, appToChooseDisplay, confirmStop) {
        if (!open) {
            host.open = null
            confirmStop = false
        } else if (controllerActive) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (host.open != null || appToChooseDisplay != null || confirmStop) return@LaunchedEffect
            val target = focus.target(page)
            repeat(24) {
                androidx.compose.runtime.withFrameNanos { }
                focus.request(target)
                if (focus.focused == target) return@LaunchedEffect
            }
            focus.forget(page)
            focus.request(drawerPageEntries[page])
        }
    }
    if (open || veil > 0.01f) Box(
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color(0x8A000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { host.open = null; a.onClose() },
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        // A 4:3 or near-square screen (under 440dp tall) gets one-line shortcuts and a shorter tab row,
        // so the settings keep most of the height; a narrow one keeps some of the game in view.
        val short = maxHeight < 440.dp
        val sheetWidth = minOf(360.dp, maxWidth * 0.92f)
        AnimatedVisibility(
            open,
            enter = slideInHorizontally(Motion.sp(0.8f, Spring.StiffnessLow)) { it } + fadeIn(Motion.tw(220)),
            exit = slideOutHorizontally(Motion.tw(240)) { it } + fadeOut(Motion.tw(200)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(sheetWidth)
                    .background(pal.background.copy(alpha = 0.97f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .focusGroup()
                    .controllerBack {
                        if (host.open != null) host.open = null else a.onClose()
                    }
                    .padding(if (short) 12.dp else 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().heightIn(min = if (short) 40.dp else 44.dp)) {
                    Text(a.title ?: if (a.steam) "Steam" else "Desktop", fontSize = if (short) 18.sp else 20.sp, fontWeight = FontWeight.Bold,
                        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    StopSessionButton(modifier = focus.track(page, "stop")) { host.open = null; confirmStop = true }
                }
                if (a.onSteamMenu != null && a.onQam != null) {
                    val qamInteraction = remember { MutableInteractionSource() }
                    var qamStartedOnPress by remember { mutableStateOf(false) }
                    LaunchedEffect(qamInteraction) {
                        qamInteraction.interactions.collect { interaction ->
                            if (interaction is PressInteraction.Press) {
                                qamStartedOnPress = true
                                host.open = null
                                a.onQam.invoke()
                            }
                        }
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        modifier = Modifier.fillMaxWidth().padding(top = if (short) 8.dp else 12.dp),
                    ) {
                        QuickAction("Steam menu", Icons.Outlined.Menu, Modifier.weight(1f).then(focus.track(page, "steam")), compact = short) {
                            host.open = null
                            a.onSteamMenu.invoke()
                        }
                        QuickAction(
                            "Quick access", Icons.Outlined.MoreHoriz,
                            Modifier.weight(1f).then(focus.track(page, "qam")).semantics { contentDescription = "Open Quick Access Menu" },
                            compact = short,
                            interactionSource = qamInteraction,
                            onConfirm = { host.open = null; a.onQam.invoke() },
                        ) {
                            host.open = null
                            if (!qamStartedOnPress) a.onQam.invoke()
                            qamStartedOnPress = false
                        }
                        // Big Picture's own "Switch to Desktop" waits on SteamOS Manager for ever
                        // here; this does the switch without the client.
                        if (a.onSwitchToDesktop != null) {
                            QuickAction("Desktop", Icons.Outlined.DesktopWindows, Modifier.weight(1f).then(focus.track(page, "desktop")), compact = short) {
                                host.open = null
                                a.onSwitchToDesktop.invoke()
                            }
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = if (short) 6.dp else 12.dp),
                ) {
                    DrawerBumper("LB", "Previous page", modifier = focus.track(page, "prev")) {
                        host.open = null; onPageChange((page + DRAWER_PAGES - 1) % DRAWER_PAGES)
                    }
                    DrawerPageTabs(page = page, compact = short, modifier = Modifier.weight(1f)) { index -> host.open = null; onPageChange(index) }
                    DrawerBumper("RB", "Next page", modifier = focus.track(page, "next")) {
                        host.open = null; onPageChange((page + 1) % DRAWER_PAGES)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(pageScroll[page]),
                ) { CompositionLocalProvider(LocalChipMinWidth provides 120.dp) {
                    when (page) {
                        0 -> SettingsGroup("Display") {
                            ToggleRow(host, "hud", "Performance HUD", null, a.hudOn,
                                chipModifier = focus.track(page, "hud"), onChange = a.onHud)
                            if (a.fillScreen != null) ToggleRow(
                                host, "fill", "Stretch games to fill", null, a.fillScreen,
                                chipModifier = focus.track(page, "fill"), onChange = a.onFillScreen,
                            )
                            val fgOpen = host.open == "fg"
                            val fgLabel = FrameGen.label(a.frameGenEngine, a.frameGenMultiplier)
                            SettingsRow("Frame generation", null, highlighted = fgOpen) {
                                Box {
                                    ValueChip(fgLabel, fgOpen, modifier = focus.track(page, "fg")) { host.open = if (fgOpen) null else "fg" }
                                    FrameGenMenu(host, a.frameGenEngine, a.frameGenMultiplier, a.lsfgReady, a.onFrameGenPick)
                                }
                            }
                        }
                        DRAWER_PAGE_COMPONENTS -> ComponentsDrawerPage(host, a) { key -> focus.track(page, key) }
                        1 -> {
                            SettingsGroup("Controls") {
                                ChoiceRow(host, "touch", "Touch", null,
                                    listOf(SessionPrefs.TOUCH_AUTO to "Auto (${a.touchAuto})", SessionPrefs.TOUCH_PAD to "Touchpad", SessionPrefs.TOUCH_DIRECT to "Direct"),
                                    a.touchMode, chipModifier = focus.track(page, "touch"), onPick = a.onTouch)
                                ChoiceRow(host, "osc", "On-screen controls", null,
                                    if (a.steam) listOf(SessionPrefs.OSC_AUTO to "Auto", SessionPrefs.OSC_ALWAYS to "Always", SessionPrefs.OSC_STEAM_QAM to "Steam + QAM", SessionPrefs.OSC_NEVER to "Never")
                                    else listOf(SessionPrefs.OSC_AUTO to "Auto", SessionPrefs.OSC_ALWAYS to "Always", SessionPrefs.OSC_NEVER to "Never"),
                                    a.oscMode, chipModifier = focus.track(page, "osc"), onPick = a.onOsc)
                                if (a.steam) ChoiceRow(host, "back-actions", "Back", null,
                                    listOf(false to SessionPrefs.BACK_MENU_THEN_QAM, true to SessionPrefs.BACK_QAM_THEN_MENU),
                                    a.backActionsInverted, chipModifier = focus.track(page, "back-actions"), onPick = a.onBackActionsInverted)
                            }
                            SettingsGroup("Keyboard") {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                    DrawerOutlineButton("PC keyboard", modifier = Modifier.weight(1f).height(48.dp).then(focus.track(page, "hardware"))) {
                                        host.open = null; a.onHardwareKeyboard()
                                    }
                                    DrawerOutlineButton("Android", modifier = Modifier.weight(1f).height(48.dp).then(focus.track(page, "android"))) {
                                        host.open = null; a.onKeyboard()
                                    }
                                }
                            }
                            if (a.steam && a.secondScreenDisplays.isNotEmpty()) SettingsGroup("Second screen") {
                                ChoiceRow(host, "second-screen-mode", "Shows", null,
                                    (listOf(SecondScreenMode.NONE, SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.TERMINAL) +
                                        (if (com.droiddeck.launcher.session.SessionState.deckPad) listOf(SecondScreenMode.DECK_CONTROLS) else emptyList()))
                                        .map { it to it.label },
                                    a.secondScreenMode, chipModifier = focus.track(page, "second-screen-mode"), onPick = a.onSecondScreenMode)
                                if (a.secondScreenDisplays.size > 1) ChoiceRow(host, "second-screen-display", "Display", null,
                                    a.secondScreenDisplays.map { it.id to it.label }, a.selectedSecondScreenDisplay,
                                    chipModifier = focus.track(page, "second-screen-display"), onPick = a.onSecondScreenDisplay)
                            }
                        }
                        else -> {
                            if (a.isHomeApp) SettingsGroup("Android apps") {
                                SettingsRow("Launch an app", null) {
                                    DrawerOutlineButton(if (androidAppsExpanded) "Hide" else "Show", modifier = focus.track(page, "apps")) {
                                        androidAppsExpanded = !androidAppsExpanded
                                    }
                                }
                                if (androidAppsExpanded) {
                                    if (a.androidApps.isEmpty()) {
                                        Text("No launchable apps", fontSize = 12.sp, color = colors.onSurfaceVariant,
                                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp))
                                    } else for (app in a.androidApps) {
                                        MenuItem(
                                            app.label, checked = false,
                                            modifier = focus.track(page, "app:${app.packageName}/${app.className}"),
                                            leading = {
                                                app.icon?.let { icon ->
                                                    Image(bitmap = icon.asImageBitmap(), contentDescription = null,
                                                        modifier = Modifier.size(26.dp).clip(RoundedCornerShape(6.dp)))
                                                }
                                            },
                                        ) {
                                            host.open = null
                                            if (a.secondScreenDisplays.isEmpty()) a.onLaunchAndroidApp(app, null)
                                            else appToChooseDisplay = app
                                        }
                                    }
                                }
                            }
                            SettingsGroup("Session behavior") {
                                ChoiceRow(
                                    host, "suspend", "Background behavior",
                                    "Applies now and to future sessions in this mode.",
                                    listOf(
                                        SessionPrefs.SUSPEND_AUTO to "Auto",
                                        SessionPrefs.SUSPEND_MANUAL to "Manual",
                                        SessionPrefs.SUSPEND_NEVER to "Never",
                                    ),
                                    a.suspendPolicy,
                                    note = "Auto pauses in the background and resumes when visible. Manual pauses there and waits for Resume. Never keeps the session running.",
                                    chipModifier = focus.track(page, "suspend"),
                                    onPick = a.onSuspendPolicy,
                                )
                            }
                            SettingsGroup("Next session") {
                                ChoiceRow(host, "shape", "Screen ratio", null,
                                    SessionPrefs.shapeChoices, a.shapeMode,
                                    chipModifier = focus.track(page, "shape"), onPick = a.onShape)
                            }
                            if (a.steam) SettingsGroup(stringResource(R.string.game_settings_title)) {
                                ChoiceRow(host, "fex", stringResource(R.string.fex_preset_title), stringResource(R.string.fex_next_launch),
                                    FexPreset.all.map { it.id to stringResource(it.label) }, a.fexPreset,
                                    chipModifier = focus.track(page, "fex"), onPick = a.onFexPreset)
                                GameEnvironmentRow(modifier = focus.track(page, "game-env"))
                            }
                            SettingsGroup("Support") {
                                SettingsRow("Session logs", "Send this session's logs with a bug report") {
                                    DrawerOutlineButton("Share logs", modifier = focus.track(page, "share-logs")) {
                                        host.open = null
                                        a.onShareLogs()
                                    }
                                }
                            }
                            Spacer(Modifier.height(18.dp))
                            if (!a.isHomeApp) DrawerOutlineButton("Background", modifier = focus.track(page, "background")) {
                                host.open = null; a.onBackground()
                            }
                        }
                    }
                } }
            }
        }
    }

    appToChooseDisplay?.let { app ->
        val secondaryDisplay = a.secondScreenDisplays.firstOrNull { it.id == a.selectedSecondScreenDisplay }
            ?: a.secondScreenDisplays.firstOrNull()
        ChooseAppDisplayDialog(
            app = app,
            secondaryDisplay = secondaryDisplay,
            onPrimary = {
                appToChooseDisplay = null
                a.onLaunchAndroidApp(app, Display.DEFAULT_DISPLAY)
            },
            onSecondary = {
                appToChooseDisplay = null
                secondaryDisplay?.let { a.onLaunchAndroidApp(app, it.id) }
            },
            onDismiss = { appToChooseDisplay = null },
        )
    }
    if (confirmStop) {
        val cancelFocus = remember { FocusRequester() }
        val cancel = { confirmStop = false }
        // Where Stop sits on screen: the dialog is a window of its own, so its bounds are taken
        // against the screen and the session's overlay converts them back.
        var stopOnScreen by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
        val stop = {
            confirmStop = false
            val from = a.onStopFrom
            if (from != null) from(stopOnScreen) else a.onStop()
        }
        LaunchedEffect(controllerActive) {
            if (controllerActive) {
                androidx.compose.runtime.withFrameNanos { }
                runCatching { cancelFocus.requestFocus() }
            }
        }
        AlertDialog(
            onDismissRequest = cancel,
            modifier = Modifier.controllerBack(onBack = cancel),
            title = { Text("Stop session?") },
            confirmButton = {
                val dialogView = LocalView.current
                TextButton(
                    onClick = stop,
                    modifier = Modifier.controllerConfirm(onClick = stop).onGloballyPositioned { c ->
                        val at = IntArray(2).also { dialogView.rootView.getLocationOnScreen(it) }
                        stopOnScreen = c.boundsInWindow().translate(at[0].toFloat(), at[1].toFloat())
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = colors.error),
                ) { Text("Stop") }
            },
            dismissButton = {
                TextButton(onClick = cancel, modifier = Modifier.focusRequester(cancelFocus)
                    .controllerConfirm(onClick = cancel)) { Text("Cancel") }
            },
        )
    }
}

@Composable
private fun DrawerOutlineButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val hot = source.collectIsFocusedAsState().value || source.collectIsHoveredAsState().value
    OutlinedButton(
        onClick = onClick,
        interactionSource = source,
        modifier = modifier.controllerConfirm(onClick = onClick),
        shape = RoundedCornerShape(12.dp),
        contentPadding = PaddingValues(horizontal = 12.dp),
        border = BorderStroke(if (hot) 2.dp else 1.dp, if (hot) pal.signal else colors.outline),
        colors = ButtonDefaults.outlinedButtonColors(containerColor = if (hot) pal.signal.copy(alpha = 0.16f) else Color.Transparent),
    ) { Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1) }
}

@Composable
private fun StopSessionButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val fill by animateColorAsState(if (hot) colors.error.copy(alpha = 0.18f) else Color.Transparent, Motion.tw(220), label = "dangerFill")
    val shape = RoundedCornerShape(20.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.heightIn(min = 44.dp).semantics { contentDescription = "Stop session" }
            .clip(shape).background(fill).border(if (hot) 2.dp else 1.dp, colors.error.copy(alpha = if (hot) 0.9f else 0.55f), shape)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
    ) {
        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
        Text("Stop", fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
    }
}

/** One of the sheet's shortcuts into Steam: an icon over its name, the whole tile the target. */
@Composable
private fun QuickAction(
    label: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier,
    compact: Boolean = false,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onConfirm: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val hot = interactionSource.collectIsFocusedAsState().value || interactionSource.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(12.dp)
    val tile = modifier.height(if (compact) 44.dp else 64.dp).clip(shape)
        .background(if (hot) pal.signal.copy(alpha = 0.14f) else colors.surface)
        .border(if (hot) 2.dp else 1.dp, if (hot) pal.signal else pal.line, shape)
        .hoverable(interactionSource)
        .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
        .controllerConfirm(onClick = onConfirm ?: onClick)
        .padding(horizontal = 6.dp)
    val content: @Composable () -> Unit = {
        Icon(icon, contentDescription = null, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(20.dp))
        Text(label, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
    if (compact) Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterHorizontally), modifier = tile,
    ) { content() }
    else Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(5.dp, Alignment.CenterVertically), modifier = tile,
    ) { content() }
}

/** A bumper as a keycap, 44dp to touch; LB / RB on the pad turn the page too. */
@Composable
private fun DrawerBumper(label: String, description: String, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val ring = RoundedCornerShape(10.dp)
    val cap = RoundedCornerShape(7.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(44.dp).clip(ring)
            .border(2.dp, if (hot) pal.signal else Color.Transparent, ring)
            .hoverable(src).clickable(interactionSource = src, indication = null, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (hot) colors.onBackground else colors.onSurfaceVariant,
            modifier = Modifier.clip(cap).background(colors.surfaceVariant).border(1.dp, pal.line2, cap).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

/**
 * The drawer's page tabs, QAM-style: one icon per page standing in a line in page order. The
 * selected one steps forward - full size, bright, a soft glow - while the others step back to
 * half size and fade, and the whole line leans a little toward the selection. LB / RB still turn
 * the page (the buttons beside it and the pad's bumpers); a tab can be tapped or picked with the
 * pad like the dots it replaces.
 */
@Composable
private fun DrawerPageTabs(page: Int, modifier: Modifier = Modifier, compact: Boolean = false, onSelect: (Int) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val count = drawerPageIcons.size
    val middle = (count - 1) / 2f
    val motion = tween<Float>(durationMillis = 340, easing = FastOutSlowInEasing)
    androidx.compose.foundation.layout.BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(if (compact) 44.dp else 64.dp).clipToBounds(),
    ) {
        val tab = if (compact) 40f else 56f
        // Fit the line to the room between LB and RB. Worst case is an end tab selected: full size
        // ([tab] dp) and pushed outward by the lean, which scales with the spacing. So
        //   (count-1) * spacing * (1 + lean/64) + 56 <= width
        // - never more than the mock's 64 dp, never tighter than 32 dp.
        val spacing = ((maxWidth.value - tab) / ((count - 1) * (1f + DRAWER_TAB_LEAN_DP / DRAWER_TAB_SPACING_DP))).coerceIn(32f, DRAWER_TAB_SPACING_DP)
        val leanStep = DRAWER_TAB_LEAN_DP * spacing / DRAWER_TAB_SPACING_DP
        val lean by animateFloatAsState(-(page - middle) * leanStep, motion, label = "tabLean")
        drawerPageIcons.forEachIndexed { index, icon ->
            val selected = index == page
            val source = remember { MutableInteractionSource() }
            val focused = source.collectIsFocusedAsState().value
            val scale by animateFloatAsState(if (selected) 1f else DRAWER_TAB_SIDE_SCALE, motion, label = "tabScale")
            val alpha by animateFloatAsState(if (selected) 1f else DRAWER_TAB_SIDE_ALPHA, motion, label = "tabAlpha")
            val glow by animateFloatAsState(if (selected) 1f else 0f, motion, label = "tabGlow")
            val tint by animateColorAsState(if (selected) colors.onBackground else colors.onSurfaceVariant, tween(340), label = "tabTint")
            val select = { onSelect(index) }
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .offset(x = ((index - middle) * spacing + lean).dp)
                    .size(tab.dp)
                    .graphicsLayer { scaleX = scale; scaleY = scale; this.alpha = alpha }
                    .clip(RoundedCornerShape(16.dp))
                    .background(
                        Brush.radialGradient(
                            listOf(pal.signal.copy(alpha = 0.34f * glow), pal.signal.copy(alpha = 0.10f * glow), Color.Transparent),
                        ),
                    )
                    .border(if (focused) 2.dp else 0.dp, if (focused) colors.onBackground else Color.Transparent, RoundedCornerShape(16.dp))
                    .semantics { contentDescription = "${drawerPageTitles[index]} page" }
                    .hoverable(source)
                    .clickable(interactionSource = source, indication = LocalIndication.current, onClick = select)
                    .controllerConfirm(onClick = select),
            ) {
                Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (compact) 22.dp else 30.dp))
            }
        }
    }
}

/** Display, Controls, Components, Settings. */
const val DRAWER_PAGES = 4

private const val DRAWER_PAGE_COMPONENTS = 2

/**
 * The drawer's Components tab: quick swaps between what is already installed, per Proton. A swap
 * into the Proton a running game uses waits until that game closes; downloading, importing and
 * deleting stay on the Components page in the app.
 */
@Composable
private fun ComponentsDrawerPage(host: MenuHost, a: DrawerActions, track: (String) -> Modifier) {
    val colors = MaterialTheme.colorScheme
    val snap = a.components
    if (snap == null) {
        SettingsGroup("Components") {
            Text("Reading the Protons…", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(14.dp))
        }
        return
    }
    val running = snap.protons.filter { it.inUseByGame }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val view = snap.protons.firstOrNull { it.proton.id == pick } ?: running.firstOrNull() ?: snap.protons.firstOrNull()
    // Focusable, so the pad can climb from the Proton box up to it and the page scrolls it into view.
    SettingsGroup("Running now") {
        val src = remember { MutableInteractionSource() }
        val hot = src.collectIsFocusedAsState().value
        val pal = LocalPalette.current
        Column(
            modifier = Modifier.fillMaxWidth().then(track("cmp-running"))
                .focusable(interactionSource = src)
                .background(if (hot) pal.signal.copy(alpha = 0.12f) else Color.Transparent)
                .padding(horizontal = 14.dp, vertical = 11.dp),
        ) {
            if (running.isEmpty()) {
                Text("No game is running on a Proton", fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text("Changes apply the next time a game starts.", fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
            } else for (r in running) {
                Text(r.proton.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                for (comp in ComponentsManager.COMPONENTS) {
                    Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
                        Text(ComponentsManager.LABEL.getValue(comp), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.width(96.dp))
                        Text(r.components.getValue(comp).inUse, fontSize = 12.sp, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
    }
    if (view == null) {
        SettingsGroup("Components") {
            Text("No Proton is installed yet.", fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(14.dp))
        }
        return
    }
    val p = view.proton
    SettingsGroup("Components") {
        DrawerStackedChoice(
            host, "cmp-proton", "Proton",
            listOfNotNull(
                p.version,
                "game running".takeIf { view.inUseByGame },
                view.reappliedAt.takeIf { it > 0 }?.let { "re-applied at launch " + java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it * 1000)) },
            ).joinToString(" · "),
            snap.protons.map { it.proton.id to it.proton.name }, p.id,
            chipModifier = track("cmp-proton"), onPick = { pick = it },
        )
        val build = ComponentsManager.safeName(p.version)
        for (comp in ComponentsManager.COMPONENTS) {
            val st = view.components.getValue(comp)
            val originals = view.originals.filter { it.comp == comp }
            val options = originals.map { "orig:${it.protonVersion}" to (if (it.protonVersion == build) "Original" else "Original · ${it.protonVersion}") } +
                snap.packages.filter { it.comp == comp }.map { it.file to it.version }
            if (options.isEmpty()) continue
            val current = st.activeFile ?: "orig:$build"
            val shown = options.firstOrNull { it.first == current }?.second
            DrawerStackedChoice(
                host, "cmp-$comp", ComponentsManager.LABEL.getValue(comp),
                // The box already shows the choice: only say more when there is more to say.
                st.queued?.let { "Next: $it · after the game closes" } ?: st.inUse.takeIf { it != shown },
                options, current,
                note = if (view.inUseByGame) "A game is running on this Proton: the change waits until it closes." else "Applies the next time a game starts.",
                chipModifier = track("cmp-$comp"),
                onPick = { v -> if (v != current || st.queued != null) a.onComponentSwap(p.id, comp, v) },
            )
        }
    }
    Text(
        "Downloads, importing and deleting are on the Components page in the app.",
        fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
    )
}

/**
 * A choice for the drawer's narrow column: label, then the box across the full width, then the
 * detail underneath - so a long Proton or package name never squeezes the text beside it.
 */
@Composable
private fun <T> DrawerStackedChoice(
    host: MenuHost, key: String, label: String, hint: String?,
    options: List<Pair<T, String>>, selected: T, note: String? = null,
    chipModifier: Modifier = Modifier, onPick: (T) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val open = host.open == key
    Column(
        modifier = Modifier.fillMaxWidth()
            .background(if (open) pal.signal.copy(alpha = 0.10f) else Color.Transparent)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(label, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
        Box(modifier = Modifier.fillMaxWidth().padding(top = 6.dp)) {
            ValueChip(options.firstOrNull { it.first == selected }?.second ?: "-", open, modifier = chipModifier.fillMaxWidth()) {
                host.open = if (open) null else key
            }
            AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null }, title = label, note = note) { firstItemFocus ->
                options.forEachIndexed { index, (value, text) ->
                    MenuItem(text, checked = value == selected, focusRequester = if (index == 0) firstItemFocus else null) {
                        onPick(value)
                        host.open = null
                    }
                }
            }
        }
        if (hint != null) Text(hint, fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 5.dp))
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

/** The tab row's measure, from the approved mock: side tabs at half size, a little faded. */
private const val DRAWER_TAB_SPACING_DP = 64f

private const val DRAWER_TAB_LEAN_DP = 12f

private const val DRAWER_TAB_SIDE_SCALE = 0.5f

private const val DRAWER_TAB_SIDE_ALPHA = 0.7f
