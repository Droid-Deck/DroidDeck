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
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import com.droiddeck.launcher.core.AppLanguage
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.download.StoreDownloadTier
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
    val gpu = remember { com.droiddeck.launcher.gpu.GpuInfo.detect() }
    val gpuOk = gpu.support == com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED
    val gpuName = remember { DeviceSupport.gpuName(ctx) }
    val limitBlocks = PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    val signedIn = s.offlineAccount != null
    var showLimitDetails by rememberSaveable { mutableStateOf(false) }
    var editSgdbKey by remember { mutableStateOf(false) }
    if (editSgdbKey) SgdbKeyDialog(s.sgdbUserKey, onSave = { a.onSgdbKey(it); editSgdbKey = false }, onDismiss = { editSgdbKey = false })
    // Four tabs by scope, a chip row with one gliding selection; LB and RB turn them from
    // anywhere on the page. Build and credits are on the Updates page.
    val tabs = listOf(stringResource(R.string.setup_tab_overview), stringResource(R.string.setup_tab_controller), stringResource(R.string.setup_tab_launcher), stringResource(R.string.setup_tab_diagnostics))
    var tab by rememberSaveable { mutableStateOf(0) }
    val firstChip = remember { FocusRequester() }
    var tabTurned by remember { mutableStateOf(false) }
    val pick: (Int) -> Unit = { i -> tab = i; tabTurned = true }
    val inputModeManager = LocalInputModeManager.current
    // The control a controller was on went with the old tab: it lands on the new tab's chip.
    LaunchedEffect(tab) {
        if (tabTurned && inputModeManager.inputMode == InputMode.Keyboard) {
            androidx.compose.runtime.withFrameNanos { }
            runCatching { firstChip.requestFocus() }
        }
    }
    val wide = !LocalNarrowPane.current
    val runtimeOk = s.ready && !s.busy && !s.removalPending
    val checks = listOf(gpuOk, runtimeOk, !limitBlocks, signedIn)
    val passing = checks.count { it }
    var showChecks by rememberSaveable { mutableStateOf(false) }
    Rise(0, Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().bumpers(
                onPrevious = { pick((tab + tabs.size - 1) % tabs.size) },
                onNext = { pick((tab + 1) % tabs.size) },
            ),
        ) {
            PageHeader(stringResource(R.string.setup_title)) {
                Box(Modifier.weight(1f)) { SheetChips(tabs, tab, firstChip, pick) }
            }
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (tab) {
                    0 -> {
                        val gpuCheck: @Composable (Boolean) -> Unit = { divider ->
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
                                divider = divider,
                            )
                        }
                        val runtimeCheck: @Composable (Boolean) -> Unit = { divider ->
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
                                divider = divider && !s.busy,
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
                                if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                            }
                        }
                        val limitCheck: @Composable (Boolean) -> Unit = { divider ->
                            CheckRow(
                                if (limitBlocks) CheckState.WARN else CheckState.OK,
                                stringResource(R.string.setup_limit),
                                when (s.phantomProcessStatus) {
                                    PhantomProcessStatus.ENABLED -> stringResource(R.string.setup_limit_on)
                                    PhantomProcessStatus.UNSET -> stringResource(R.string.setup_limit_unset)
                                    PhantomProcessStatus.UNREADABLE -> stringResource(R.string.setup_limit_unknown)
                                    else -> PhantomProcessLimit.title(ctx, s.phantomProcessStatus)
                                },
                                divider = divider && !showLimitDetails,
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
                            if (divider && showLimitDetails) Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                        }
                        val accountCheck: @Composable (Boolean) -> Unit = { divider ->
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
                                divider = divider,
                            )
                        }
                        val all = listOf(gpuCheck, runtimeCheck, limitCheck, accountCheck)
                        // Everything passes: one line says so, and Details opens the checks in place.
                        ReadinessCard(
                            passing, checks.size,
                            listOfNotNull(
                                gpu.displayName(ctx),
                                s.installed?.let { stringResource(R.string.setup_ready_runtime, it) },
                                stringResource(R.string.setup_ready_limit_off).takeIf { !limitBlocks },
                                s.offlineAccount?.takeIf { it.isNotEmpty() }?.let { stringResource(R.string.setup_ready_signed_in, it) },
                            ).joinToString(" · "),
                            open = showChecks, onDetails = { showChecks = !showChecks },
                        ) {
                            val shownChecks = all.filterIndexed { i, _ -> checks[i] }
                            shownChecks.forEachIndexed { i, check -> check(i < shownChecks.lastIndex) }
                        }
                        // Each check that warns is its own card, its Fix it as it always was.
                        if (passing < checks.size) {
                            SectionTitle(stringResource(R.string.setup_needs_you), null)
                            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                                all.forEachIndexed { i, check ->
                                    if (!checks[i]) Column(Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, AttentionAmber.copy(alpha = 0.5f), Shape14)) { check(false) }
                                }
                            }
                        }
                        SettingsGroup(stringResource(R.string.setup_account)) {
                            accountCheck(true)
                            ToggleRow(
                                host, "offline", stringResource(R.string.setup_offline),
                                s.offlineAccount?.let { if (it.isEmpty()) stringResource(R.string.setup_signed_in_unnamed) else stringResource(R.string.setup_signed_in, it) } ?: stringResource(R.string.setup_sign_in_first),
                                s.offline, enabled = s.offlineAccount != null,
                            ) { a.onOffline() }
                        }
                        SettingsGroup(stringResource(R.string.setup_runtime)) { runtimeCheck(false) }
                    }
                    1 -> {
                        val controller = s.controller
                        if (controller != null && a.controller != null) SettingsGroup(stringResource(R.string.setup_controller)) {
                            ControllerRows(host, s.oscMode, controller, a.controller)
                        }
                        if (s.controller == null || a.controller == null) Note(stringResource(R.string.setup_controller_unavailable))
                    }
                    2 -> {
                        val look: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_look)) {
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
                                ChoiceRow(
                                    host, "app-scale", stringResource(R.string.setup_app_scale), stringResource(R.string.setup_app_scale_hint),
                                    com.droiddeck.launcher.core.AppUiPrefs.scales.map { percent ->
                                        percent to stringResource(
                                            if (percent == com.droiddeck.launcher.core.AppUiPrefs.DEFAULT_SCALE) R.string.setup_app_scale_default
                                            else R.string.ctrl_percent, percent,
                                        )
                                    }, s.appScale, onPick = a.onAppScale,
                                )
                                ToggleRow(host, "launcher-animations", stringResource(R.string.setup_animations), null,
                                    s.animationsEnabled, onChange = a.onAnimationsEnabled)
                                LanguageRow(host, s.language, a.onLanguage)
                            }
                        }
                        val device: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_device)) {
                                ToggleRow(
                                    host, "home-screen", stringResource(R.string.setup_home),
                                    if (s.homeScreenEnabled) stringResource(R.string.setup_home_on) else stringResource(R.string.setup_home_off),
                                    s.homeScreenEnabled,
                                ) { a.onHomeScreen(it) }
                                if (s.homeScreenEnabled) {
                                    ActionRow(stringResource(R.string.setup_default_home), s.defaultHomeLabel ?: stringResource(R.string.setup_choose_home), stringResource(R.string.setup_choose), a.onHomeApp)
                                }
                                ChoiceRow(
                                    host, "orientation", stringResource(R.string.setup_orientation), null,
                                    SessionPrefs.orientationOptions.map { (value, label) -> value to stringResource(label) },
                                    s.orientation, onPick = a.onOrientation,
                                )
                                ToggleRow(
                                    host, "launcher-fullscreen", stringResource(R.string.setup_fullscreen),
                                    if (s.launcherFullscreen) stringResource(R.string.setup_fullscreen_on) else stringResource(R.string.setup_fullscreen_off),
                                    s.launcherFullscreen,
                                ) { a.onLauncherFullscreen(it) }
                            }
                        }
                        val rail: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_rail)) {
                                RailToggle(
                                    "store", host, "store-enabled", stringResource(R.string.setup_store),
                                    if (s.storeEnabled) stringResource(R.string.setup_store_on) else stringResource(R.string.setup_store_off),
                                    s.storeEnabled, a.onStoreEnabled,
                                )
                                RailToggle("stores", host, "stores-enabled", stringResource(R.string.setup_stores_show), null, s.gameStoresEnabled, a.onGameStoresEnabled)
                            }
                        }
                        val art: @Composable () -> Unit = {
                            SettingsGroup(stringResource(R.string.setup_group_art)) {
                                ToggleRow(host, "added-art", stringResource(R.string.mode_added_art), stringResource(R.string.mode_added_art_hint), s.addedGamesArt) { a.onAddedGamesArt(it) }
                                ActionRow(
                                    stringResource(R.string.mode_sgdb_key), if (s.sgdbUserKey) stringResource(R.string.mode_sgdb_key_yours) else null,
                                    stringResource(R.string.mode_sgdb_edit), onClick = { editSgdbKey = true },
                                )
                            }
                        }
                        // Two columns on a wide page: how it looks and this device; then the rail and the art.
                        if (wide) Row(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) { look(); rail() }
                            Column(Modifier.weight(1f)) { device(); art() }
                        } else { look(); device(); rail(); art() }
                    }
                    3 -> {
                        SettingsGroup(stringResource(R.string.setup_tab_diagnostics)) {
                            ToggleRow(host, "logs", stringResource(R.string.setup_logs), stringResource(R.string.setup_logs_hint), s.logsEnabled) { a.onLogs() }
                            ToggleRow(host, "agent-commands", stringResource(R.string.setup_agent_commands), null, s.agentCommands) { a.onAgentCommands() }
                            ActionRow(stringResource(R.string.setup_latest_logs), stringResource(R.string.drawer_logs_hint), stringResource(R.string.drawer_share_logs), a.onShareLogs,
                                progress = com.droiddeck.launcher.session.SessionLogShare.progress)
                            ActionRow(stringResource(R.string.setup_saved_logs), stringResource(R.string.setup_saved_logs_hint, com.droiddeck.launcher.session.SessionPaths.KEEP_SESSIONS), stringResource(R.string.setup_clear_logs), a.onClearLogs)
                        }
                    }
                }
            }
        }
    }
}

