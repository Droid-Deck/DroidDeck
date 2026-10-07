package com.droiddeck.launcher.ui

import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.FocusRequester
import android.os.Build
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.PriorityHigh
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Speed
import androidx.compose.material.icons.outlined.VideogameAsset
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import kotlinx.coroutines.flow.first
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalInputModeManager
import androidx.compose.ui.input.InputMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.session.SessionPrefs
import androidx.compose.ui.platform.LocalInspectionMode
import com.droiddeck.launcher.R

// The Setup page: runtime and device checks, tools, frame generation and launch settings.

private enum class CheckState { OK, WARN, BUSY }

/** One requirement in Setup's system check: a status mark, what it is, and at most one action. */
@Composable
private fun CheckRow(state: CheckState, title: String, detail: String?, divider: Boolean = true, action: (@Composable () -> Unit)? = null) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val tint = when (state) {
        CheckState.OK -> pal.good
        CheckState.WARN -> AttentionAmber
        CheckState.BUSY -> pal.signal
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp),
        modifier = Modifier.fillMaxWidth()
            .background(if (state == CheckState.WARN) AttentionAmber.copy(alpha = 0.07f) else Color.Transparent)
            .heightIn(min = 60.dp)
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(28.dp).clip(CircleShape).background(tint.copy(alpha = 0.16f))) {
            Icon(
                when (state) {
                    CheckState.OK -> Icons.Filled.Check
                    CheckState.WARN -> Icons.Filled.PriorityHigh
                    CheckState.BUSY -> Icons.Filled.Refresh
                },
                contentDescription = when (state) {
                    CheckState.OK -> stringResource(R.string.setup_check_done)
                    CheckState.WARN -> stringResource(R.string.setup_check_warn)
                    CheckState.BUSY -> stringResource(R.string.setup_check_busy)                },
                tint = tint, modifier = Modifier.size(16.dp),
            )
        }
        Column(modifier = Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
            if (detail != null) Text(detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (action != null) action()
    }
    if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
}

