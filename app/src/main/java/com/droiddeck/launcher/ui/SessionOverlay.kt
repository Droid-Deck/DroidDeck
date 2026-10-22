package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.testTag
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
import androidx.compose.ui.res.pluralStringResource
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
import androidx.compose.material.icons.outlined.AutoFixHigh
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.PowerSettingsNew
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.material.icons.outlined.DesktopWindows
import androidx.compose.material.icons.outlined.Layers
import androidx.compose.material.icons.outlined.ScreenShare
import androidx.compose.material.icons.outlined.PictureInPictureAlt
import androidx.compose.material.icons.automirrored.outlined.ExitToApp
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.SportsEsports
import androidx.compose.material3.Icon
import androidx.compose.material3.RadioButton
import androidx.compose.material3.RadioButtonDefaults
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.ui.semantics.Role
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
import androidx.compose.material3.LinearProgressIndicator
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
import com.droiddeck.launcher.core.GameEnvironment
import com.droiddeck.launcher.session.ProfileValue
import com.droiddeck.launcher.session.SelectedGameProfile
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.ui.focus.focusProperties
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.mutableStateMapOf
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
import com.droiddeck.launcher.core.TextureFiltering
import com.droiddeck.launcher.gpu.FrameGen
import com.droiddeck.launcher.gpu.Lossless
import com.droiddeck.launcher.gpu.ScreenEffectLooks
import com.droiddeck.launcher.gpu.Cardboard
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.gpu.ScreenEffects
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.ComponentsManager
import com.droiddeck.launcher.input.SecondScreenDisplay
import com.droiddeck.launcher.input.SecondScreenMode
import kotlinx.coroutines.flow.collect
import java.util.Locale

private val SessionDrawerPage.title: Int get() = when (this) {
    SessionDrawerPage.CONTROLLER -> R.string.drawer_page_controller
    SessionDrawerPage.GLASSES -> R.string.drawer_page_glasses
    SessionDrawerPage.SECOND_SCREEN -> R.string.drawer_second_screen
    SessionDrawerPage.DISPLAY -> R.string.drawer_page_display
    SessionDrawerPage.VR -> R.string.vr_title
    SessionDrawerPage.EFFECTS -> R.string.drawer_page_effects
    SessionDrawerPage.GAMES -> R.string.game_settings_title
    SessionDrawerPage.SESSION -> R.string.mode_session
}

private val SessionDrawerPage.icon get() = when (this) {
    SessionDrawerPage.CONTROLLER -> Icons.Outlined.SportsEsports
    SessionDrawerPage.GLASSES -> GlassesIcon
    SessionDrawerPage.SECOND_SCREEN -> Icons.Outlined.ScreenShare
    SessionDrawerPage.DISPLAY -> Icons.Outlined.DesktopWindows
    SessionDrawerPage.VR -> VrHeadsetIcon
    SessionDrawerPage.EFFECTS -> Icons.Outlined.AutoFixHigh
    SessionDrawerPage.GAMES -> Icons.Outlined.Layers
    SessionDrawerPage.SESSION -> Icons.Outlined.Settings
}

private class DrawerFocus {
    private val requesters = HashMap<String, FocusRequester>()
    private val last = mutableMapOf<SessionDrawerPage, String>()
    var focused by mutableStateOf<String?>(null)
        private set

    private fun requester(key: String) = requesters.getOrPut(key) { FocusRequester() }

    fun track(page: SessionDrawerPage, key: String): Modifier = Modifier.focusRequester(requester(key)).onFocusChanged {
        if (it.isFocused) {
            last[page] = key
            focused = key
        } else if (focused == key) focused = null
    }

    fun target(page: SessionDrawerPage) = last[page]
    fun request(key: String) = runCatching { requester(key).requestFocus() }
    fun forget(page: SessionDrawerPage) { last.remove(page) }
}

class DrawerActions(
    val steam: Boolean,
    val title: String? = null,
    val isHomeApp: Boolean,
    val androidApps: List<HomeApp.LaunchableApp>,
    val hudOn: Boolean,
    val hudFps: Boolean = true,
    val hudBattery: Boolean = true,
    val hudGraph: Boolean = true,
    val hudColor: String = SessionPrefs.HUD_COLOR_MINT,
    val hudOpacity: Int = SessionPrefs.HUD_OPACITY_DEFAULT,
    val frameGen: FrameGen.Mode,
    val lossless: Lossless.State,
    val oscMode: String,
    val onScreenButtonsVisible: Boolean,
    val suspendPolicy: String,
    val steamDownloadsInBackground: Boolean = false,
    val backActionsInverted: Boolean,
    val padType: String? = null,
    val onPadType: (String) -> Unit = {},
    val touchMode: String,
    val touchAuto: String,
    val fexPreset: String,
    val upscaler: Int = 0,
    val upscaleSharpness: Int = 75,
    val effects: ScreenEffects = ScreenEffects.OFF,
    val cardboard: Cardboard = Cardboard.OFF,
    val cardboardSensor: Boolean = false,
    val steamVr: SteamVr.Status? = null,
    val glassesPresent: Boolean = false,
    val glassesOutput: Boolean = false,
    val immersiveSteamVr: Boolean = false,
    val textureAnisotropy: Int? = 0,
    val textureLodBias: String? = TextureFiltering.LOD_BIAS_OFF,
    val secondScreenMode: SecondScreenMode,
    val secondScreenDisplays: List<SecondScreenDisplay>,
    val selectedSecondScreenDisplay: Int,
    val onHud: (Boolean) -> Unit,
    val onHudFps: (Boolean) -> Unit = {},
    val onHudBattery: (Boolean) -> Unit = {},
    val onHudGraph: (Boolean) -> Unit = {},
    val onHudColor: (String) -> Unit = {},
    val onHudOpacity: (Int) -> Unit = {},
    val onFrameGenPick: (FrameGen.Mode) -> Unit,
    val onImportLossless: () -> Unit,
    val onKeyboard: () -> Unit,
    val onHardwareKeyboard: () -> Unit,
    val onSteamMenu: (() -> Unit)?,
    val onQam: (() -> Unit)?,
    val onAchievements: (() -> Unit)? = null,
    val achievementsProgress: Pair<Int, Int>? = null,
    val onOsc: (String) -> Unit,
    val onEditSteamTouch: (() -> Unit)? = null,
    val controller: com.droiddeck.launcher.input.ControllerPrefs.Settings? = null,
    val onRumble: (Boolean) -> Unit = {},
    val onSteamButton: (Boolean) -> Unit = {},
    val onQamButton: (Boolean) -> Unit = {},
    val onKeyboardButton: (Boolean) -> Unit = {},
    val onSuspendPolicy: (String) -> Unit,
    val onSteamDownloadsInBackground: (Boolean) -> Unit = {},
    val onBackActionsInverted: (Boolean) -> Unit,
    val lowLatencyController: Boolean = false,
    val lowLatencyTouch: Boolean = false,
    val onLowLatencyController: (Boolean) -> Unit = {},
    val onLowLatencyTouch: (Boolean) -> Unit = {},
    val onTouch: (String) -> Unit,
    val onGestureSettings: () -> Unit = {},
    val onFexPreset: (String) -> Unit,
    val onUpscaler: (Int) -> Unit = {},
    val onUpscaleSharpness: (Int) -> Unit = {},
    val onEffects: (ScreenEffects) -> Unit = {},
    val onCardboard: (Cardboard) -> Unit = {},
    val onCardboardRecenter: () -> Unit = {},
    val onGlassesRecenter: () -> Unit = {},
    val onSteamVr: (String) -> Unit = {},
    val onTextureAnisotropy: (Int?) -> Unit = {},
    val onTextureLodBias: (String?) -> Unit = {},
    val onSecondScreenMode: (SecondScreenMode) -> Unit,
    val onSecondScreenDisplay: (Int) -> Unit,
    val onLaunchAndroidApp: (HomeApp.LaunchableApp, Int?) -> Unit,
    val onBackground: () -> Unit,
    val pipSupported: Boolean = false,
    val pipAutoEnter: Boolean = false,
    val onPip: () -> Unit = {},
    val onPipAutoEnter: (Boolean) -> Unit = {},
    val unfoldedControls: Boolean? = null,
    val onUnfoldedControls: (Boolean) -> Unit = {},
    val unfoldedControlsWithController: Boolean = false,
    val onUnfoldedControlsWithController: (Boolean) -> Unit = {},
    val onShareLogs: () -> Unit,
    val onStop: () -> Unit,
    val onStopFrom: ((androidx.compose.ui.geometry.Rect?) -> Unit)? = null,
    val onClose: () -> Unit,
    val components: ComponentsManager.Snapshot? = null,
    val onComponentsRefresh: () -> Unit = {},
    val componentCatalog: List<ComponentsManager.CatalogItem> = emptyList(),
    val componentCatalogAt: Long = 0,
    val componentCatalogChecking: Boolean = false,
    val componentDownloads: Map<String, Int> = emptyMap(),
    val componentDownloadRevision: Long = 0,
    val onComponentCatalogRefresh: () -> Unit = {},
    val onComponentDownload: (ComponentsManager.CatalogItem) -> Unit = {},
    val gameProfiles: List<Pair<Long, String>> = emptyList(),
    val selectedGameProfileId: Long? = null,
    val selectedGameProfile: SelectedGameProfile? = null,
    val gameProfilesLoaded: Boolean = false,
    val gameProfileRefreshFailed: Boolean = false,
    val onSelectedGameProfile: (Long?) -> Unit = {},
    val onGameProfilesPageOpened: () -> Unit = {},
    val onSelectedGameProfileRefresh: () -> Unit = {},
    val gameFexPresetSaving: Boolean = false,
    val onGameFexPreset: (Long, String?) -> Unit = { _, _ -> },
    val gameNoSteamSaving: Boolean = false,
    val onGameNoSteam: (Long, Boolean) -> Unit = { _, _ -> },
    val onComponentSwap: (protonId: String, comp: String, value: String) -> Unit = { _, _, _ -> },
    val onGameComponent: (appId: Long, comp: String, file: String?) -> Unit = { _, _, _ -> },
    val defaultProtonId: String? = null,
    val onDefaultProton: (protonId: String) -> Unit = {},
    val onGameProton: (appId: Long, protonId: String?) -> Unit = { _, _ -> },
)