/**
 * The checks as one card while they all pass: a ring with how many pass, "Ready to play" and one
 * line of what that means, and a Details chip that opens the checks themselves in place ([checks]).
 */
@Composable
private fun ReadinessCard(passing: Int, total: Int, line: String, open: Boolean, onDetails: () -> Unit, checks: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val ready = passing == total
    Column(Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 14.dp, vertical = 12.dp),
        ) {
            val tint = if (ready) pal.good else AttentionAmber
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(52.dp)) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { passing / total.toFloat().coerceAtLeast(1f) }, strokeWidth = 4.dp,
                    color = tint, trackColor = tint.copy(alpha = 0.18f), modifier = Modifier.size(52.dp),
                )
                Text("$passing/$total", fontSize = 13.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            }
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (ready) R.string.setup_ready else R.string.setup_not_ready),
                    fontSize = 17.sp, fontWeight = FontWeight.Bold, color = colors.onBackground,
                )
                if (line.isNotEmpty()) Text(line, fontSize = 13.sp, color = colors.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            if (passing > 0) SecondaryButton(stringResource(if (open) R.string.common_hide else R.string.setup_details), compact = true, onClick = onDetails)
        }
        AnimatedVisibility(open, enter = expandVertically(Motion.sp(1f)) + fadeIn(Motion.sp(1f)), exit = shrinkVertically(Motion.sp(1f)) + fadeOut(Motion.sp(1f))) {
            Column(Modifier.fillMaxWidth()) {
                Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
                checks()
            }
        }
    }
}