@Composable
internal fun SetupPanel(
    s: FrontEndState,
    a: FrontEndActions,
    onOpenDeveloperOptions: () -> Unit,
    onRequestWirelessAdb: (Boolean) -> Unit,
    initialTab: Int = 0,
) {
    val host = rememberMenuHost()
    val ctx = LocalContext.current
    var processLimitBusy by remember { mutableStateOf(false) }
    var processLimitMessage by remember { mutableStateOf<String?>(null) }
    val setProcessLimit: (Boolean) -> Unit = { enabled ->
        processLimitBusy = true
        processLimitMessage = null
        a.onSetPhantomProcessLimit(enabled) { error ->
            processLimitBusy = false
            if (error == null) {
                a.onRefreshPhantomStatus()
            } else {
                processLimitMessage = ctx.getString(R.string.setup_limit_adb_failed)
                onRequestWirelessAdb(enabled)
            }
        }
    }
    val runtime = when {
        s.busy -> stringResource(R.string.common_working)
        s.removalPending -> stringResource(R.string.setup_retry_removal)
        !s.ready -> stringResource(R.string.setup_install)
        s.available != null && s.available != s.installed -> stringResource(R.string.common_update)
        else -> stringResource(R.string.setup_manage)
    }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    // Tested hardware passes; an Adreno below it (a 610, say) warns rather than claiming support.
    val inspecting = LocalInspectionMode.current
    val gpu = remember { if (inspecting) com.droiddeck.launcher.gpu.GpuInfo("Adreno 740", 740, com.droiddeck.launcher.gpu.GpuInfo.Family.A7XX, "Snapdragon 8 Gen 2", false) else com.droiddeck.launcher.gpu.GpuInfo.detect() }
    val gpuOk = gpu.support == com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED
    val gpuName = remember { if (inspecting) gpu.name else DeviceSupport.gpuName(ctx) }
    val limitBlocks = PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    val signedIn = s.offlineAccount != null
    var showLimitDetails by rememberSaveable { mutableStateOf(false) }
    // Four tabs instead of one long scroll; LB and RB turn them from anywhere on the page. Build
    // and credits are on the Updates page.
    val tabs = listOf(stringResource(R.string.setup_tab_overview), stringResource(R.string.setup_tab_controller), stringResource(R.string.setup_tab_session), stringResource(R.string.setup_tab_launcher))
    var tab by rememberSaveable { mutableStateOf(initialTab.coerceIn(0, tabs.lastIndex)) }
    val tabFocus = remember { List(tabs.size) { FocusRequester() } }
    var tabTurned by remember { mutableStateOf(false) }
    val pick: (Int) -> Unit = { i -> tab = i; tabTurned = true }
    val inputModeManager = LocalInputModeManager.current
    // The control a controller was on went with the old tab: it lands on the new tab itself.
    LaunchedEffect(tab) {
        if (tabTurned && inputModeManager.inputMode == InputMode.Keyboard) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { tabFocus[tab].requestFocus() }
        }
    }
    Rise(0, Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().bumpers(
                onPrevious = { pick((tab + tabs.size - 1) % tabs.size) },
                onNext = { pick((tab + 1) % tabs.size) },
            ),
        ) {
            PageHeader(stringResource(R.string.setup_title)) {
                TabStrip(tabs, tab, pick, Modifier.weight(1f), tabFocus)
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (tab) {
                    0 -> {
                        SectionTitle(stringResource(R.string.setup_system_check), null)
                        // What Steam needs, one row each: green when done, one button when not. The
                        // process-limit controls only open under their row.
                        Column(modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
                            CheckRow(
                                if (gpuOk) CheckState.OK else CheckState.WARN,
                                when (gpu.support) {
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> stringResource(R.string.setup_gpu_supported)
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> stringResource(R.string.setup_gpu_untested)
                                    else -> stringResource(R.string.setup_gpu_unsupported)
                                },
                                when (gpu.support) {
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> stringResource(R.string.setup_gpu_detail, gpu.displayName(ctx), gpuName)
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> stringResource(R.string.setup_gpu_untested_detail, gpu.displayName(ctx), gpu.supportText(ctx).replaceFirstChar { it.lowercase() })
                                    else -> stringResource(R.string.setup_gpu_unsupported_detail, gpuName)
                                },
                            )
                            CheckRow(
                                when { s.busy -> CheckState.BUSY; !s.ready -> CheckState.WARN; else -> CheckState.OK },
                                stringResource(R.string.setup_runtime),
                                when {
                                    s.busy -> null
                                    s.removalPending -> stringResource(R.string.runtime_removal_incomplete)
                                    !s.ready -> stringResource(R.string.setup_runtime_missing)
                                    s.available != null && s.available != s.installed -> stringResource(R.string.setup_runtime_update, s.installed ?: stringResource(R.string.setup_installed))
                                    else -> stringResource(R.string.setup_runtime_current, s.installed ?: stringResource(R.string.setup_installed))
                                },
                                divider = !s.busy,
                            ) { SecondaryButton(runtime, enabled = !s.busy && !s.runtimeActionsBlocked && !s.sessionRunning, compact = true, onClick = a.onRuntime) }
                            if (s.busy) {
                                Column(modifier = Modifier.fillMaxWidth().padding(start = 12.dp, end = 12.dp, bottom = 10.dp)) {
                                    Text(
                                        if (s.percent >= 0) stringResource(R.string.setup_runtime_progress, s.stage, s.percent) else s.stage,
                                        fontSize = 13.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(bottom = 5.dp),
                                    )
                                    if (s.percent >= 0) LinearProgressIndicator(progress = { s.percent / 100f }, modifier = Modifier.fillMaxWidth().height(4.dp))
                                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                                }
                                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                            }
                            CheckRow(
                                if (limitBlocks) CheckState.WARN else CheckState.OK,
                                stringResource(R.string.setup_limit),
                                when (s.phantomProcessStatus) {
                                    PhantomProcessStatus.ENABLED -> stringResource(R.string.setup_limit_on)
                                    PhantomProcessStatus.UNSET -> stringResource(R.string.setup_limit_unset)
                                    PhantomProcessStatus.UNREADABLE -> stringResource(R.string.setup_limit_unknown)
                                    else -> PhantomProcessLimit.title(ctx, s.phantomProcessStatus)
                                },
                            ) {
                                if (limitBlocks) PrimaryButton(if (showLimitDetails) stringResource(R.string.common_hide) else stringResource(R.string.setup_fix_it), compact = true) { showLimitDetails = !showLimitDetails }
                                else if (s.phantomProcessStatus != PhantomProcessStatus.NOT_APPLICABLE) {
                                    SecondaryButton(if (showLimitDetails) stringResource(R.string.common_hide) else stringResource(R.string.setup_details), compact = true) { showLimitDetails = !showLimitDetails }
                                }
                            }
                            AnimatedVisibility(showLimitDetails, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
                                // One sentence and at most three buttons. The computer route and its
                                // raw command live on the full page Wireless debugging opens.
                                Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
                                    Text(
                                        if (limitBlocks) PhantomProcessLimit.gateInstructions(ctx, s.phantomProcessStatus)
                                        else PhantomProcessLimit.instructions(ctx, s.phantomProcessStatus),
                                        fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    Actions {
                                        if (limitBlocks) {
                                            PrimaryButton(stringResource(R.string.setup_dev_options), compact = true, onClick = onOpenDeveloperOptions)
                                            SecondaryButton(stringResource(R.string.setup_use_wireless), compact = true, enabled = !processLimitBusy) { setProcessLimit(false) }
                                            SecondaryButton(stringResource(R.string.setup_check_again), compact = true, onClick = a.onRefreshPhantomStatus)
                                        } else if (s.phantomProcessStatus == PhantomProcessStatus.DISABLED) {
                                            SecondaryButton(stringResource(R.string.setup_limit_turn_on), compact = true, enabled = !processLimitBusy) { setProcessLimit(true) }
                                        }
                                    }
                                    if (processLimitBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                    processLimitMessage?.let { Text(it, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
                                }
                            }
                            CheckRow(
                                if (signedIn) CheckState.OK else CheckState.WARN,
                                stringResource(R.string.setup_account),
                                s.offlineAccount?.let {
                                    when {
                                        it.isEmpty() -> stringResource(if (s.offline) R.string.setup_signed_in_offline_unnamed else R.string.setup_signed_in_unnamed)
                                        s.offline -> stringResource(R.string.setup_signed_in_offline, it)
                                        else -> stringResource(R.string.setup_signed_in, it)
                                    }
                                } ?: stringResource(R.string.setup_sign_in),
                                divider = false,
                            )
                        }
                        SectionTitle(stringResource(R.string.setup_tools), null)
                        ToolGrid(s, a)
                    }
                    1 -> {
                        val controller = s.controller
                        if (controller != null && a.controller != null) SettingsGroup(stringResource(R.string.setup_controller)) {
                            ControllerRows(host, s.oscMode, controller, a.controller)
                        }
                        if (s.controller == null || a.controller == null) Note(stringResource(R.string.setup_controller_unavailable))
                    }
                    2 -> {
                        SettingsGroup(stringResource(R.string.setup_session)) {
                            ChoiceRow(
                                host, "back-actions", stringResource(R.string.mode_back), stringResource(SessionPrefs.backActionsOrder(s.backActionsInverted)),
                                listOf(
                                    false to stringResource(SessionPrefs.BACK_MENU_THEN_QAM),
                                    true to stringResource(SessionPrefs.BACK_QAM_THEN_MENU),
                                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
                            )
                            SettingsRow(stringResource(R.string.frame_gen_title), stringResource(R.string.frame_gen_hint)) {
                                Box {
                                    ValueChip(s.frameGenLabel, host.open == "fg") { host.open = if (host.open == "fg") null else "fg" }
                                    FrameGenMenu(s, a, host)
                                }
                            }
                            ToggleRow(host, "logs", stringResource(R.string.setup_logs), stringResource(R.string.setup_logs_hint), s.logsEnabled) { a.onLogs() }
                            ActionRow(stringResource(R.string.setup_latest_logs), stringResource(R.string.drawer_logs_hint), stringResource(R.string.drawer_share_logs), a.onShareLogs,
                                progress = com.droiddeck.launcher.session.SessionLogShare.progress)
                            ActionRow(stringResource(R.string.setup_saved_logs), stringResource(R.string.setup_saved_logs_hint, com.droiddeck.launcher.session.SessionPaths.KEEP_SESSIONS), stringResource(R.string.setup_clear_logs), a.onClearLogs)
                            ToggleRow(
                                host, "offline", stringResource(R.string.setup_offline),
                                s.offlineAccount?.let { if (it.isEmpty()) stringResource(R.string.setup_signed_in_unnamed) else stringResource(R.string.setup_signed_in, it) } ?: stringResource(R.string.setup_sign_in_first),
                                s.offline, enabled = s.offlineAccount != null,
                            ) { a.onOffline() }
                        }
                    }
                    3 -> {
                        SettingsGroup(stringResource(R.string.setup_launcher)) {
                            LanguageRow(host, s.language, a.onLanguage)
                            ChoiceRow(
                                host, "app-scale", stringResource(R.string.setup_app_scale), stringResource(R.string.setup_app_scale_hint),
                                com.droiddeck.launcher.core.AppUiPrefs.scales.map { percent ->
                                    percent to stringResource(
                                        if (percent == com.droiddeck.launcher.core.AppUiPrefs.DEFAULT_SCALE) R.string.setup_app_scale_default
                                        else R.string.ctrl_percent, percent,
                                    )
                                }, s.appScale, onPick = a.onAppScale,
                            )
                            SettingsRow(stringResource(R.string.setup_theme), stringResource(R.string.setup_theme_hint)) {
                                Box {
                                    ValueChip(stringResource(Themes.byId(s.theme).label), host.open == "theme") { host.open = if (host.open == "theme") null else "theme" }
                                    AnchoredMenu(host.open == "theme", onDismiss = { if (host.open == "theme") host.open = null }, title = stringResource(R.string.setup_theme)) { firstItemFocus ->
                                        Themes.all.forEachIndexed { index, theme ->
                                            MenuItem(stringResource(theme.label), checked = s.theme == theme.id, focusRequester = if (index == 0) firstItemFocus else null) {
                                                a.onTheme(theme.id)
                                                host.open = null
                                            }
                                        }
                                    }
                                }
                            }
                            ToggleRow(
                                host, "home-screen", stringResource(R.string.setup_home),
                                if (s.homeScreenEnabled) stringResource(R.string.setup_home_on) else stringResource(R.string.setup_home_off),
                                s.homeScreenEnabled,
                            ) { a.onHomeScreen(it) }
                            ToggleRow(
                                host, "launcher-fullscreen", stringResource(R.string.setup_fullscreen),
                                if (s.launcherFullscreen) stringResource(R.string.setup_fullscreen_on) else stringResource(R.string.setup_fullscreen_off),
                                s.launcherFullscreen,
                            ) { a.onLauncherFullscreen(it) }
                            ToggleRow(host, "launcher-animations", stringResource(R.string.setup_animations), null,
                                s.animationsEnabled, onChange = a.onAnimationsEnabled)
                            if (s.homeScreenEnabled) {
                                ActionRow(stringResource(R.string.setup_default_home), s.defaultHomeLabel ?: stringResource(R.string.setup_choose_home), stringResource(R.string.setup_choose), a.onHomeApp)
                            }
                        }
                        SettingsGroup(stringResource(R.string.setup_linux_apps)) {
                            ToggleRow(
                                host, "store-enabled", stringResource(R.string.setup_store),
                                if (s.storeEnabled) stringResource(R.string.setup_store_on)
                                else stringResource(R.string.setup_store_off),
                                s.storeEnabled,
                            ) { a.onStoreEnabled(it) }
                        }
                    }
                }
            }
        }
    }
}

/** The launcher's own tools as cards: four across, two by two on a narrow page. */
@Composable
private fun ToolGrid(s: FrontEndState, a: FrontEndActions) {
    val columns = if (LocalNarrowPane.current) 2 else 4
    val tools = listOf(
        ToolSpec(Icons.Outlined.Folder, stringResource(R.string.setup_tool_files), stringResource(R.string.setup_tool_files_hint), a.onFiles),
        ToolSpec(Icons.Outlined.Extension, stringResource(R.string.setup_tool_protons), stringResource(R.string.setup_tool_protons_hint), a.onProtons),
        ToolSpec(Icons.Outlined.Speed, stringResource(R.string.setup_tool_performance), stringResource(R.string.setup_tool_performance_hint), a.onPerformance),
        ToolSpec(Icons.Outlined.VideogameAsset, stringResource(R.string.setup_tool_roms), s.romsDir ?: stringResource(R.string.setup_tool_roms_hint), a.onRoms),
    )
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        for (row in tools.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (t in row) ToolCard(t, Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

private class ToolSpec(val icon: ImageVector, val title: String, val detail: String, val onClick: () -> Unit)

@Composable
private fun ToolCard(t: ToolSpec, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "toolScale")
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier.paneItem("tool:${t.title}")
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, Shape14, pal.signal, pal.line)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = t.onClick)
            .controllerConfirm(onClick = t.onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Icon(t.icon, contentDescription = null, tint = if (hot) pal.signal else colors.onSurfaceVariant, modifier = Modifier.size(20.dp))
        Text(t.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(t.detail, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun FrameGenMenu(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    FrameGenMenu(host, s.frameGen, s.lossless, a.onFrameGenPick, a.onImportLossless)
}

/**
 * What shapes a launch, beside the game rather than three screens away in Setup. They are the
 * app-wide settings - each card opens the same page or menu Setup does.
 */
@Composable
internal fun LaunchSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost, game: com.droiddeck.launcher.frontend.Library.SteamGame? = null) {
    // Three across, two on a narrow page; each row's cards share one height.
    val columns = if (LocalNarrowPane.current) 2 else 3
    val controller = a.controller
    val context = androidx.compose.ui.platform.LocalContext.current
    val inputMode = androidx.compose.ui.platform.LocalInputModeManager.current
    // The game's prefix is compatdata/<id>, as droiddeck-game-env reads it: a Steam title's appid, an
    // added game's shortcut appid (unsigned). Recommended scans the game's own folder either way.
    val wincompKey = game?.let {
        if (it.library == com.droiddeck.launcher.frontend.Library.ADDED) Integer.toUnsignedString(it.appId)
        else it.appId.takeIf { id -> id > 0 }?.toString()
    }
    var wincompOpen by remember(wincompKey) { mutableStateOf<Boolean?>(null) }
    val wincompPicks = remember(wincompKey, wincompOpen) { wincompKey?.let { com.droiddeck.launcher.session.WinComponents.picks(context, it) }.orEmpty() }
    val cards = buildList<@Composable (Modifier) -> Unit> {
        add { m -> SettingCard(stringResource(R.string.setup_card_components), stringResource(R.string.setup_card_components_hint), "card:components", m) { a.onComponents(true) } }
        add { m ->
            Box(m) {
                SettingCard(stringResource(R.string.frame_gen_title), s.frameGenLabel, "card:fg", Modifier.fillMaxSize()) {
                    host.open = if (host.open == "fg") null else "fg"
                }
                FrameGenMenu(s, a, host)
            }
        }
        if (controller != null) add { m -> SettingCard(stringResource(R.string.setup_card_controls), stringResource(R.string.setup_card_controls_hint), "card:controls", m, controller.onMapping) }
        if (wincompKey != null) add { m ->
            SettingCard(
                stringResource(R.string.wincomp_title),
                if (wincompPicks.isEmpty()) stringResource(R.string.wincomp_card_none) else wincompPicks.joinToString(", ") { com.droiddeck.launcher.session.WinComponentNames.of(it) },
                "card:wincomp", m,
            ) { wincompOpen = inputMode.inputMode == androidx.compose.ui.input.InputMode.Keyboard }
        }
    }
    val opened = wincompOpen
    if (opened != null && wincompKey != null && game != null) WinComponentsDialog(
        wincompKey, game.name, game.gameFiles, opened, compat = game.protonPrefix,
        steamAppId = game.appId.takeIf { game.library != com.droiddeck.launcher.frontend.Library.ADDED },
    ) { wincompOpen = null }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp)) {
        for (row in cards.chunked(columns)) {
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
                for (card in row) card(Modifier.weight(1f).fillMaxHeight())
                repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
            }
        }
    }
}

@Composable
private fun SettingCard(label: String, value: String, id: String, modifier: Modifier, onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val src = remember { MutableInteractionSource() }
    val hot = rememberHot(src)
    val pressed by src.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.97f else 1f, Motion.sp(0.5f, Spring.StiffnessMedium), label = "cardScale")
    Column(
        verticalArrangement = Arrangement.spacedBy(3.dp),
        modifier = modifier.paneItem(id)
            .graphicsLayer { scaleX = scale; scaleY = scale }
            .clip(Shape14)
            .background(if (hot) pal.signal.copy(alpha = 0.10f) else colors.surface)
            .glideBorder(hot, Shape14, pal.signal, pal.line2)
            .hoverable(src).clickable(interactionSource = src, indication = LocalIndication.current, role = Role.Button, onClick = onClick)
            .controllerConfirm(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 12.dp),
    ) {
        Text(label, fontSize = 13.sp, color = if (hot) pal.signal else colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        Text(value, fontSize = 16.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/**
 * The app's language: "System default" (what Android uses, named in brackets) or one of the
 * languages the app ships, each in its own script. Steam is set to the same language when it starts.
 */
@Composable
private fun LanguageRow(host: MenuHost, chosen: String, onPick: (String) -> Unit) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val systemName = AppLanguage.displayName(AppLanguage.system(context))
    val systemLabel = stringResource(R.string.setup_language_system, systemName)
    SettingsRow(stringResource(R.string.setup_language), stringResource(R.string.setup_language_hint)) {
        Box {
            ValueChip(if (chosen == AppLanguage.SYSTEM) stringResource(R.string.setup_language_system_short) else AppLanguage.nativeName(chosen),
                host.open == "language") { host.open = if (host.open == "language") null else "language" }
            AnchoredMenu(host.open == "language", onDismiss = { if (host.open == "language") host.open = null }, title = stringResource(R.string.setup_language)) { firstItemFocus ->
                MenuItem(systemLabel, checked = chosen == AppLanguage.SYSTEM, focusRequester = firstItemFocus) {
                    host.open = null
                    onPick(AppLanguage.SYSTEM)
                }
                AppLanguage.supported.forEach { tag ->
                    MenuItem(AppLanguage.nativeName(tag), checked = chosen == tag) {
                        host.open = null
                        onPick(tag)
                    }
                }
            }
        }
    }
}
