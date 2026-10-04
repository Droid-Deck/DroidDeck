package com.droiddeck.launcher.ui

import androidx.compose.ui.draw.alpha
import androidx.compose.animation.core.Animatable
import androidx.compose.foundation.Canvas
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.util.lerp
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.IntSize
import kotlin.math.roundToInt
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.Icons
import androidx.compose.foundation.layout.heightIn
import androidx.compose.material.icons.outlined.Home
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
import androidx.compose.material3.Text
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
import androidx.compose.runtime.mutableIntStateOf
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
import androidx.compose.ui.platform.LocalContext
import com.droiddeck.launcher.R
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenMode
import kotlinx.coroutines.flow.collect

private val drawerPageTitles = listOf(R.string.drawer_page_display, R.string.drawer_page_controls, R.string.drawer_page_components, R.string.drawer_page_settings)

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

    /** Where focus was last on [page], if anywhere. */
    fun target(page: Int) = last[page]
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
    val frameGen: FrameGen.Mode,
    val lossless: Lossless.State,
    val oscMode: String,
    val suspendPolicy: String,
    val backActionsInverted: Boolean,
    val touchMode: String,
    val touchAuto: String,
    val shapeMode: String,
    val fexPreset: String,
    /** Steam only: games stretched to the screen's size, changed live (null = not Steam). */
    val fillScreen: Boolean? = null,
    val upscaler: Int = 0,
    val upscaleSharpness: Int = 75,
    val secondScreenMode: SecondScreenMode,
    val secondScreenDisplays: List<SecondScreenDisplay>,
    val selectedSecondScreenDisplay: Int,
    val onHud: (Boolean) -> Unit,
    val onFrameGenPick: (FrameGen.Mode) -> Unit,
    val onImportLossless: () -> Unit,
    /** The Android keyboard (text, turned into key presses). */
    val onKeyboard: () -> Unit,
    /** The on-screen PC keyboard: real keys, Esc, F1-F12, Ctrl, Alt... */
    val onHardwareKeyboard: () -> Unit,
    val onSteamMenu: (() -> Unit)?,
    val onQam: (() -> Unit)?,
    val onOsc: (String) -> Unit,
    val controller: com.droiddeck.launcher.input.ControllerPrefs.Settings? = null,
    val onRumble: (Boolean) -> Unit = {},
    val onSteamButton: (Boolean) -> Unit = {},
    val onQamButton: (Boolean) -> Unit = {},
    val onKeyboardButton: (Boolean) -> Unit = {},
    val onSuspendPolicy: (String) -> Unit,
    val onBackActionsInverted: (Boolean) -> Unit,
    val onTouch: (String) -> Unit,
    val onShape: (String) -> Unit,
    val onFexPreset: (String) -> Unit,
    val onFillScreen: (Boolean) -> Unit = {},
    val onUpscaler: (Int) -> Unit = {},
    val onUpscaleSharpness: (Int) -> Unit = {},
    val onSecondScreenMode: (SecondScreenMode) -> Unit,
    val onSecondScreenDisplay: (Int) -> Unit,
    val onLaunchAndroidApp: (HomeApp.LaunchableApp, Int?) -> Unit,
    val onBackground: () -> Unit,
    val pipSupported: Boolean = false,
    val pipAutoEnter: Boolean = false,
    val onPip: () -> Unit = {},
    val onPipAutoEnter: (Boolean) -> Unit = {},
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
    var sheetCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stopCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
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
    // The page focus was last put on: turning to another (LB, RB, a tab) starts it at its first
    // option; anything else (a menu closing, the drawer opening again) goes back where it was.
    var focusedPage by remember { mutableIntStateOf(page) }
    LaunchedEffect(open, page, controllerActive, host.open, appToChooseDisplay, confirmStop) {
        if (!open) {
            host.open = null
            confirmStop = false
        } else if (controllerActive) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (host.open != null || appToChooseDisplay != null || confirmStop) return@LaunchedEffect
            val turned = page != focusedPage
            focusedPage = page
            // The page's top control: on Settings that is Android apps, when it is shown.
            val first = if (page == drawerPageEntries.lastIndex && a.isHomeApp) "apps" else drawerPageEntries[page]
            if (turned) focus.forget(page)
            val target = focus.target(page) ?: first
            repeat(24) {
                androidx.compose.runtime.withFrameNanos { }
                focus.request(target)
                if (focus.focused == target) return@LaunchedEffect
            }
            focus.forget(page)
            focus.request(first)
        }
    }
    if (open || veil > 0.01f) Box(
        modifier = Modifier.fillMaxSize().graphicsLayer { alpha = veil }.background(Color(0x8A000000))
            .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { host.open = null; a.onClose() },
    )
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        // A 4:3 or near-square screen (under 440dp tall) gets tighter padding and a shorter tab row,
        // so the settings keep most of the height; a narrow one keeps some of the game in view.
        val short = maxHeight < 440.dp
        val sheetWidth = minOf(360.dp, maxWidth * 0.92f)
        AnimatedVisibility(
            open,
            enter = slideInHorizontally(Motion.sp(0.8f, Spring.StiffnessLow)) { it } + fadeIn(Motion.tw(220)),
            exit = slideOutHorizontally(Motion.tw(240)) { it } + fadeOut(Motion.tw(200)),
        ) {
            FocusGlideHost(Modifier.fillMaxHeight().width(sheetWidth)) { Box(Modifier.fillMaxSize().onGloballyPositioned { sheetCoords = it }) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(pal.background.copy(alpha = 0.97f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .focusGroup()
                    .controllerBack {
                        if (host.open != null) host.open = null else a.onClose()
                    }
                    .padding(if (short) 12.dp else 16.dp),
            ) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth().heightIn(min = 44.dp),
                ) {
                    Text(a.title ?: if (a.steam) stringResource(R.string.drawer_steam) else stringResource(R.string.drawer_desktop), fontSize = if (short) 18.sp else 20.sp, fontWeight = FontWeight.Bold,
                        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
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
                        SteamActionPill(stringResource(R.string.drawer_steam_menu), Icons.Outlined.Home, focus.track(page, "steam")) {
                            host.open = null
                            a.onSteamMenu.invoke()
                        }
                        SteamActionPill(
                            stringResource(R.string.drawer_open_qam), Icons.Outlined.MoreHoriz, focus.track(page, "qam"),
                            interactionSource = qamInteraction,
                            onConfirm = { host.open = null; a.onQam.invoke() },
                        ) {
                            host.open = null
                            if (!qamStartedOnPress) a.onQam.invoke()
                            qamStartedOnPress = false
                        }
                    }
                    StopSessionButton(modifier = focus.track(page, "stop").onGloballyPositioned { stopCoords = it }) { host.open = null; confirmStop = true }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = if (short) 6.dp else 12.dp),
                ) {
                    DrawerBumper("LB", stringResource(R.string.drawer_prev_page), modifier = focus.track(page, "prev")) {
                        host.open = null; onPageChange((page + DRAWER_PAGES - 1) % DRAWER_PAGES)
                    }
                    DrawerPageTabs(page = page, compact = short, modifier = Modifier.weight(1f)) { index -> host.open = null; onPageChange(index) }
                    DrawerBumper("RB", stringResource(R.string.drawer_next_page), modifier = focus.track(page, "next")) {
                        host.open = null; onPageChange((page + 1) % DRAWER_PAGES)
                    }
                }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(pageScroll[page]),
                ) { CompositionLocalProvider(LocalChipMinWidth provides 120.dp) {
                    when (page) {
                        0 -> SettingsGroup(stringResource(R.string.drawer_page_display)) {
                            ToggleRow(host, "hud", stringResource(R.string.drawer_hud), null, a.hudOn,
                                chipModifier = focus.track(page, "hud"), onChange = a.onHud)
                            if (a.fillScreen != null) ToggleRow(
                                host, "fill", stringResource(R.string.drawer_fill), null, a.fillScreen,
                                chipModifier = focus.track(page, "fill"), onChange = a.onFillScreen,
                            )
                            ChoiceRow(host, "upscaler", "Upscaler", null,
                                SessionPrefs.upscalerChoices, a.upscaler,
                                chipModifier = focus.track(page, "upscaler"), onPick = a.onUpscaler)
                            ChoiceRow(host, "upscale-sharpness", "Upscaler sharpness", null,
                                SessionPrefs.upscaleSharpnessChoices, a.upscaleSharpness, enabled = a.upscaler != 0,
                                chipModifier = focus.track(page, "upscale-sharpness"), onPick = a.onUpscaleSharpness)
                            val fgOpen = host.open == "fg"
                            val fgLabel = FrameGen.label(LocalContext.current, a.frameGen)
                            SettingsRow(stringResource(R.string.frame_gen_title), null, highlighted = fgOpen) {
                                Box {
                                    ValueChip(fgLabel, fgOpen, modifier = focus.track(page, "fg")) { host.open = if (fgOpen) null else "fg" }
                                    FrameGenMenu(host, a.frameGen, a.lossless, a.onFrameGenPick, a.onImportLossless)
                                }
                            }
                        }
                        DRAWER_PAGE_COMPONENTS -> ComponentsDrawerPage(host, a) { key -> focus.track(page, key) }
                        1 -> {
                            SettingsGroup(stringResource(R.string.drawer_page_controls)) {
                                ChoiceRow(host, "touch", stringResource(R.string.mode_touch), null,
                                    listOf(SessionPrefs.TOUCH_AUTO to stringResource(R.string.drawer_touch_auto, a.touchAuto), SessionPrefs.TOUCH_PAD to stringResource(R.string.mode_touch_touchpad), SessionPrefs.TOUCH_DIRECT to stringResource(R.string.mode_touch_direct), SessionPrefs.TOUCH_OFF to stringResource(R.string.widgets_off)),
                                    a.touchMode, chipModifier = focus.track(page, "touch"), onPick = a.onTouch)
                                ChoiceRow(host, "osc", stringResource(R.string.mode_osc), null,
                                    if (a.steam) listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always), SessionPrefs.OSC_STEAM_QAM to stringResource(R.string.mode_osc_qam), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never))
                                    else listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never)),
                                    a.oscMode, chipModifier = focus.track(page, "osc"), onPick = a.onOsc)
                                a.controller?.let { c ->
                                    ToggleRow(host, "rumble", stringResource(R.string.ctrl_rumble), null, c.rumble,
                                        chipModifier = focus.track(page, "rumble"), onChange = a.onRumble)
                                    ToggleRow(host, "steam-button", stringResource(R.string.ctrl_steam_button), null, c.steamButton,
                                        chipModifier = focus.track(page, "steam-button"), onChange = a.onSteamButton)
                                    ToggleRow(host, "qam-button", stringResource(R.string.ctrl_qam_button), null, c.qamButton,
                                        chipModifier = focus.track(page, "qam-button"), onChange = a.onQamButton)
                                    ToggleRow(host, "keyboard-button", stringResource(R.string.ctrl_keyboard_button), null, c.keyboardButton,
                                        chipModifier = focus.track(page, "keyboard-button"), onChange = a.onKeyboardButton)
                                }
                                if (a.steam) ChoiceRow(host, "back-actions", stringResource(R.string.mode_back), null,
                                    listOf(false to SessionPrefs.BACK_MENU_THEN_QAM, true to SessionPrefs.BACK_QAM_THEN_MENU),
                                    a.backActionsInverted, chipModifier = focus.track(page, "back-actions"), onPick = a.onBackActionsInverted)
                            }
                            SettingsGroup(stringResource(R.string.drawer_keyboard)) {
                                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.fillMaxWidth().padding(8.dp)) {
                                    DrawerOutlineButton(stringResource(R.string.drawer_pc_keyboard), modifier = Modifier.weight(1f).height(48.dp).then(focus.track(page, "hardware"))) {
                                        host.open = null; a.onHardwareKeyboard()
                                    }
                                    DrawerOutlineButton(stringResource(R.string.drawer_android_keyboard), modifier = Modifier.weight(1f).height(48.dp).then(focus.track(page, "android"))) {
                                        host.open = null; a.onKeyboard()
                                    }
                                }
                            }
                            if (a.steam && a.secondScreenDisplays.isNotEmpty()) SettingsGroup(stringResource(R.string.drawer_second_screen)) {
                                ChoiceRow(host, "second-screen-mode", stringResource(R.string.drawer_second_screen_shows), null,
                                    (listOf(SecondScreenMode.NONE, SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.TERMINAL) +
                                        (if (com.droiddeck.launcher.session.SessionState.deckPad) listOf(SecondScreenMode.DECK_CONTROLS) else emptyList()))
                                        .map { it to it.label },
                                    a.secondScreenMode, chipModifier = focus.track(page, "second-screen-mode"), onPick = a.onSecondScreenMode)
                                if (a.secondScreenDisplays.size > 1) ChoiceRow(host, "second-screen-display", stringResource(R.string.drawer_page_display), null,
                                    a.secondScreenDisplays.map { it.id to it.label }, a.selectedSecondScreenDisplay,
                                    chipModifier = focus.track(page, "second-screen-display"), onPick = a.onSecondScreenDisplay)
                            }
                        }
                        else -> {
                            if (a.isHomeApp) SettingsGroup(stringResource(R.string.drawer_android_apps)) {
                                SettingsRow(stringResource(R.string.drawer_launch_app), null) {
                                    DrawerOutlineButton(if (androidAppsExpanded) stringResource(R.string.common_hide) else stringResource(R.string.common_show), modifier = focus.track(page, "apps")) {
                                        androidAppsExpanded = !androidAppsExpanded
                                    }
                                }
                                if (androidAppsExpanded) {
                                    if (a.androidApps.isEmpty()) {
                                        Text(stringResource(R.string.drawer_no_apps), fontSize = 12.sp, color = colors.onSurfaceVariant,
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
                            SettingsGroup(stringResource(R.string.drawer_session_behavior)) {
                                ChoiceRow(
                                    host, "suspend", stringResource(R.string.mode_suspend),
                                    stringResource(R.string.drawer_suspend_hint),
                                    listOf(
                                        SessionPrefs.SUSPEND_AUTO to stringResource(R.string.common_auto),
                                        SessionPrefs.SUSPEND_MANUAL to stringResource(R.string.mode_suspend_manual),
                                        SessionPrefs.SUSPEND_NEVER to stringResource(R.string.common_never),
                                    ),
                                    a.suspendPolicy,
                                    note = stringResource(R.string.mode_suspend_note),
                                    chipModifier = focus.track(page, "suspend"),
                                    onPick = a.onSuspendPolicy,
                                )
                            }
                            SettingsGroup(stringResource(R.string.drawer_next_session)) {
                                ChoiceRow(host, "shape", stringResource(R.string.mode_ratio), null,
                                    SessionPrefs.shapeChoices, a.shapeMode,
                                    chipModifier = focus.track(page, "shape"), onPick = a.onShape)
                            }
                            if (a.steam) SettingsGroup(stringResource(R.string.game_settings_title)) {
                                ChoiceRow(host, "fex", stringResource(R.string.fex_preset_title), stringResource(R.string.fex_next_launch),
                                    FexPreset.all.map { it.id to stringResource(it.label) }, a.fexPreset,
                                    chipModifier = focus.track(page, "fex"), onPick = a.onFexPreset)
                                GameEnvironmentRow(modifier = focus.track(page, "game-env"))
                            }
                            SettingsGroup(stringResource(R.string.drawer_support)) {
                                SettingsRow(stringResource(R.string.drawer_logs), stringResource(R.string.drawer_logs_hint)) {
                                    DrawerOutlineButton(stringResource(R.string.drawer_share_logs), modifier = focus.track(page, "share-logs")) {
                                        host.open = null
                                        a.onShareLogs()
                                    }
                                }
                            }
                            if (a.pipSupported) SettingsGroup(stringResource(R.string.pip_title)) {
                                ToggleRow(host, "pip-auto", stringResource(R.string.pip_auto), null,
                                    a.pipAutoEnter, onChange = a.onPipAutoEnter)
                            }
                            Spacer(Modifier.height(18.dp))
                            if (a.pipSupported) DrawerOutlineButton(stringResource(R.string.pip_title), modifier = focus.track(page, "pip")) {
                                host.open = null; a.onPip()
                            }
                            if (!a.isHomeApp) DrawerOutlineButton(stringResource(R.string.drawer_background), modifier = focus.track(page, "background")) {
                                host.open = null; a.onBackground()
                            }
                        }
                    }
                } }
            }
            StopConfirm(
                open = confirmStop,
                pill = sheetCoords?.let { sc -> stopCoords?.takeIf { it.isAttached && sc.isAttached }?.let { sc.localBoundingBoxOf(it, clipBounds = false) } },
                controllerActive = controllerActive,
                onCancel = { confirmStop = false },
                onStop = { onScreen ->
                    confirmStop = false
                    val from = a.onStopFrom
                    if (from != null) from(onScreen) else a.onStop()
                },
            )
            } }
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
    val stopDescription = stringResource(R.string.stop_session)
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = modifier.heightIn(min = 44.dp).semantics { contentDescription = stopDescription }
            .clip(shape).background(fill).glideBorder(hot, shape, colors.error.copy(alpha = 0.9f), colors.error.copy(alpha = 0.55f))
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
    ) {
        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.drawer_stop), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
    }
}