/**
 * A rail section's switch. On, its rail item comes in with the rail's own entrance; off, a dot
 * flies from this row to the item as it folds away.
 */
@Composable
private fun RailToggle(rail: String, host: MenuHost, key: String, label: String, hint: String?, checked: Boolean, onChange: (Boolean) -> Unit) {
    var at by remember { mutableStateOf<androidx.compose.ui.geometry.Rect?>(null) }
    Box(Modifier.onGloballyPositioned { at = it.boundsInRoot() }) {
        ToggleRow(host, key, label, hint, checked) { on ->
            val from = at
            val to = Hops.railBounds[rail]
            if (!on && from != null && to != null) Flights.fly(Flight(from, to = { to.center }))
            onChange(on)
        }
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
        add { m ->
            val back = stringResource(R.string.hop_from_games)
            // Components is its own section: the card hops there, and B on it comes back here.
            SettingCard(stringResource(R.string.setup_card_components), stringResource(R.string.setup_card_components_hint), "card:components", m.hopSource("card:components")) {
                val at = Hops.sources["card:components"]
                if (at == null) a.onComponents(true)
                else Hops.go(HopOrigin("games", HOP_SURFACE_GAMES, null, "card:components", at, back, dest = "components", page = "components")) { a.onComponents(true) }
            }
        }
        add { m ->
            Box(m) {
                SettingCard(stringResource(R.string.frame_gen_title), s.frameGenLabel, "card:fg", Modifier.fillMaxSize()) {
                    host.open = if (host.open == "fg") null else "fg"
                }
                FrameGenMenu(s, a, host)
            }
        }
        if (controller != null) add { m -> SettingCard(stringResource(R.string.setup_card_controls), stringResource(R.string.setup_card_controls_hint), "card:controls", m, controller.onMapping) }
        // An Epic game's own launch choices, kept in its sidecar so a launch from the Steam client
        // honours them too (droiddeck-store-launch reads the same file).
        // Epic's launch choices; cloud saves live in the game's Cloud saves view, so a GOG game has no card.
        val cardStore = com.droiddeck.launcher.stores.Store.byId(game?.source.orEmpty())?.takeIf { it == com.droiddeck.launcher.stores.Store.EPIC }
        if (cardStore != null && game?.gameFiles != null) add { m ->
            Box(m) {
                StoreLaunchCard(host, game.gameFiles!!, cardStore)
            }
        }
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

/** Epic's account page behind its sign-in: signing in there presents any step the account still owes. */
private const val EPIC_RESOLVE_URL = "https://www.epicgames.com/id/login?redirectUrl=https%3A%2F%2Fwww.epicgames.com%2Faccount%2Fpersonal"

/** The Epic card: the sign-in and offline switches, read from and written to the game's sidecar, and Resolve Epic sign-in. */
@Composable
private fun StoreLaunchCard(host: MenuHost, folder: java.io.File, store: com.droiddeck.launcher.stores.Store) {
    val appContext = androidx.compose.ui.platform.LocalContext.current.applicationContext
    var sidecar by remember(folder) { mutableStateOf(com.droiddeck.launcher.stores.StoreGameSidecar.read(folder)) }
    val options = sidecar?.epic ?: com.droiddeck.launcher.stores.EpicOptions()
    val epic = store == com.droiddeck.launcher.stores.Store.EPIC
    val key = "store-" + store.id
    val value = buildList {
        if (epic && options.offline) add(stringResource(R.string.epic_card_offline))
        else if (epic && options.eos) add(stringResource(R.string.epic_card_eos))
    }.ifEmpty { listOf(stringResource(R.string.epic_card_none)) }.joinToString(" · ")
    // Each switch goes straight to the sidecar on disk - both launch paths read it there - and the
    // card shows what was read back, so a write that did not take is never shown as done.
    fun set(change: (com.droiddeck.launcher.stores.EpicOptions) -> com.droiddeck.launcher.stores.EpicOptions) {
        Thread({
            val written = runCatching { com.droiddeck.launcher.stores.StoreGameSidecar.updateEpic(folder, change) }
                .onFailure { android.util.Log.w("EpicLaunchCard", "could not write ${folder.name}: ${it.message}") }.getOrNull()
            if (written != null) android.util.Log.i("EpicLaunchCard", "epic options ${written.id} eos=${written.epic.eos} offline=${written.epic.offline}")
            else android.util.Log.w("EpicLaunchCard", "epic options not saved for ${folder.name}")
            com.droiddeck.launcher.stores.StoresState.post { sidecar = written ?: com.droiddeck.launcher.stores.StoreGameSidecar.read(folder) }
        }, "epic-options").start()
    }
    SettingCard(store.shortLabel, value, "card:$key", Modifier.fillMaxSize()) {
        host.open = if (host.open == key) null else key
    }
    AnchoredMenu(host.open == key, onDismiss = { if (host.open == key) host.open = null }, title = store.shortLabel) { first ->
        if (epic) {
            MenuItem(stringResource(R.string.epic_eos), checked = options.eos, focusRequester = first) { set { it.copy(eos = !it.eos) } }
            MenuItem(stringResource(R.string.epic_offline), checked = options.offline) { set { it.copy(offline = !it.offline) } }
        }
        if (epic) {
        // Epic asks some accounts to accept something once (privacy policy, EULA) before a game may
        // sign in - EOS's "corrective action". Signing in on Epic's site shows it.
        MenuItem(stringResource(R.string.epic_resolve), checked = false) {
            host.open = null
            runCatching {
                appContext.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW, android.net.Uri.parse(EPIC_RESOLVE_URL)).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }
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