@OptIn(ExperimentalComposeUiApi::class)
@Composable
internal fun SessionDrawer(open: Boolean, requestedPage: SessionDrawerPage, controllerActive: Boolean, onPageChange: (SessionDrawerPage) -> Unit, a: DrawerActions) {
    val pages = sessionDrawerPages(a.secondScreenDisplays.isNotEmpty(), a.glassesPresent)
    val page = requestedPage.takeIf { it in pages } ?: SessionDrawerPage.CONTROLLER
    LaunchedEffect(requestedPage, pages) { if (page != requestedPage) onPageChange(page) }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val host = rememberMenuHost()
    val (glasses, glassesSettings) = rememberGlasses()
    val glassesPages = glassesTabs(glasses) + (GlassesTab.CONTROLLERS to stringResource(R.string.vr_controllers_tab))
    var selectedGlassesTab by rememberSaveable { mutableStateOf(GlassesTab.DISPLAY) }
    val glassesTab = selectedGlassesTab.takeIf { key -> glassesPages.any { it.first == key } } ?: GlassesTab.DISPLAY
    var androidAppsExpanded by rememberSaveable { mutableStateOf(false) }
    var advancedEffectsExpanded by rememberSaveable { mutableStateOf(false) }
    var appToChooseDisplay by remember { mutableStateOf<HomeApp.LaunchableApp?>(null) }
    var confirmStop by remember { mutableStateOf(false) }
    var sheetCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    var stopCoords by remember { mutableStateOf<LayoutCoordinates?>(null) }
    val pageScroll = remember { SessionDrawerPage.entries.associateWith { ScrollState(0) } }
    LaunchedEffect(glassesTab) {
        if (page == SessionDrawerPage.GLASSES) host.open = null
        pageScroll.getValue(SessionDrawerPage.GLASSES).scrollTo(0)
    }
    val veil by animateFloatAsState(if (open) 1f else 0f, Motion.tw(260), label = "veil")
    val focus = remember { DrawerFocus() }
    val inputModeManager = LocalInputModeManager.current
    val focusManager = LocalFocusManager.current
    BackHandler(enabled = open) {
        if (host.open != null) host.open = null else a.onClose()
    }
    BackHandler(enabled = open && confirmStop) { confirmStop = false }
    LaunchedEffect(page) { host.open = null; appToChooseDisplay = null }
    LaunchedEffect(a.selectedGameProfileId) {
        if (host.open == "fex") host.open = null
    }
    LaunchedEffect(open, page, a.componentDownloadRevision) {
        if (open && page == SessionDrawerPage.GAMES) {
            a.onGameProfilesPageOpened()
            a.onComponentsRefresh()
            while (true) {
                a.onSelectedGameProfileRefresh()
                delay(1_000)
            }
        }
    }
    LaunchedEffect(open, controllerActive) {
        if (open && !controllerActive) focusManager.clearFocus(force = true)
    }
    var focusedPage by remember { mutableStateOf(page) }
    LaunchedEffect(open, page, glassesTab, controllerActive, host.open, appToChooseDisplay, confirmStop) {
        if (!open) {
            host.open = null
            confirmStop = false
        } else if (controllerActive) {
            inputModeManager.requestInputMode(InputMode.Keyboard)
            if (host.open != null || appToChooseDisplay != null || confirmStop) return@LaunchedEffect
            val turned = page != focusedPage
            focusedPage = page
            val first = when {
                page == SessionDrawerPage.GLASSES -> "glasses-tab-$glassesTab"
                page == SessionDrawerPage.SESSION && a.isHomeApp -> "apps"
                else -> page.firstControl
            }
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
    if (open || veil > 0.01f) {
        val closeLabel = stringResource(R.string.common_close)
        Box(
            modifier = Modifier.fillMaxSize()
                .focusProperties { canFocus = false }
                .semantics { contentDescription = closeLabel }
                .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null, onClickLabel = closeLabel) { host.open = null; a.onClose() },
        )
    }
    androidx.compose.foundation.layout.BoxWithConstraints(modifier = Modifier.fillMaxSize().exposeTestTags(), contentAlignment = Alignment.CenterEnd) {
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
                    .blockTouches()
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
                a.onAchievements?.let { show ->
                    DrawerAchievementsRow(a.achievementsProgress, Modifier.padding(top = if (short) 6.dp else 10.dp).then(focus.track(page, "achievements"))) {
                        host.open = null; show()
                    }
                }

                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(top = if (short) 6.dp else 12.dp),
                ) {
                    DrawerBumper("LB", stringResource(R.string.drawer_prev_page), modifier = focus.track(page, "prev")) {
                        host.open = null; onPageChange(pages.step(page, -1))
                    }
                    DrawerPageTabs(page = page, pages = pages, compact = short, modifier = Modifier.weight(1f)) { index -> host.open = null; onPageChange(index) }
                    DrawerBumper("RB", stringResource(R.string.drawer_next_page), modifier = focus.track(page, "next")) {
                        host.open = null; onPageChange(pages.step(page, 1))
                    }
                }
                if (page == SessionDrawerPage.GLASSES) SegmentedTabs(
                    glassesPages, glassesTab, modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                    tabModifier = { key -> focus.track(page, "glasses-tab-$key").testTag("glasses-tab-$key") },
                ) { selectedGlassesTab = it }
                Column(
                    modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(pageScroll.getValue(page)),
                ) { CompositionLocalProvider(LocalChipMinWidth provides 120.dp) {
                    when (page) {
                        SessionDrawerPage.DISPLAY -> {
                            SettingsGroup(stringResource(R.string.drawer_page_display)) {
                                ToggleRow(host, "hud", stringResource(R.string.drawer_hud), null, a.hudOn,
                                    chipModifier = focus.track(page, "hud"), onChange = a.onHud)
                                if (a.hudOn) {
                                    ToggleRow(host, "hud-fps", stringResource(R.string.drawer_hud_fps), null, a.hudFps,
                                        chipModifier = focus.track(page, "hud-fps"), onChange = a.onHudFps)
                                    ToggleRow(host, "hud-battery", stringResource(R.string.drawer_hud_battery), null, a.hudBattery,
                                        chipModifier = focus.track(page, "hud-battery"), onChange = a.onHudBattery)
                                    ToggleRow(host, "hud-graph", stringResource(R.string.drawer_hud_graph), null, a.hudGraph,
                                        chipModifier = focus.track(page, "hud-graph"), onChange = a.onHudGraph)
                                    ChoiceRow(
                                        host, "hud-color", stringResource(R.string.drawer_hud_color), null,
                                        listOf(
                                            SessionPrefs.HUD_COLOR_MINT to stringResource(R.string.drawer_hud_color_mint),
                                            "white" to stringResource(R.string.drawer_hud_color_white),
                                            "sky" to stringResource(R.string.drawer_hud_color_sky),
                                            "amber" to stringResource(R.string.drawer_hud_color_amber),
                                            "violet" to stringResource(R.string.drawer_hud_color_violet),
                                        ),
                                        a.hudColor, chipModifier = focus.track(page, "hud-color"), onPick = a.onHudColor,
                                    )
                                    SliderRow(
                                        stringResource(R.string.drawer_hud_opacity), null, a.hudOpacity,
                                        SessionPrefs.HUD_OPACITY_RANGE, step = 10, format = { "$it%" },
                                        modifier = focus.track(page, "hud-opacity"), onChange = a.onHudOpacity,
                                    )
                                }
                                val fgOpen = host.open == "fg"
                                val fgLabel = FrameGen.label(LocalContext.current, a.frameGen)
                                SettingsRow(stringResource(R.string.frame_gen_title), null, highlighted = fgOpen) {
                                    Box {
                                        ValueChip(fgLabel, fgOpen, modifier = focus.track(page, "fg")) { host.open = if (fgOpen) null else "fg" }
                                        FrameGenMenu(host, a.frameGen, a.lossless, a.onFrameGenPick, a.onImportLossless)
                                    }
                                }
                            }
                            SettingsGroup(stringResource(R.string.display_image_scaling)) {
                                ChoiceRow(host, "upscaler", stringResource(R.string.display_filter), null,
                                    SessionPrefs.upscalerChoices(LocalContext.current), a.upscaler,
                                    note = stringResource(R.string.display_filter_note),
                                    chipModifier = focus.track(page, "upscaler"), onPick = a.onUpscaler)
                                if (SessionPrefs.upscalerHasSharpness(a.upscaler)) SliderRow(
                                    stringResource(R.string.display_sharpness), null, a.upscaleSharpness, 0..100, step = 5,
                                    format = { "$it%" }, modifier = focus.track(page, "upscale-sharpness"), onChange = a.onUpscaleSharpness)
                            }
                        }
                        SessionDrawerPage.EFFECTS -> {
                            ScreenEffectsGroup(host, a, advancedEffectsExpanded,
                                onAdvancedToggle = { advancedEffectsExpanded = !advancedEffectsExpanded }) { key -> focus.track(page, key) }
                        }
                        SessionDrawerPage.VR -> {
                            SteamVrGroup(a) { key -> focus.track(page, key) }
                            if (!a.glassesOutput) CardboardGroup(host, a) { key -> focus.track(page, key) }
                        }
                        SessionDrawerPage.CONTROLLER -> {
                            SettingsGroup(stringResource(R.string.drawer_page_controller)) {
                                a.padType?.let { type ->
                                    ChoiceRow(host, "pad-type", stringResource(R.string.mode_controller), null,
                                        listOf(SessionPrefs.CONTROLLER_DECK to stringResource(R.string.mode_controller_deck),
                                            SessionPrefs.CONTROLLER_STEAM to stringResource(R.string.mode_controller_steam),
                                            SessionPrefs.CONTROLLER_DS4 to stringResource(R.string.mode_controller_ds4),
                                            SessionPrefs.CONTROLLER_DUALSENSE to stringResource(R.string.mode_controller_dualsense),
                                            SessionPrefs.CONTROLLER_XBOX360 to stringResource(R.string.mode_controller_x360)),
                                        type, chipModifier = focus.track(page, "pad-type"), onPick = a.onPadType)
                                }
                                ChoiceRow(host, "touch", stringResource(R.string.mode_touch), null,
                                    listOf(SessionPrefs.TOUCH_AUTO to stringResource(R.string.drawer_touch_auto, a.touchAuto), SessionPrefs.TOUCH_PAD to stringResource(R.string.mode_touch_touchpad), SessionPrefs.TOUCH_DIRECT to stringResource(R.string.mode_touch_direct), SessionPrefs.TOUCH_GESTURES to stringResource(R.string.mode_touch_gestures), SessionPrefs.TOUCH_OFF to stringResource(R.string.widgets_off)),
                                    a.touchMode, chipModifier = focus.track(page, "touch"), onPick = a.onTouch)
                                ActionRow(stringResource(R.string.gesture_settings), stringResource(R.string.gesture_settings_hint),
                                    stringResource(R.string.ctrl_edit), a.onGestureSettings,
                                    modifier = focus.track(page, "gesture-settings"))
                                ChoiceRow(host, "osc", stringResource(R.string.mode_osc), null,
                                    if (a.steam) listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always), SessionPrefs.OSC_STEAM_TOUCH to stringResource(R.string.osc_steam_touch), SessionPrefs.OSC_STEAM_QAM to stringResource(R.string.mode_osc_qam), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never))
                                    else listOf(SessionPrefs.OSC_AUTO to stringResource(R.string.common_auto), SessionPrefs.OSC_ALWAYS to stringResource(R.string.common_always), SessionPrefs.OSC_NEVER to stringResource(R.string.common_never)),
                                    if (!a.steam && a.oscMode == SessionPrefs.OSC_STEAM_TOUCH) SessionPrefs.OSC_AUTO else a.oscMode,
                                    chipModifier = focus.track(page, "osc"), onPick = a.onOsc)
                                a.onEditSteamTouch?.let { edit ->
                                    ActionRow(stringResource(R.string.osc_steam_layout), stringResource(R.string.osc_steam_layout_hint), stringResource(R.string.ctrl_edit), edit)
                                }
                                a.controller?.let { c ->
                                    ToggleRow(host, "rumble", stringResource(R.string.ctrl_rumble), null, c.rumble,
                                        chipModifier = focus.track(page, "rumble"), onChange = a.onRumble)
                                    if (a.onScreenButtonsVisible) {
                                        if (a.steam) {
                                            ToggleRow(host, "steam-button", stringResource(R.string.ctrl_steam_button), null, c.steamButton,
                                                chipModifier = focus.track(page, "steam-button"), onChange = a.onSteamButton)
                                            ToggleRow(host, "qam-button", stringResource(R.string.ctrl_qam_button), null, c.qamButton,
                                                chipModifier = focus.track(page, "qam-button"), onChange = a.onQamButton)
                                        }
                                        ToggleRow(host, "keyboard-button", stringResource(R.string.ctrl_keyboard_button), null, c.keyboardButton,
                                            chipModifier = focus.track(page, "keyboard-button"), onChange = a.onKeyboardButton)
                                    }
                                }
                                if (a.steam) ChoiceRow(host, "back-actions", stringResource(R.string.mode_back), null,
                                    listOf(false to stringResource(SessionPrefs.BACK_MENU_THEN_QAM), true to stringResource(SessionPrefs.BACK_QAM_THEN_MENU)),
                                    a.backActionsInverted, chipModifier = focus.track(page, "back-actions"), onPick = a.onBackActionsInverted)
                                if (a.steam && a.unfoldedControls != null) ToggleRow(host, "unfolded-controls", stringResource(R.string.unfolded_controls),
                                    stringResource(R.string.unfolded_controls_hint), a.unfoldedControls,
                                    chipModifier = focus.track(page, "unfolded-controls"), onChange = a.onUnfoldedControls)
                                if (a.steam && a.unfoldedControls == true) ToggleRow(host, "unfolded-controls-with-controller",
                                    stringResource(R.string.unfolded_controls_with_controller),
                                    stringResource(R.string.unfolded_controls_with_controller_hint), a.unfoldedControlsWithController,
                                    chipModifier = focus.track(page, "unfolded-controls-with-controller"),
                                    onChange = a.onUnfoldedControlsWithController)
                            }
                            SettingsGroup(stringResource(R.string.drawer_latency)) {
                                ToggleRow(host, "low-latency-controller", stringResource(R.string.drawer_low_latency_controller),
                                    stringResource(R.string.drawer_low_latency_controller_hint), a.lowLatencyController,
                                    chipModifier = focus.track(page, "low-latency-controller"), onChange = a.onLowLatencyController)
                                ToggleRow(host, "low-latency-touch", stringResource(R.string.drawer_low_latency_touch),
                                    stringResource(R.string.drawer_low_latency_touch_hint), a.lowLatencyTouch,
                                    chipModifier = focus.track(page, "low-latency-touch"), onChange = a.onLowLatencyTouch)
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
                        }
                        SessionDrawerPage.SECOND_SCREEN -> {
                            SecondScreenGroup(host, a) { key -> focus.track(page, key) }
                        }
                        SessionDrawerPage.GLASSES -> if (glassesTab == GlassesTab.CONTROLLERS) {
                            VrControllerGroups(host) { key -> focus.track(page, key) }
                        } else {
                            GlassesGroups(host, glasses, glassesSettings, { key -> focus.track(page, key) }, onRecenter = a.onGlassesRecenter,
                                tab = glassesTab, outputActive = a.glassesOutput, steamVrRunning = a.steamVr == SteamVr.Status.RUNNING,
                                steamVr = { SteamVrRow(a) })
                        }
                        SessionDrawerPage.GAMES -> {
                            if (a.steam) SelectedGameProfileGroup(host, a) { key -> focus.track(page, key) }
                            Text(stringResource(R.string.fex_next_launch), fontSize = 12.sp,
                                color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
                            TextureFilteringGroup(host, a) { key -> focus.track(page, key) }
                            if (a.steam) SettingsGroup(stringResource(R.string.game_settings_title)) {
                                DrawerFexPresetRow(host, a, focus.track(page, "fex"))
                                DrawerNoSteamRow(host, a, focus.track(page, "no-steam"))
                                GameEnvironmentRow(
                                    modifier = focus.track(page, "game-env"),
                                    showHint = false,
                                    initialScope = a.selectedGameProfileId?.toString().orEmpty(),
                                )
                            }
                            ComponentsDrawerPage(host, a) { key -> focus.track(page, key) }
                            ComponentDownloadsDrawerGroup(
                                host = host,
                                catalog = a.componentCatalog,
                                catalogAt = a.componentCatalogAt,
                                checking = a.componentCatalogChecking,
                                packages = a.components?.packages.orEmpty(),
                                downloads = a.componentDownloads,
                                onRefresh = a.onComponentCatalogRefresh,
                                onDownload = a.onComponentDownload,
                                track = { key -> focus.track(page, key) },
                            )
                        }
                        SessionDrawerPage.SESSION -> {
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
                                    null,
                                    listOf(
                                        SessionPrefs.SUSPEND_AUTO to stringResource(R.string.common_auto),
                                        SessionPrefs.SUSPEND_MANUAL to stringResource(R.string.mode_suspend_manual),
                                        SessionPrefs.SUSPEND_NEVER to stringResource(R.string.common_never),
                                    ) + if (a.steam) listOf(
                                        SessionPrefs.SUSPEND_NATIVE to stringResource(R.string.mode_suspend_native),
                                    ) else emptyList(),
                                    a.suspendPolicy,
                                    note = stringResource(if (a.steam) R.string.mode_suspend_steam_note else R.string.mode_suspend_note),
                                    chipModifier = focus.track(page, "suspend"),
                                    onPick = a.onSuspendPolicy,
                                )
                                if (a.steam && a.suspendPolicy != SessionPrefs.SUSPEND_NEVER) ToggleRow(
                                    host, "background-downloads", stringResource(R.string.mode_background_downloads),
                                    stringResource(R.string.mode_background_downloads_hint),
                                    a.steamDownloadsInBackground,
                                    onChange = a.onSteamDownloadsInBackground,
                                )
                            }
                            SettingsGroup(stringResource(R.string.drawer_support)) {
                                SettingsRow(stringResource(R.string.drawer_logs), stringResource(R.string.drawer_logs_hint)) {
                                    DrawerOutlineButton(stringResource(R.string.drawer_share_logs), modifier = focus.track(page, "share-logs"),
                                        progress = com.droiddeck.launcher.session.SessionLogShare.progress) {
                                        host.open = null
                                        a.onShareLogs()
                                    }
                                }
                            }
                            if (a.pipSupported) SettingsGroup(stringResource(R.string.pip_title)) {
                                ToggleRow(host, "pip-auto", stringResource(R.string.pip_auto), null,
                                    a.pipAutoEnter, onChange = a.onPipAutoEnter)
                            }
                            Column(Modifier.fillMaxWidth().padding(top = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                                if (a.pipSupported) DrawerActionButton(stringResource(R.string.pip_title), Icons.Outlined.PictureInPictureAlt,
                                    modifier = focus.track(page, "pip")) { host.open = null; a.onPip() }
                                if (!a.isHomeApp) DrawerActionButton(stringResource(R.string.drawer_background), Icons.AutoMirrored.Outlined.ExitToApp,
                                    modifier = focus.track(page, "background")) { host.open = null; a.onBackground() }
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
private fun DrawerActionButton(text: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val source = remember { MutableInteractionSource() }
    val hot = source.collectIsFocusedAsState().value || source.collectIsHoveredAsState().value
    val shape = RoundedCornerShape(8.dp)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        modifier = modifier.fillMaxWidth().heightIn(min = 52.dp).clip(shape)
            .background(if (hot) pal.signal.copy(alpha = 0.16f) else colors.surfaceVariant)
            .glideBorder(hot, shape, pal.signal)
            .hoverable(source).clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = source, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
    ) {
        Icon(icon, contentDescription = null, tint = colors.onBackground, modifier = Modifier.size(22.dp))
        Text(text, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
    }
}

@Composable
private fun DrawerOutlineButton(text: String, modifier: Modifier = Modifier, progress: Float? = null, onClick: () -> Unit) {
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
    ) { ProgressLabel({ m -> Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, modifier = m) }, progress, fontSize = 13.sp) }
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
        modifier = modifier.heightIn(min = 44.dp).semantics { contentDescription = stopDescription }.testTag("drawer-stop")
            .clip(shape).background(fill).glideBorder(hot, shape, colors.error.copy(alpha = 0.9f), colors.error.copy(alpha = 0.55f))
            .hoverable(src).clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = src, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(start = 12.dp, end = 14.dp),
    ) {
        Icon(Icons.Outlined.PowerSettingsNew, contentDescription = null, tint = colors.error, modifier = Modifier.size(18.dp))
        Text(stringResource(R.string.drawer_stop), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.error)
    }
}

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
            .clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = interactionSource, indication = LocalIndication.current, onClick = onClick)
            .controllerConfirm(onClick = onConfirm ?: onClick),
    ) {
        Icon(icon, contentDescription = null, tint = if (hot) pal.signal else colors.onBackground, modifier = Modifier.size(20.dp))
    }
}

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
            .hoverable(src).clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = src, indication = null, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .semantics { contentDescription = description },
    ) {
        Text(
            label, fontSize = 12.sp, fontWeight = FontWeight.Bold, color = if (hot) colors.onBackground else colors.onSurfaceVariant,
            modifier = Modifier.clip(cap).background(colors.surfaceVariant).border(1.dp, pal.line2, cap).padding(horizontal = 6.dp, vertical = 3.dp),
        )
    }
}