/** A compact shortcut into Steam, sharing the header with Stop. */
@Composable
private fun SteamActionPill(
    description: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    onConfirm: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val hot = interactionSource.collectIsFocusedAsState().value || interactionSource.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(22.dp)
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(width = 52.dp, height = 44.dp).semantics { contentDescription = description }
            .clip(shape).background(if (hot) pal.signal.copy(alpha = 0.14f) else Color.Transparent)
            .glideBorder(hot, shape, pal.signal, pal.line)
            .hoverable(interactionSource)
            .clickable(interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onConfirm ?: onClick),
    ) {
        Icon(icon, contentDescription = null, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(20.dp))
    }
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
            .glideBorder(hot, ring, pal.signal)
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
    val motion = Motion.tw<Float>(340, easing = FastOutSlowInEasing)
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
            val pageDescription = stringResource(R.string.drawer_page_desc, stringResource(drawerPageTitles[index]))
            val tint by animateColorAsState(if (selected) colors.onBackground else colors.onSurfaceVariant, Motion.tw(340, easing = FastOutSlowInEasing), label = "tabTint")
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
                    .glideBorder(focused, RoundedCornerShape(16.dp), colors.onBackground)
                    .semantics { contentDescription = pageDescription }
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
        SettingsGroup(stringResource(R.string.drawer_page_components)) {
            Text(stringResource(R.string.comp_reading), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(14.dp))
        }
        return
    }
    val running = snap.protons.filter { it.inUseByGame }
    var pick by rememberSaveable { mutableStateOf<String?>(null) }
    val view = snap.protons.firstOrNull { it.proton.id == pick } ?: running.firstOrNull() ?: snap.protons.firstOrNull()
    // Focusable, so the pad can climb from the Proton box up to it and the page scrolls it into view.
    SettingsGroup(stringResource(R.string.drawer_running_now)) {
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
                Text(stringResource(R.string.drawer_no_game), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Text(stringResource(R.string.comp_applies_long), fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(top = 2.dp))
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
        SettingsGroup(stringResource(R.string.drawer_page_components)) {
            Text(stringResource(R.string.drawer_no_proton), fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(14.dp))
        }
        return
    }
    val p = view.proton
    SettingsGroup(stringResource(R.string.drawer_page_components)) {
        DrawerStackedChoice(
            host, "cmp-proton", stringResource(R.string.comp_proton),
            listOfNotNull(
                p.version,
                stringResource(R.string.drawer_game_running).takeIf { view.inUseByGame },
                view.reappliedAt.takeIf { it > 0 }?.let { stringResource(R.string.drawer_reapplied, java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT).format(java.util.Date(it * 1000))) },
            ).joinToString(" · "),
            snap.protons.map { it.proton.id to it.proton.name }, p.id,
            chipModifier = track("cmp-proton"), onPick = { pick = it },
        )
        val originalLabel = stringResource(R.string.drawer_original)
        val build = ComponentsManager.safeName(p.version)
        for (comp in ComponentsManager.COMPONENTS) {
            val st = view.components.getValue(comp)
            val originals = view.originals.filter { it.comp == comp }
            val options = originals.map { "orig:${it.protonVersion}" to (if (it.protonVersion == build) originalLabel else stringResource(R.string.comp_original, it.protonVersion)) } +
                snap.packages.filter { it.comp == comp }.map { it.file to it.version }
            if (options.isEmpty()) continue
            val current = st.activeFile ?: "orig:$build"
            val shown = options.firstOrNull { it.first == current }?.second
            DrawerStackedChoice(
                host, "cmp-$comp", ComponentsManager.LABEL.getValue(comp),
                // The box already shows the choice: only say more when there is more to say.
                st.queued?.let { stringResource(R.string.comp_next_queued, it) } ?: st.inUse.takeIf { it != shown },
                options, current,
                note = if (view.inUseByGame) stringResource(R.string.comp_waits_long) else stringResource(R.string.comp_applies_long),
                chipModifier = track("cmp-$comp"),
                onPick = { v -> if (v != current || st.queued != null) a.onComponentSwap(p.id, comp, v) },
            )
        }
    }
    Text(
        stringResource(R.string.drawer_components_note),
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

/** Room between the Stop pill and the box that steps out under it: the pull. */
private val StepPull = 6.dp
private val StepBoxCorner = 16.dp
private val StepFillet = 10.dp
private val StepBoxMaxWidth = 264.dp

/**
 * The stair-step outline: the pill [p] on top, a box under it from [left] to the pill's right edge
 * and down to [bottom], the pull between them [pullTop] (the box's top). Right edges are flush and
 * the inside corner where the pull meets the box is rounded the other way. While the box is still
 * no wider than the pill it is just the pill, stretched down.
 */
private fun stepPath(path: Path, p: Rect, left: Float, bottom: Float, pullTop: Float, box: Float, fillet: Float) {
    path.reset()
    val r = p.right; val tl = p.left; val tt = p.top
    val pb = maxOf(bottom, p.bottom)
    val l = minOf(left, tl)
    val pt = minOf(pullTop, pb)
    val sw = tl - l
    val h = (pb - pt).coerceAtLeast(0f)
    val rt = minOf(p.height / 2f, p.width / 2f)
    val rbr = minOf(box, (pb - tt) / 2f)
    val rbl = minOf(lerp(minOf(box, (pb - tt) / 2f), minOf(box, h / 2f), (sw / box).coerceIn(0f, 1f)), (r - l) / 2f)
    val rpl = minOf(box, sw / 2f, h / 2f)
    val f = minOf(fillet, sw / 2f)
    val yl = minOf(pt + rpl, pb - rbl)
    fun corner(rect: Rect, start: Float, sweep: Float, endX: Float, endY: Float) {
        if (rect.width < 0.5f) path.lineTo(endX, endY) else path.arcTo(rect, start, sweep, false)
    }
    path.moveTo(tl + rt, tt)
    path.lineTo(r - rt, tt)
    corner(Rect(r - 2 * rt, tt, r, tt + 2 * rt), -90f, 90f, r, tt + rt)
    path.lineTo(r, pb - rbr)
    corner(Rect(r - 2 * rbr, pb - 2 * rbr, r, pb), 0f, 90f, r - rbr, pb)
    path.lineTo(l + rbl, pb)
    corner(Rect(l, pb - 2 * rbl, l + 2 * rbl, pb), 90f, 90f, l, pb - rbl)
    path.lineTo(l, yl)
    corner(Rect(l, pt, l + 2 * rpl, pt + 2 * rpl), 180f, 90f, l + rpl, pt)
    path.lineTo(tl - f, pt)
    corner(Rect(tl - 2 * f, pt - 2 * f, tl, pt), 90f, -90f, tl, pt - f)
    path.lineTo(tl, tt + rt)
    corner(Rect(tl, tt, tl + 2 * rt, tt + 2 * rt), 180f, 90f, tl + rt, tt)
    path.close()
}

/**
 * Stop's confirm, grown out of the Stop pill at [pill] (px, in the sheet): a pull the pill's width
 * drops out of it and a wider box steps out to the left under it for the choices, right edges
 * flush, so Stop lands under the pill. Cancel, B and a tap outside fold it back into the pill,
 * width first. [onStop] gets Stop's bounds on screen for the flood.
 */
@Composable
private fun StopConfirm(
    open: Boolean,
    pill: Rect?,
    controllerActive: Boolean,
    onCancel: () -> Unit,
    onStop: (Rect?) -> Unit,
) {
    val colors = MaterialTheme.colorScheme
    val density = LocalDensity.current
    val left = remember { Animatable(0f) }
    val bottom = remember { Animatable(0f) }
    val tint = remember { Animatable(0f) }
    val dim = remember { Animatable(0f) }
    val items = remember { List(3) { Animatable(0f) } }
    // Composed from the first open until it has folded back into the pill.
    var shown by remember { mutableStateOf(false) }
    var grown by remember { mutableStateOf(false) }
    var content by remember { mutableStateOf<IntSize?>(null) }
    val cancelFocus = remember { FocusRequester() }
    var stopFocused by remember { mutableStateOf(false) }
    var cancelFocused by remember { mutableStateOf(false) }
    var stopCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val view = LocalView.current
    if (!open && !shown) return
    val pull = with(density) { StepPull.toPx() }

    LaunchedEffect(open, pill, content) {
        val p = pill ?: return@LaunchedEffect
        val c = content ?: return@LaunchedEffect
        val boxLeft = (p.right - c.width).coerceAtLeast(0f)
        val boxBottom = p.bottom + pull + c.height
        if (open) {
            shown = true
            if (!grown) {
                left.snapTo(p.left); bottom.snapTo(p.bottom); tint.snapTo(0f)
                items.forEach { it.snapTo(0f) }
                grown = true
            }
            coroutineScope {
                launch { dim.animateTo(1f, Motion.tw(260)) }
                launch { tint.animateTo(1f, Motion.tw(200)) }
                launch { bottom.animateTo(boxBottom, Motion.sp(0.7f, 380f)) }
                launch { delay(Motion.ms(110).toLong()); left.animateTo(boxLeft, Motion.sp(0.55f, 300f)) }
                items.forEachIndexed { i, a ->
                    launch { delay(Motion.ms(260 + i * 55).toLong()); a.animateTo(1f, Motion.tw(220)) }
                }
                if (controllerActive) launch {
                    delay(Motion.ms(300).toLong())
                    withFrameNanos { }
                    runCatching { cancelFocus.requestFocus() }
                }
            }
        } else if (grown) {
            coroutineScope {
                items.forEach { launch { it.animateTo(0f, Motion.tw(90)) } }
                launch { delay(Motion.ms(60).toLong()); dim.animateTo(0f, Motion.tw(300)) }
                launch { delay(Motion.ms(60).toLong()); left.animateTo(p.left, Motion.sp(1f, 420f)) }
                launch { delay(Motion.ms(160).toLong()); bottom.animateTo(p.bottom, Motion.sp(1f, 380f)) }
                launch { delay(Motion.ms(200).toLong()); tint.animateTo(0f, Motion.tw(200)) }
            }
            grown = false
            shown = false
            content = null
        }
    }
    val p = pill ?: return

    Box(Modifier.fillMaxSize()) {
        // The rest of the sheet dims behind it, and a tap there cancels.
        Box(
            Modifier.fillMaxSize().graphicsLayer { alpha = dim.value }.background(Color(0x59000000))
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, enabled = open, onClick = onCancel),
        )
        val path = remember { Path() }
        val boxCorner = with(density) { StepBoxCorner.toPx() }
        val fillet = with(density) { StepFillet.toPx() }
        val rim = with(density) { 1.4.dp.toPx() }
        val restFill = colors.surface
        val hotFill = lerp(colors.surface, colors.error, 0.22f)
        Canvas(Modifier.fillMaxSize()) {
            if (!grown) return@Canvas
            stepPath(path, p, left.value, bottom.value, p.bottom + pull, boxCorner, fillet)
            drawPath(path, lerp(hotFill, restFill, tint.value))
            drawPath(path, colors.error.copy(alpha = lerp(0.9f, 0.55f, tint.value)), style = Stroke(rim))
        }
        // The pill's label rides on top, as the pull's handle.
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.offset { IntOffset(p.left.roundToInt(), p.top.roundToInt()) }
                .size(with(density) { p.width.toDp() }, with(density) { p.height.toDp() })
                .padding(start = 12.dp, end = 14.dp),
        ) {
            Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
            Text(stringResource(R.string.drawer_stop), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
        }
        val maxW = with(density) { StepBoxMaxWidth.toPx() }.coerceAtMost(p.right)
        Column(
            modifier = Modifier
                .offset { IntOffset((p.right - (content?.width ?: maxW.roundToInt())).roundToInt(), (p.bottom + pull).roundToInt()) }
                .width(with(density) { maxW.toDp() })
                .onSizeChanged { if (content != it) content = it }
                .controllerBack(onCancel)
                .onPreviewKeyEvent { e ->
                    // Keep a controller in here, as the dialog's own window used to.
                    if (e.type != KeyEventType.KeyDown) return@onPreviewKeyEvent false
                    when (e.key) {
                        Key.DirectionUp, Key.DirectionDown -> true
                        Key.DirectionLeft -> cancelFocused
                        Key.DirectionRight -> stopFocused
                        else -> false
                    }
                }
                .padding(start = 18.dp, end = 12.dp, top = 14.dp, bottom = 12.dp),
        ) {
            Text(
                stringResource(R.string.drawer_stop_title), fontSize = 18.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                modifier = Modifier.graphicsLayer { alpha = items[0].value; translationY = (1f - items[0].value) * 6.dp.toPx() },
            )
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End),
                modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            ) {
                ConfirmChoice(
                    stringResource(R.string.common_cancel), danger = false, enabled = open,
                    modifier = Modifier.graphicsLayer { alpha = items[1].value; translationY = (1f - items[1].value) * 6.dp.toPx() }
                        .focusRequester(cancelFocus).onFocusChanged { cancelFocused = it.isFocused },
                    onClick = onCancel,
                )
                ConfirmChoice(
                    stringResource(R.string.drawer_stop), danger = true, enabled = open,
                    modifier = Modifier.graphicsLayer { alpha = items[2].value; translationY = (1f - items[2].value) * 6.dp.toPx() }
                        .onFocusChanged { stopFocused = it.isFocused }
                        .onGloballyPositioned { stopCoords = it },
                ) {
                    val at = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
                    onStop(stopCoords?.takeIf { it.isAttached }?.boundsInWindow()?.translate(at[0].toFloat(), at[1].toFloat()))
                }
            }
        }
    }
}

/** One of the confirm's two choices: a capsule that fills when focused; Stop in the danger red. */
@Composable
private fun ConfirmChoice(label: String, danger: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(20.dp)
    val accent = if (danger) colors.error else pal.signal
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.heightIn(min = 40.dp).clip(shape)
            .background(if (hot) accent.copy(alpha = if (danger) 0.18f else 0.14f) else Color.Transparent)
            .glideBorder(hot, shape, accent)
            .hoverable(src)
            .clickable(interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (danger) colors.error else colors.onBackground)
    }
}
