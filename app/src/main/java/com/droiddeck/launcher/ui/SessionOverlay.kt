package com.droiddeck.launcher.ui

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
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
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
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.CircleShape
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
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import com.droiddeck.launcher.core.FexPreset
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenMode
import kotlinx.coroutines.flow.collect

private val drawerPageTitles = listOf("Now", "Controls", "Session")
private val drawerPageEntries = listOf("hud", "touch", "suspend")

private class DrawerFocus {
    private val requesters = HashMap<String, FocusRequester>()
    private val last = arrayOfNulls<String>(3)
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

@Composable
fun HudText(text: String) {
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.TopEnd) {
        Text(
            text,
            fontFamily = FontFamily.Monospace,
            fontSize = 12.sp,
            color = Color.White,
            modifier = Modifier
                .padding(12.dp)
                .background(Color(0x8C000000))
                .padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

@Composable
fun LoadingOverlay(step: String, percent: Int, elapsed: String, hint: String, ended: Boolean) {
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = androidx.compose.foundation.layout.Arrangement.Center,
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .clickable(interactionSource = MutableInteractionSource(), indication = null) {}
            .padding(32.dp),
    ) {
        Image(painterResource(R.drawable.logo), contentDescription = null, modifier = Modifier.size(64.dp))
        Spacer(Modifier.height(14.dp))
        Text(if (ended) "The session ended" else "Steam is loading", color = Color.White, fontSize = 17.sp)
        Text(step, color = Color(0xFFB8C4D0), fontSize = 13.sp, textAlign = TextAlign.Center, modifier = Modifier.padding(top = 6.dp))
        if (!ended) {
            Spacer(Modifier.height(14.dp))
            if (percent >= 0) LinearProgressIndicator(progress = { percent / 100f }, modifier = Modifier.width(220.dp))
            else LinearProgressIndicator(modifier = Modifier.width(220.dp))
            Text(elapsed, color = Color(0xFF667788), fontSize = 11.sp, modifier = Modifier.padding(top = 8.dp))
            Text(
                hint, color = Color(0xFF667788), fontSize = 11.sp, textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 18.dp).widthIn(max = 360.dp),
            )
        }
    }
}

@Composable
fun SessionPausedOverlay(onResume: () -> Unit) {
    val interactionSource = remember { MutableInteractionSource() }
    val resumeFocus = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        androidx.compose.runtime.withFrameNanos { }
        runCatching { resumeFocus.requestFocus() }
    }
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xF20B0D10))
            .clickable(interactionSource = interactionSource, indication = null) {},
        contentAlignment = Alignment.Center,
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(32.dp),
        ) {
            OutlinedButton(
                onClick = onResume,
                modifier = Modifier.focusRequester(resumeFocus).controllerConfirm(onClick = onResume),
            ) {
                Text("Resume session")
            }
        }
    }
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
    val onClose: () -> Unit,
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
    val pageScroll = remember { List(3) { ScrollState(0) } }
    val veil by animateFloatAsState(if (open) 1f else 0f, Motion.tw(260), label = "veil")
    val focus = remember { DrawerFocus() }
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = open) {
        if (host.open != null) host.open = null else a.onClose()
    }
    BackHandler(enabled = open && confirmStop) { confirmStop = false }
    LaunchedEffect(page) { host.open = null; appToChooseDisplay = null }
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
    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.CenterEnd) {
        AnimatedVisibility(
            open,
            enter = slideInHorizontally(Motion.sp(0.8f, Spring.StiffnessLow)) { it } + fadeIn(Motion.tw(220)),
            exit = slideOutHorizontally(Motion.tw(240)) { it } + fadeOut(Motion.tw(200)),
        ) {
            Column(
                modifier = Modifier
                    .fillMaxHeight()
                    .width(340.dp)
                    .background(pal.background.copy(alpha = 0.97f))
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) {}
                    .focusGroup()
                    .controllerBack {
                        if (host.open != null) host.open = null else a.onClose()
                    }
                    .padding(horizontal = 14.dp, vertical = 16.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(start = 4.dp, bottom = 6.dp)) {
                    Box(modifier = Modifier.size(10.dp).clip(CircleShape).background(Brush.linearGradient(listOf(colors.primary, pal.primary2))))
                    Spacer(Modifier.width(10.dp))
                    Text(a.title ?: if (a.steam) "Steam" else "Desktop", fontSize = 17.sp, fontWeight = FontWeight.Bold,
                        color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
                    ShareSessionLogsButton(modifier = focus.track(page, "share-logs")) {
                        host.open = null
                        a.onShareLogs()
                    }
                    Spacer(Modifier.width(8.dp))
                    StopSessionButton(modifier = focus.track(page, "stop")) { host.open = null; confirmStop = true }
                }
                if (a.onSteamMenu != null && a.onQam != null) {
                    val qamInteraction = remember { MutableInteractionSource() }
                    val qamHot = qamInteraction.collectIsFocusedAsState().value || qamInteraction.collectIsHoveredAsState().value
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
                        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
                    ) {
                        val steamSrc = remember { MutableInteractionSource() }
                        val steamHot = steamSrc.collectIsFocusedAsState().value || steamSrc.collectIsHoveredAsState().value
                        OutlinedButton(
                            onClick = { host.open = null; a.onSteamMenu.invoke() },
                            interactionSource = steamSrc,
                            modifier = Modifier.weight(1f).height(48.dp).then(focus.track(page, "steam")).controllerConfirm {
                                host.open = null
                                a.onSteamMenu.invoke()
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(if (steamHot) 2.dp else 1.dp, if (steamHot) pal.signal else colors.outline),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (steamHot) pal.signal.copy(alpha = 0.16f) else Color.Transparent),
                        ) {
                            Text("STEAM", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                        }
                        OutlinedButton(
                            onClick = {
                                host.open = null
                                if (!qamStartedOnPress) a.onQam.invoke()
                                qamStartedOnPress = false
                            },
                            interactionSource = qamInteraction,
                            modifier = Modifier.weight(1f).height(48.dp).then(focus.track(page, "qam"))
                                .semantics { contentDescription = "Open Quick Access Menu" }.controllerConfirm {
                                host.open = null
                                a.onQam.invoke()
                            },
                            shape = RoundedCornerShape(12.dp),
                            border = BorderStroke(if (qamHot) 2.dp else 1.dp, if (qamHot) pal.signal else colors.outline),
                            colors = ButtonDefaults.outlinedButtonColors(containerColor = if (qamHot) pal.signal.copy(alpha = 0.16f) else Color.Transparent),
                        ) {
                            Text("QAM", fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.5.sp)
                        }
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
                ) {
                    DrawerOutlineButton("LB  ‹", modifier = Modifier.height(48.dp).then(focus.track(page, "prev"))) {
                        host.open = null; onPageChange((page + 2) % 3)
                    }
                    Row(
                        horizontalArrangement = Arrangement.Center,
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.weight(1f),
                    ) {
                        drawerPageTitles.forEachIndexed { index, title ->
                            val dotSource = remember { MutableInteractionSource() }
                            val dotFocused = dotSource.collectIsFocusedAsState().value
                            val select = { host.open = null; onPageChange(index) }
                            Box(
                                contentAlignment = Alignment.Center,
                                modifier = Modifier.size(38.dp)
                                    .semantics { contentDescription = "$title page" }
                                    .hoverable(dotSource)
                                    .clickable(interactionSource = dotSource, indication = LocalIndication.current, onClick = select)
                                    .controllerConfirm(onClick = select),
                            ) {
                                Box(
                                    Modifier.size(if (index == page) 16.dp else 10.dp)
                                        .clip(CircleShape)
                                        .background(if (index == page) pal.signal else colors.onSurfaceVariant)
                                        .border(if (dotFocused) 2.dp else 0.dp, colors.onBackground, CircleShape),
                                )
                            }
                        }
                    }
                    DrawerOutlineButton("›  RB", modifier = Modifier.height(48.dp).then(focus.track(page, "next"))) {
                        host.open = null; onPageChange((page + 1) % 3)
                    }
                }

                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(pageScroll[page]),
                ) {
                    when (page) {
                        0 -> SettingsGroup("Now") {
                            ToggleRow(host, "hud", "Performance HUD", null, a.hudOn,
                                chipModifier = focus.track(page, "hud"), onChange = a.onHud)
                            if (a.fillScreen != null) ToggleRow(
                                host, "fill", "Stretch games to fill", null, a.fillScreen,
                                chipModifier = focus.track(page, "fill"), onChange = a.onFillScreen,
                            )
                            val fgOpen = host.open == "fg"
                            val fgLabel = when (a.frameGenEngine) {
                                FrameGen.ENGINE_WINFG -> "Win-FG ${a.frameGenMultiplier}×"
                                FrameGen.ENGINE_LSFG -> "LSFG ${a.frameGenMultiplier}×"
                                else -> "Off"
                            }
                            SettingsRow("Frame generation", null, highlighted = fgOpen) {
                                Box {
                                    ValueChip(fgLabel, fgOpen, modifier = focus.track(page, "fg")) { host.open = if (fgOpen) null else "fg" }
                                    AnchoredMenu(fgOpen, onDismiss = { if (host.open == "fg") host.open = null }, title = "Frame generation") { firstItemFocus ->
                                        val need = if (a.lsfgReady) null else "Requires Lossless Scaling"
                                        MenuItem("Off", checked = a.frameGenEngine == FrameGen.ENGINE_OFF, focusRequester = firstItemFocus) { a.onFrameGenPick(FrameGen.ENGINE_OFF, 2); host.open = null }
                                        for (m in 2..4) MenuItem("Win-FG ${m}×", checked = a.frameGenEngine == FrameGen.ENGINE_WINFG && a.frameGenMultiplier == m) { a.onFrameGenPick(FrameGen.ENGINE_WINFG, m); host.open = null }
                                        for (m in 2..4) MenuItem("LSFG ${m}×", checked = a.frameGenEngine == FrameGen.ENGINE_LSFG && a.frameGenMultiplier == m, enabled = a.lsfgReady, detail = need) { a.onFrameGenPick(FrameGen.ENGINE_LSFG, m); host.open = null }
                                    }
                                }
                            }
                        }
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
                                    DrawerOutlineButton("Hardware", modifier = Modifier.weight(1f).height(42.dp).then(focus.track(page, "hardware"))) {
                                        host.open = null; a.onHardwareKeyboard()
                                    }
                                    DrawerOutlineButton("Android", modifier = Modifier.weight(1f).height(42.dp).then(focus.track(page, "android"))) {
                                        host.open = null; a.onKeyboard()
                                    }
                                }
                            }
                            if (a.steam && a.secondScreenDisplays.isNotEmpty()) SettingsGroup("Second screen") {
                                ChoiceRow(host, "second-screen-mode", "Controls", null,
                                    listOf(SecondScreenMode.NONE, SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.TERMINAL).map { it to it.label },
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
                                if (a.steam) ChoiceRow(host, "fex", "FEX preset", null,
                                    FexPreset.all.map { it.id to it.label }, a.fexPreset,
                                    chipModifier = focus.track(page, "fex"), onPick = a.onFexPreset)
                            }
                            Spacer(Modifier.height(18.dp))
                            if (!a.isHomeApp) DrawerOutlineButton("Background", modifier = focus.track(page, "background")) {
                                host.open = null; a.onBackground()
                            }
                        }
                    }
                }
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
        val stop = { confirmStop = false; a.onStop() }
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
                TextButton(
                    onClick = stop,
                    modifier = Modifier.controllerConfirm(onClick = stop),
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
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.size(42.dp).semantics { contentDescription = "Stop session" }
            .clip(RoundedCornerShape(12.dp)).background(fill).border(1.dp, colors.error.copy(alpha = if (hot) 0.9f else 0.5f), RoundedCornerShape(12.dp))
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) { Text("×", fontSize = 25.sp, fontWeight = FontWeight.Medium, color = colors.error) }
}

@Composable
private fun ShareSessionLogsButton(modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = src.collectIsFocusedAsState().value || src.collectIsHoveredAsState().value
    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier.widthIn(min = 88.dp).height(42.dp)
            .semantics { contentDescription = "Send current session logs" }
            .clip(RoundedCornerShape(12.dp))
            .background(if (hot) pal.signal.copy(alpha = 0.16f) else Color.Transparent)
            .border(1.dp, if (hot) pal.signal else colors.outline.copy(alpha = 0.5f), RoundedCornerShape(12.dp))
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick),
    ) {
        Text("send logs", fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            color = if (hot) pal.signal else colors.onSurfaceVariant, maxLines = 1)
    }
}