@Composable
private fun DrawerPageTabs(page: SessionDrawerPage, pages: List<SessionDrawerPage>, modifier: Modifier = Modifier, compact: Boolean = false, onSelect: (SessionDrawerPage) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val count = pages.size
    val selectedIndex = pages.indexOf(page)
    val middle = (count - 1) / 2f
    val motion = Motion.tw<Float>(340, easing = FastOutSlowInEasing)
    androidx.compose.foundation.layout.BoxWithConstraints(
        contentAlignment = Alignment.Center,
        modifier = modifier.height(if (compact) 44.dp else 64.dp).clipToBounds().selectableGroup(),
    ) {
        val tab = if (compact) 36f else 44f
        val spacing = ((maxWidth.value - tab) / ((count - 1) * (1f + DRAWER_TAB_LEAN_DP / DRAWER_TAB_SPACING_DP))).coerceIn(24f, DRAWER_TAB_SPACING_DP)
        val leanStep = DRAWER_TAB_LEAN_DP * spacing / DRAWER_TAB_SPACING_DP
        val lean by animateFloatAsState(-(selectedIndex - middle) * leanStep, motion, label = "tabLean")
        pages.forEachIndexed { index, item ->
            androidx.compose.runtime.key(item) {
                val selected = item == page
                val source = remember { MutableInteractionSource() }
                val focused = source.collectIsFocusedAsState().value
                val scale by animateFloatAsState(if (selected) 1f else DRAWER_TAB_SIDE_SCALE, motion, label = "tabScale")
                val alpha by animateFloatAsState(if (selected) 1f else DRAWER_TAB_SIDE_ALPHA, motion, label = "tabAlpha")
                val glow by animateFloatAsState(if (selected) 1f else 0f, motion, label = "tabGlow")
                val pageDescription = stringResource(R.string.drawer_page_desc, stringResource(item.title))
                val tint by animateColorAsState(if (selected) colors.onBackground else colors.onSurfaceVariant, Motion.tw(340, easing = FastOutSlowInEasing), label = "tabTint")
                val select = { onSelect(item) }
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
                        .testTag("drawer-tab-${item.name.lowercase()}")
                        .hoverable(source)
                        .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab, interactionSource = source, indication = LocalIndication.current, onClick = select)
                        .controllerConfirm(onClick = select),
                ) {
                    Icon(item.icon, contentDescription = null, tint = tint, modifier = Modifier.size(if (compact) 22.dp else 30.dp))
                }
            }
        }
    }
}

private const val LOOK_CUSTOM = "custom"

@Composable
private fun ScreenEffectsGroup(
    host: MenuHost, a: DrawerActions, advancedExpanded: Boolean, onAdvancedToggle: () -> Unit,
    track: (String) -> Modifier,
) {
    val e = a.effects
    val preset = ScreenEffectLooks.match(e, a.upscaler)
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    SettingsGroup(stringResource(R.string.drawer_effects)) {
        val presets = ScreenEffectLooks.LOOKS.map { it.name to stringResource(it.label) }
        ChoiceRow(host, "look", stringResource(R.string.drawer_look), null,
            if (preset == null) listOf(LOOK_CUSTOM to stringResource(R.string.drawer_look_custom)) + presets else presets,
            preset?.name ?: LOOK_CUSTOM, chipModifier = track("look")) { name ->
            ScreenEffectLooks.LOOKS.firstOrNull { it.name == name }?.let { picked ->
                picked.scalingMode?.let(a.onUpscaler)
                a.onEffects(picked.effects)
            }
        }
        ToggleRow(host, "fxaa", stringResource(R.string.drawer_fxaa), null, e.fxaa, chipModifier = track("fxaa")) { a.onEffects(e.copy(fxaa = it)) }
        ToggleRow(host, "toon", stringResource(R.string.drawer_toon), null, e.toon, chipModifier = track("toon")) { a.onEffects(e.copy(toon = it)) }
        ToggleRow(host, "crt", stringResource(R.string.drawer_crt), null, e.crt, chipModifier = track("crt")) { a.onEffects(e.copy(crt = it)) }
        ToggleRow(host, "ntsc", stringResource(R.string.drawer_ntsc), null, e.ntsc, chipModifier = track("ntsc")) { a.onEffects(e.copy(ntsc = it)) }
        ToggleRow(host, "fake-hdr", stringResource(R.string.drawer_fake_hdr), null, e.hdr, chipModifier = track("fake-hdr")) { a.onEffects(e.copy(hdr = it)) }
    }
    val source = remember { MutableInteractionSource() }
    val hot = source.collectIsFocusedAsState().value || source.collectIsHoveredAsState().value
    val rotation by animateFloatAsState(if (advancedExpanded) 180f else 0f, Motion.tw(180), label = "advancedArrow")
    val description = stringResource(if (advancedExpanded) R.string.widgets_expanded else R.string.widgets_collapsed)
    val toggle = { host.open = null; onAdvancedToggle() }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = track("effects-advanced").fillMaxWidth().padding(top = 12.dp).heightIn(min = 48.dp)
            .glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
            .hoverable(source).clickable(interactionSource = source, indication = LocalIndication.current, role = Role.Button, onClick = toggle)
            .controllerConfirm(onClick = toggle).semantics { stateDescription = description }.padding(horizontal = 4.dp),
    ) {
        Text(stringResource(R.string.drawer_effects_advanced).uppercase(), fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold, letterSpacing = 1.5.sp, color = colors.onSurfaceVariant)
        Box(Modifier.weight(1f).height(1.dp).background(pal.line))
        Icon(Icons.Outlined.ExpandMore, contentDescription = null, tint = colors.onSurfaceVariant,
            modifier = Modifier.size(22.dp).rotate(rotation))
    }
    AnimatedVisibility(advancedExpanded) {
        Column(Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(colors.surface).border(1.dp, pal.line, RoundedCornerShape(14.dp))) {
            ToggleRow(host, "cas", stringResource(R.string.drawer_cas), null, e.cas, chipModifier = track("cas")) { a.onEffects(e.copy(cas = it)) }
            if (e.cas) SliderRow(stringResource(R.string.drawer_cas_level), null, e.casLevel, 0..100, step = 5,
                format = { "$it%" }, modifier = track("cas-level")) { a.onEffects(e.copy(casLevel = it)) }
            ToggleRow(host, "deband", stringResource(R.string.drawer_deband), null, e.deband, chipModifier = track("deband")) { a.onEffects(e.copy(deband = it)) }
            if (e.deband) SliderRow(stringResource(R.string.drawer_deband_strength), null, e.debandStrength, 0..200, step = 5,
                format = { "$it%" }, modifier = track("deband-strength")) { a.onEffects(e.copy(debandStrength = it)) }
            SliderRow(stringResource(R.string.drawer_brightness), null, e.brightness, -100..100, step = 2,
                format = ::signed, modifier = track("brightness")) { a.onEffects(e.copy(brightness = it)) }
            SliderRow(stringResource(R.string.drawer_contrast), null, e.contrast, -100..100, step = 2,
                format = ::signed, modifier = track("contrast")) { a.onEffects(e.copy(contrast = it)) }
            SliderRow(stringResource(R.string.drawer_gamma), null, (e.gamma * 100f).roundToInt(), 50..300, step = 5,
                format = { String.format(Locale.US, "%.2f", it / 100f) }, modifier = track("gamma")) { a.onEffects(e.copy(gamma = it / 100f)) }
            SliderRow(stringResource(R.string.drawer_saturation), null, e.saturation, 0..200, step = 5,
                format = { "$it%" }, modifier = track("saturation")) { a.onEffects(e.copy(saturation = it)) }
        }
    }
}

@Composable
private fun CardboardGroup(host: MenuHost, a: DrawerActions, track: (String) -> Modifier) {
    val c = a.cardboard
    SettingsGroup(stringResource(R.string.cardboard_title)) {
        ToggleRow(host, "cardboard", stringResource(R.string.cardboard_mode), stringResource(R.string.cardboard_mode_hint),
            c.enabled, chipModifier = track("cardboard")) { a.onCardboard(c.copy(enabled = it)) }
        if (!c.enabled) return@SettingsGroup
        ChoiceRow(host, "cardboard-source", stringResource(R.string.cardboard_source), null,
            listOf(Cardboard.SOURCE_SCREEN to stringResource(R.string.cardboard_source_screen),
                Cardboard.SOURCE_SBS to stringResource(R.string.cardboard_source_sbs)),
            c.source, chipModifier = track("cardboard-source")) { a.onCardboard(c.copy(source = it)) }
        ChoiceRow(host, "cardboard-viewer", stringResource(R.string.cardboard_viewer), null,
            listOf(Cardboard.Viewer.CARDBOARD_V2.id to stringResource(R.string.cardboard_viewer_v2),
                Cardboard.Viewer.CARDBOARD_V1.id to stringResource(R.string.cardboard_viewer_v1),
                Cardboard.Viewer.NONE.id to stringResource(R.string.cardboard_viewer_none)),
            c.viewer, chipModifier = track("cardboard-viewer")) { a.onCardboard(c.copy(viewer = it)) }
        SliderRow(stringResource(R.string.cardboard_ipd), null, c.ipdMm, 50..80, step = 1,
            format = { "$it mm" }, modifier = track("cardboard-ipd")) { a.onCardboard(c.copy(ipdMm = it)) }
        if (c.source == Cardboard.SOURCE_SCREEN) {
            SliderRow(stringResource(R.string.cardboard_screen_width), null, c.screenWidthDm, 5..200, step = 5,
                format = { String.format(Locale.US, "%.1f m", it / 10f) }, modifier = track("cardboard-width")) { a.onCardboard(c.copy(screenWidthDm = it)) }
            SliderRow(stringResource(R.string.cardboard_screen_distance), null, c.screenDistanceDm, 5..200, step = 5,
                format = { String.format(Locale.US, "%.1f m", it / 10f) }, modifier = track("cardboard-distance")) { a.onCardboard(c.copy(screenDistanceDm = it)) }
        }
        ToggleRow(host, "cardboard-tracking", stringResource(R.string.cardboard_head_tracking),
            if (a.cardboardSensor) null else stringResource(R.string.cardboard_no_sensor),
            c.headTracking && a.cardboardSensor, enabled = a.cardboardSensor, chipModifier = track("cardboard-tracking")) {
            a.onCardboard(c.copy(headTracking = it))
        }
        if (c.headTracking && a.cardboardSensor)
            ActionRow(stringResource(R.string.cardboard_recenter), null, stringResource(R.string.cardboard_recenter_button), a.onCardboardRecenter)
    }
}

@Composable
private fun SteamVrGroup(a: DrawerActions, track: (String) -> Modifier) {
    SettingsGroup(stringResource(R.string.steamvr_title)) {
        val vrLabel = stringResource(R.string.cardboard_steamvr)
        val enterLabel = stringResource(if (a.immersiveSteamVr) R.string.steamvr_enter else R.string.cardboard_steamvr_start)
        @Composable fun button(label: String, text: String, action: String) {
            SettingsRow(label, null) {
                SecondaryButton(text, modifier = track("steamvr"), onClick = { a.onSteamVr(action) })
            }
        }
        when (a.steamVr) {
            SteamVr.Status.NOT_INSTALLED -> button(vrLabel, stringResource(R.string.cardboard_steamvr_install), "install")
            SteamVr.Status.INSTALLING -> SettingsRow(vrLabel, stringResource(R.string.cardboard_steamvr_installing)) {}
            SteamVr.Status.READY -> button(vrLabel, enterLabel, "start")
            SteamVr.Status.RUNNING -> {
                if (a.immersiveSteamVr) button(vrLabel, enterLabel, "start")
                SettingsRow(vrLabel, null) {
                    SecondaryButton(stringResource(R.string.cardboard_steamvr_stop),
                        modifier = track(if (a.immersiveSteamVr) "steamvr-stop" else "steamvr"), onClick = { a.onSteamVr("stop") })
                }
            }
            SteamVr.Status.UNAVAILABLE -> SettingsRow(vrLabel, stringResource(R.string.cardboard_steamvr_unavailable)) {}
            null -> SettingsRow(vrLabel, stringResource(R.string.steamvr_checking)) {}
        }
    }
}

@Composable
private fun SteamVrRow(a: DrawerActions) {
    val vrLabel = stringResource(R.string.cardboard_steamvr)
    when (a.steamVr) {
        SteamVr.Status.NOT_INSTALLED -> ActionRow(vrLabel, null, stringResource(R.string.cardboard_steamvr_install), { a.onSteamVr("install") })
        SteamVr.Status.INSTALLING -> SettingsRow(vrLabel, stringResource(R.string.cardboard_steamvr_installing)) {}
        SteamVr.Status.READY -> ActionRow(vrLabel, null, stringResource(R.string.cardboard_steamvr_start), { a.onSteamVr("start") })
        SteamVr.Status.RUNNING -> ActionRow(vrLabel, null, stringResource(R.string.cardboard_steamvr_stop), { a.onSteamVr("stop") })
        SteamVr.Status.UNAVAILABLE -> SettingsRow(vrLabel, stringResource(R.string.cardboard_steamvr_unavailable)) {}
        null -> {}
    }
}

@Composable
private fun SecondScreenGroup(host: MenuHost, a: DrawerActions, track: (String) -> Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val modes = listOf(SecondScreenMode.NONE, SecondScreenMode.KEYBOARD_TRACKPAD, SecondScreenMode.TERMINAL) +
        if (com.droiddeck.launcher.session.SessionState.deckPad) listOf(SecondScreenMode.DECK_CONTROLS) else emptyList()
    SettingsGroup(stringResource(R.string.drawer_second_screen_mode)) {
        Column(Modifier.fillMaxWidth().selectableGroup()) {
            modes.forEach { mode ->
                val selected = a.secondScreenMode == mode
                val source = remember { MutableInteractionSource() }
                val hot = source.collectIsFocusedAsState().value || source.collectIsHoveredAsState().value
                val pick = { host.open = null; a.onSecondScreenMode(mode) }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = track("second-screen:${mode.id}").fillMaxWidth().heightIn(min = 56.dp)
                        .background(if (hot) pal.signal.copy(alpha = 0.12f) else Color.Transparent)
                        .glideBorder(hot, RoundedCornerShape(8.dp), pal.signal)
                        .hoverable(source)
                        .selectable(selected, interactionSource = source, indication = null, role = Role.RadioButton, onClick = pick)
                        .controllerConfirm(onClick = pick)
                        .padding(horizontal = 14.dp, vertical = 8.dp),
                ) {
                    Text(stringResource(mode.label), fontSize = 15.sp, color = colors.onBackground, modifier = Modifier.weight(1f))
                    RadioButton(selected, onClick = null,
                        colors = RadioButtonDefaults.colors(selectedColor = pal.signal, unselectedColor = colors.onSurfaceVariant))
                }
            }
        }
        if (a.secondScreenDisplays.size > 1) ChoiceRow(host, "second-screen-display", stringResource(R.string.drawer_page_display), null,
            a.secondScreenDisplays.map { it.id to it.label }, a.selectedSecondScreenDisplay,
            chipModifier = track("second-screen-display"), onPick = a.onSecondScreenDisplay)
    }
}

@Composable
private fun DrawerFexPresetRow(host: MenuHost, a: DrawerActions, modifier: Modifier) {
    val appId = a.selectedGameProfileId
    val profile = a.selectedGameProfile?.takeIf { it.appId == appId }
    val inherited = profile?.fexPreset?.inherited == true
    val hint = if (profile == null) null else stringResource(
        if (inherited) R.string.comp_game_fex_inherited else R.string.comp_game_fex_override,
    )
    SettingsRow(stringResource(R.string.fex_preset_title), hint, highlighted = host.open == "fex") {
        if (appId != null && profile == null) {
            ValueChip("-", false, enabled = false, modifier = modifier) {}
        } else {
            FexPresetChip(
                preset = profile?.fexPreset?.value ?: a.fexPreset,
                inherited = inherited,
                open = host.open == "fex",
                enabled = !a.gameFexPresetSaving,
                onToggle = { host.open = if (host.open == "fex") null else "fex" },
                onDismiss = { if (host.open == "fex") host.open = null },
                onPick = { preset ->
                    if (appId == null) {
                        preset?.let(a.onFexPreset)
                    } else {
                        a.onGameFexPreset(appId, preset)
                    }
                },
                allowInheritance = appId != null,
                modifier = modifier,
            )
        }
    }
}

@Composable
private fun DrawerNoSteamRow(host: MenuHost, a: DrawerActions, modifier: Modifier) {
    val appId = a.selectedGameProfileId ?: return
    val profile = a.selectedGameProfile?.takeIf { it.appId == appId } ?: return
    ToggleRow(
        host, "no-steam", stringResource(R.string.game_no_steam_title), stringResource(R.string.game_no_steam_detail),
        profile.startWithoutSteam, enabled = !a.gameNoSteamSaving, chipModifier = modifier,
    ) { on -> a.onGameNoSteam(appId, on) }
}

@Composable
private fun TextureFilteringGroup(host: MenuHost, a: DrawerActions, track: (String) -> Modifier) {
    val inherited = a.selectedGameProfileId != null
    val anisotropyChoices: List<Pair<Int?, String>> =
        (if (inherited) listOf(null to stringResource(R.string.comp_game_use_general)) else emptyList()) +
            SessionPrefs.textureAnisotropyChoices(LocalContext.current).map { (value, label) -> value as Int? to label }
    val lodBiasChoices: List<Pair<String?, String>> =
        (if (inherited) listOf(null to stringResource(R.string.comp_game_use_general)) else emptyList()) +
            SessionPrefs.textureLodBiasChoices(LocalContext.current).map { (value, label) -> value as String? to label }
    SettingsGroup(stringResource(R.string.drawer_texture)) {
        ChoiceRow(host, "anisotropy", stringResource(R.string.drawer_anisotropy), null,
            anisotropyChoices, a.textureAnisotropy, chipModifier = track("anisotropy"), onPick = a.onTextureAnisotropy)
        ChoiceRow(host, "texture-sharpness", stringResource(R.string.drawer_texture_sharpness), null,
            lodBiasChoices, a.textureLodBias, note = stringResource(R.string.drawer_texture_sharpness_note),
            chipModifier = track("texture-sharpness"), onPick = a.onTextureLodBias)
    }
}

private fun signed(v: Int) = if (v > 0) "+$v" else "$v"

@Composable
private fun SelectedGameProfileGroup(host: MenuHost, a: DrawerActions, track: (String) -> Modifier) {
    val colors = MaterialTheme.colorScheme
    val options: List<Pair<Long?, String>> = listOf(null to stringResource(R.string.comp_scope_general)) +
        a.gameProfiles.map { (id, name) -> Pair(id, name) }
    val profile = a.selectedGameProfile
    SettingsGroup(stringResource(R.string.drawer_selected_profile)) {
        DrawerStackedChoice(
            host = host,
            key = "game-profile",
            label = stringResource(R.string.comp_profile),
            hint = if (a.selectedGameProfileId == null) stringResource(R.string.comp_scope_general_detail)
            else stringResource(R.string.drawer_profile_inspect_hint),
            options = options,
            selected = a.selectedGameProfileId,
            chipModifier = track("game-profile"),
            onPick = a.onSelectedGameProfile,
        )
        if (a.gameProfileRefreshFailed) {
            Text(
                stringResource(
                    if (a.gameProfilesLoaded) {
                        R.string.drawer_profile_refresh_failed_stale
                    } else {
                        R.string.drawer_profile_refresh_failed_initial
                    },
                ),
                fontSize = 13.sp,
                color = colors.error,
                modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
        if (a.selectedGameProfileId == null) return@SettingsGroup
        if (profile == null) {
            if (!a.gameProfileRefreshFailed) Text(
                stringResource(R.string.drawer_selected_profile_none),
                fontSize = 13.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(14.dp),
            )
            return@SettingsGroup
        }
        val hasOverrides = profile.hasOverrides ||
            a.textureAnisotropy != null ||
            a.textureLodBias != null
        Column(modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 11.dp)) {
            Text(
                stringResource(
                    if (hasOverrides) R.string.drawer_profile_custom
                    else R.string.drawer_profile_default,
                ),
                fontSize = 12.sp,
                color = colors.onSurfaceVariant,
                modifier = Modifier.padding(bottom = if (hasOverrides) 5.dp else 0.dp),
            )
            if (!profile.fexPreset.inherited) {
                ProfileSummaryRow(stringResource(R.string.comp_game_fex), fexProfileLabel(profile.fexPreset))
            }
            if (!profile.proton.inherited) {
                ProfileSummaryRow(stringResource(R.string.comp_proton), profileValueLabel(profile.proton))
            }
            for (component in ComponentsManager.COMPONENTS) {
                val value = profile.components.getValue(component)
                if (!value.inherited) {
                    ProfileSummaryRow(ComponentsManager.LABEL.getValue(component), profileValueLabel(value))
                }
            }
            if (profile.startWithoutSteam) {
                ProfileSummaryRow(stringResource(R.string.game_no_steam_title), stringResource(R.string.game_no_steam_on))
            }
            if (profile.environmentOverrides > 0) {
                ProfileSummaryRow(
                    stringResource(R.string.game_env_title),
                    pluralStringResource(
                        R.plurals.drawer_profile_environment_overrides,
                        profile.environmentOverrides,
                        profile.environmentOverrides,
                    ),
                )
            }
            if (profile.windowsComponentNames.isNotEmpty()) {
                ProfileSummaryRow(
                    stringResource(R.string.wincomp_title),
                    profile.windowsComponentNames.joinToString(", "),
                )
            }
            a.textureAnisotropy?.let { value ->
                ProfileSummaryRow(
                    stringResource(R.string.drawer_anisotropy),
                    SessionPrefs.textureAnisotropyChoices(LocalContext.current)
                        .firstOrNull { it.first == value }?.second.orEmpty(),
                )
            }
            a.textureLodBias?.let { value ->
                ProfileSummaryRow(
                    stringResource(R.string.drawer_texture_sharpness),
                    SessionPrefs.textureLodBiasChoices(LocalContext.current)
                        .firstOrNull { it.first == value }?.second.orEmpty(),
                )
            }
        }
    }
}

@Composable
private fun fexProfileLabel(value: ProfileValue): String {
    return when (value.value) {
        GameEnvironment.CUSTOM_FEX_PRESET -> stringResource(R.string.comp_game_fex_custom)
        else -> stringResource(FexPreset.byId(value.value).label)
    }
}

@Composable
private fun profileValueLabel(value: ProfileValue) =
    value.value.ifEmpty { stringResource(R.string.comp_no_proton_short) }

@Composable
private fun ProfileSummaryRow(label: String, value: String) {
    Row(modifier = Modifier.fillMaxWidth().padding(top = 3.dp)) {
        Text(label, fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(96.dp))
        Text(
            value,
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onBackground,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

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
    val gameProfile = a.selectedGameProfileId?.let { appId ->
        a.selectedGameProfile?.takeIf { it.appId == appId }
    }
    if (a.selectedGameProfileId != null) {
        if (gameProfile == null) {
            SettingsGroup(stringResource(R.string.drawer_page_components)) {
                Text(
                    stringResource(R.string.comp_reading),
                    fontSize = 13.sp,
                    color = colors.onSurfaceVariant,
                    modifier = Modifier.padding(14.dp),
                )
            }
            return
        }
        SettingsGroup(stringResource(R.string.drawer_page_components)) {
            DrawerStackedChoice(
                host = host,
                key = "game-cmp-proton",
                label = stringResource(R.string.comp_proton),
                hint = if (gameProfile.proton.inherited && gameProfile.proton.value.isNotEmpty()) {
                    stringResource(R.string.drawer_component_inherited, gameProfile.proton.value)
                } else {
                    null
                },
                options = listOf<Pair<String?, String>>(null to stringResource(R.string.comp_game_use_general)) +
                    snap.protons.map { it.proton.id to it.proton.name },
                selected = if (gameProfile.proton.inherited) null else gameProfile.protonId,
                chipModifier = track("cmp-proton"),
                onPick = { id -> a.onGameProton(gameProfile.appId, id) },
            )
            for (comp in ComponentsManager.COMPONENTS) {
                val value = gameProfile.components.getValue(comp)
                val options: List<Pair<String?, String>> =
                    listOf(null to stringResource(R.string.comp_game_use_general)) +
                        snap.packages.filter { it.comp == comp }.map { it.file to it.version }
                DrawerStackedChoice(
                    host = host,
                    key = "game-cmp-$comp",
                    label = ComponentsManager.LABEL.getValue(comp),
                    hint = if (value.inherited) {
                        stringResource(R.string.drawer_component_inherited, value.value)
                    } else {
                        null
                    },
                    options = options,
                    selected = gameProfile.componentFiles[comp],
                    chipModifier = track("cmp-$comp"),
                    onPick = { file -> a.onGameComponent(gameProfile.appId, comp, file) },
                )
            }
        }
        Text(
            stringResource(R.string.drawer_game_components_note, gameProfile.name),
            fontSize = 12.sp,
            color = colors.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 12.dp),
        )
        return
    }
    val running = snap.protons.filter { it.inUseByGame }
    val view = snap.protons.firstOrNull { it.proton.id == a.defaultProtonId } ?: running.firstOrNull() ?: snap.protons.firstOrNull()
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
            chipModifier = track("cmp-proton"), onPick = { if (it != p.id) a.onDefaultProton(it) },
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

@Composable
internal fun ComponentDownloadsDrawerGroup(
    host: MenuHost,
    catalog: List<ComponentsManager.CatalogItem>,
    catalogAt: Long,
    checking: Boolean,
    packages: List<ComponentsManager.Package>,
    downloads: Map<String, Int>,
    onRefresh: () -> Unit,
    onDownload: (ComponentsManager.CatalogItem) -> Unit,
    track: (String) -> Modifier = { Modifier },
) {
    val colors = MaterialTheme.colorScheme
    val installed = packages.map { it.file }.toSet()
    val nightlyPicked = remember { mutableStateMapOf<String, Boolean>() }
    SettingsGroup(stringResource(R.string.drawer_component_downloads)) {
        Text(stringResource(R.string.drawer_component_downloads_hint), fontSize = 12.sp,
            color = colors.onSurfaceVariant, modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp))
        SettingsRow(stringResource(R.string.comp_nightlies_checked,
            if (catalogAt > 0) java.text.DateFormat.getTimeInstance(java.text.DateFormat.SHORT)
                .format(java.util.Date(catalogAt * 1000)) else stringResource(R.string.comp_never)), null, stacked = true) {
            ValueChip(stringResource(if (checking) R.string.common_checking else R.string.stores_refresh),
                false, enabled = !checking, modifier = track("cmp-download-refresh"), onClick = onRefresh)
        }
        for (comp in ComponentsManager.COMPONENTS) {
            val key = "cmp-download-$comp"
            val items = catalog.filter { it.comp == comp }
            val open = host.open == key
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 10.dp)) {
                Text(ComponentsManager.LABEL.getValue(comp), fontSize = 15.sp,
                    fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                Box(Modifier.fillMaxWidth().padding(top = 6.dp)) {
                    ValueChip(stringResource(R.string.drawer_component_choose_download), open,
                        enabled = items.isNotEmpty(), modifier = track(key).fillMaxWidth()) {
                        host.open = if (open) null else key
                    }
                    AnchoredMenu(open, onDismiss = { if (host.open == key) host.open = null },
                        title = ComponentsManager.LABEL.getValue(comp)) { firstItemFocus ->
                        // A nightly paired with a stable build rides on that row; Stable/Nightly tabs beside the row pick which one.
                        val pairs = ComponentsManager.nightlyPairs(items)
                        val rows = items.filter { it !in pairs.values }
                        fun shown(row: ComponentsManager.CatalogItem) = pairs[row]?.takeIf { nightlyPicked[row.file] == true } ?: row
                        val firstAvailable = rows.firstOrNull { shown(it).let { s -> ComponentsManager.safeName(s.file) !in installed && s.file !in downloads } }
                        rows.forEach { row ->
                            val item = shown(row)
                            val nightly = pairs[row]
                            val stored = ComponentsManager.safeName(item.file) in installed
                            val progress = downloads[item.file]
                            val choose = @Composable { rowModifier: Modifier ->
                                MenuItem(item.file.removeSuffix(".wcp"), checked = stored,
                                    enabled = !stored && progress == null,
                                    detail = when {
                                        stored -> stringResource(R.string.wincomp_downloaded)
                                        progress != null -> if (progress < 0) stringResource(R.string.wincomp_downloading, item.file.removeSuffix(".wcp")) else "$progress%"
                                        else -> item.release + " · " + stringResource(R.string.common_size_mb, item.size / 1048576.0)
                                    },
                                    focusRequester = if (row == firstAvailable) firstItemFocus else null,
                                    modifier = rowModifier,
                                ) {
                                    onDownload(item)
                                    host.open = null
                                }
                            }
                            if (nightly == null) choose(Modifier)
                            else Row(verticalAlignment = Alignment.CenterVertically) {
                                choose(Modifier.weight(1f))
                                TabStrip(
                                    listOf(stringResource(R.string.comp_channel_stable), stringResource(R.string.comp_channel_nightly)),
                                    selected = if (item === nightly) 1 else 0,
                                    onSelect = { nightlyPicked[row.file] = it == 1 },
                                    bumpers = false, compact = true, modifier = Modifier.padding(start = 6.dp, end = 4.dp),
                                )
                            }
                        }
                    }
                }
            }
        }
        if (catalog.isEmpty() && !checking) Text(
            stringResource(if (catalogAt == 0L) R.string.comp_refresh_to_list else R.string.comp_nothing_new),
            fontSize = 12.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(14.dp),
        )
        for ((file, progress) in downloads) {
            Column(Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 8.dp)) {
                Text(stringResource(R.string.wincomp_downloading, file.removeSuffix(".wcp")),
                    fontSize = 12.sp, color = colors.onSurfaceVariant)
                if (progress < 0) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                else {
                    Text("$progress%", fontSize = 12.sp, color = colors.onSurfaceVariant)
                    LinearProgressIndicator(progress = { progress / 100f },
                        modifier = Modifier.fillMaxWidth().padding(top = 4.dp))
                }
            }
        }
    }
}

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

private const val DRAWER_TAB_SPACING_DP = 48f

private const val DRAWER_TAB_LEAN_DP = 8f

private const val DRAWER_TAB_SIDE_SCALE = 0.8f

private const val DRAWER_TAB_SIDE_ALPHA = 0.8f

private val StepPull = 6.dp
private val StepBoxCorner = 16.dp
private val StepFillet = 10.dp
private val StepBoxMaxWidth = 264.dp

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
                        .focusRequester(cancelFocus).onFocusChanged { cancelFocused = it.isFocused }.testTag("drawer-stop-cancel"),
                    onClick = onCancel,
                )
                ConfirmChoice(
                    stringResource(R.string.drawer_stop), danger = true, enabled = open,
                    modifier = Modifier.graphicsLayer { alpha = items[2].value; translationY = (1f - items[2].value) * 6.dp.toPx() }
                        .onFocusChanged { stopFocused = it.isFocused }
                        .onGloballyPositioned { stopCoords = it }.testTag("drawer-stop-confirm"),
                ) {
                    val at = IntArray(2).also { view.rootView.getLocationOnScreen(it) }
                    onStop(stopCoords?.takeIf { it.isAttached }?.boundsInWindow()?.translate(at[0].toFloat(), at[1].toFloat()))
                }
            }
        }
    }
}

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
            .clickable(role = androidx.compose.ui.semantics.Role.Button, interactionSource = src, indication = LocalIndication.current, enabled = enabled, onClick = onClick)
            .controllerConfirm(enabled = enabled, onClick = onClick)
            .padding(horizontal = 16.dp),
    ) {
        Text(label, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (danger) colors.error else colors.onBackground)
    }
}
