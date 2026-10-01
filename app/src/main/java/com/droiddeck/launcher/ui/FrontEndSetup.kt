package com.droiddeck.launcher.ui

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
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.core.PhantomProcessLimit
import com.droiddeck.launcher.core.PhantomProcessStatus
import com.droiddeck.launcher.session.SessionPrefs
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
                    CheckState.OK -> "Done"
                    CheckState.WARN -> "Needs attention"
                    CheckState.BUSY -> "Working"
                },
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
                processLimitMessage = "Check the Wireless debugging IP address & Port, or pair again if Android removed this device."
                onRequestWirelessAdb(enabled)
            }
        }
    }
    val runtime = when {
        s.busy -> "Working…"
        !s.ready -> "Install"
        s.available != null && s.available != s.installed -> "Update"
        else -> "Manage"
    }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    // Tested hardware passes; an Adreno below it (a 610, say) warns rather than claiming support.
    val gpu = remember { com.droiddeck.launcher.gpu.GpuInfo.detect() }
    val gpuOk = gpu.support == com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED
    val gpuName = remember { DeviceSupport.gpuName() }
    val limitBlocks = PhantomProcessLimit.blocksSteam(s.phantomProcessStatus)
    val signedIn = s.offlineAccount != null
    var showLimitDetails by rememberSaveable { mutableStateOf(false) }
    val checks = 4
    val readyCount = listOf(gpuOk, s.ready && !s.busy, !limitBlocks, signedIn).count { it }
    // Four tabs instead of one long scroll; LB and RB turn them from anywhere on the page. Build
    // and credits are on the Updates page.
    val tabs = listOf("Overview", "Controller", "Session", "Launcher")
    var tab by rememberSaveable { mutableStateOf(0) }
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
            PageHeader("Setup") {
                Chip(if (readyCount == checks) "● All set" else "$readyCount of $checks ready", ok = readyCount == checks)
            }
            TabStrip(tabs, tab, pick, Modifier.padding(bottom = 4.dp), tabFocus)
            Column(modifier = Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) {
                when (tab) {
                    0 -> {
                        SectionTitle("System check", null)
                        // What Steam needs, one row each: green when done, one button when not. The
                        // process-limit controls only open under their row.
                        Column(modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
                            CheckRow(
                                if (gpuOk) CheckState.OK else CheckState.WARN,
                                when (gpu.support) {
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> "Device supported"
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> "Untested GPU"
                                    else -> "GPU not supported"
                                },
                                when (gpu.support) {
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.TESTED -> "${gpu.name} · $gpuName"
                                    com.droiddeck.launcher.gpu.GpuInfo.Support.UNTESTED -> "${gpu.name}: ${gpu.supportText.replaceFirstChar { it.lowercase() }}"
                                    else -> "Steam draws with an Adreno driver; $gpuName may show a black screen"
                                },
                            )
                            CheckRow(
                                when { s.busy -> CheckState.BUSY; !s.ready -> CheckState.WARN; else -> CheckState.OK },
                                "Linux runtime",
                                when {
                                    s.busy -> if (s.percent >= 0) "${s.stage} · ${s.percent}%" else s.stage
                                    !s.ready -> "Not installed · about 3 GB, installed on the first Play"
                                    s.available != null && s.available != s.installed -> "${s.installed ?: "Installed"} · update available"
                                    else -> "${s.installed ?: "Installed"} · up to date"
                                },
                            ) { SecondaryButton(runtime, enabled = !s.busy, compact = true, onClick = a.onRuntime) }
                            CheckRow(
                                if (limitBlocks) CheckState.WARN else CheckState.OK,
                                "Child-process limit",
                                when (s.phantomProcessStatus) {
                                    PhantomProcessStatus.ENABLED -> "On · Android may close Steam. Turning it off takes a minute"
                                    PhantomProcessStatus.UNSET -> "Not set · the ROM default may close Steam. Turning it off takes a minute"
                                    PhantomProcessStatus.UNREADABLE -> "Could not be checked · turning it off takes a minute"
                                    else -> PhantomProcessLimit.title(s.phantomProcessStatus)
                                },
                            ) {
                                if (limitBlocks) PrimaryButton(if (showLimitDetails) "Hide" else "Fix it", compact = true) { showLimitDetails = !showLimitDetails }
                                else if (s.phantomProcessStatus != PhantomProcessStatus.NOT_APPLICABLE) {
                                    SecondaryButton(if (showLimitDetails) "Hide" else "Details", compact = true) { showLimitDetails = !showLimitDetails }
                                }
                            }
                            AnimatedVisibility(showLimitDetails, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                                // One sentence and at most three buttons. The computer route and its
                                // raw command live on the full page Wireless debugging opens.
                                Column(modifier = Modifier.fillMaxWidth().padding(start = 56.dp, end = 14.dp, top = 4.dp, bottom = 10.dp)) {
                                    Text(
                                        if (limitBlocks) PhantomProcessLimit.gateInstructions(s.phantomProcessStatus)
                                        else PhantomProcessLimit.instructions(s.phantomProcessStatus),
                                        fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp),
                                    )
                                    Actions {
                                        if (limitBlocks) {
                                            PrimaryButton("Developer options", compact = true, onClick = onOpenDeveloperOptions)
                                            SecondaryButton("Use Wireless debugging", compact = true, enabled = !processLimitBusy) { setProcessLimit(false) }
                                            SecondaryButton("Check again", compact = true, onClick = a.onRefreshPhantomStatus)
                                        } else if (s.phantomProcessStatus == PhantomProcessStatus.DISABLED) {
                                            SecondaryButton("Turn limit on", compact = true, enabled = !processLimitBusy) { setProcessLimit(true) }
                                        }
                                    }
                                    if (processLimitBusy) LinearProgressIndicator(modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp))
                                    processLimitMessage?.let { Text(it, fontSize = 14.sp, color = colors.onSurfaceVariant, modifier = Modifier.padding(vertical = 4.dp)) }
                                }
                            }
                            CheckRow(
                                if (signedIn) CheckState.OK else CheckState.WARN,
                                "Steam account",
                                s.offlineAccount?.let { if (s.offline) "Signed in as $it · offline mode" else "Signed in as $it" } ?: "Press Play and sign in to Steam",
                                divider = false,
                            )
                        }
                        SectionTitle("Tools", null)
                        ToolGrid(s, a)
                    }
                    1 -> {
                        val controller = s.controller
                        if (controller != null && a.controller != null) SettingsGroup("Controller") {
                            ControllerRows(host, s.oscMode, controller, a.controller)
                        }
                        if (s.controller == null || a.controller == null) Note("Controller settings are unavailable.")
                    }
                    2 -> {
                        SettingsGroup("Session") {
                            ChoiceRow(
                                host, "back-actions", "Back", SessionPrefs.backActionsOrder(s.backActionsInverted),
                                listOf(
                                    false to SessionPrefs.BACK_MENU_THEN_QAM,
                                    true to SessionPrefs.BACK_QAM_THEN_MENU,
                                ), s.backActionsInverted, onPick = a.onBackActionsInverted,
                            )
                            SettingsRow(stringResource(R.string.frame_gen_title), stringResource(R.string.frame_gen_hint)) {
                                Box {
                                    ValueChip(s.frameGenLabel, host.open == "fg") { host.open = if (host.open == "fg") null else "fg" }
                                    FrameGenMenu(s, a, host)
                                }
                            }
                            ToggleRow(host, "logs", "Session logs", "Saved after each session", s.logsEnabled) { a.onLogs() }
                            ActionRow("Latest session logs", "Send them with a bug report", "Share logs", a.onShareLogs)
                            ToggleRow(
                                host, "offline", "Offline mode",
                                s.offlineAccount?.let { "Signed in as $it" } ?: "Sign in to Steam first",
                                s.offline, enabled = s.offlineAccount != null,
                            ) { a.onOffline() }
                        }
                    }
                    3 -> {
                        SettingsGroup("Launcher") {
                            SettingsRow("Theme", "Choose the launcher appearance") {
                                Box {
                                    ValueChip(Themes.byId(s.theme).label, host.open == "theme") { host.open = if (host.open == "theme") null else "theme" }
                                    AnchoredMenu(host.open == "theme", onDismiss = { if (host.open == "theme") host.open = null }, title = "Theme") { firstItemFocus ->
                                        Themes.all.forEachIndexed { index, theme ->
                                            MenuItem(theme.label, checked = s.theme == theme.id, focusRequester = if (index == 0) firstItemFocus else null) {
                                                a.onTheme(theme.id)
                                                host.open = null
                                            }
                                        }
                                    }
                                }
                            }
                            ToggleRow(
                                host, "home-screen", "Use as a Home screen",
                                if (s.homeScreenEnabled) "DroidDeck can be the phone's Home app" else "Off: DroidDeck is never offered as a Home app",
                                s.homeScreenEnabled,
                            ) { a.onHomeScreen(it) }
                            ToggleRow(
                                host, "launcher-fullscreen", "Fullscreen",
                                if (s.launcherFullscreen) "Hide the Android status and navigation bars" else "Show the Android status and navigation bars",
                                s.launcherFullscreen,
                            ) { a.onLauncherFullscreen(it) }
                            if (s.homeScreenEnabled) {
                                ActionRow("Default Home app", s.defaultHomeLabel ?: "Choose a Home app", "Choose", a.onHomeApp)
                            }
                        }
                        SettingsGroup("Linux apps (beta)") {
                            ToggleRow(
                                host, "store-enabled", "Flathub Store",
                                if (s.storeEnabled) "The Store is in the menu. Some apps may not start; logs are in Download/DroidDeck"
                                else "Off: install Linux apps and games from Flathub with Flatpak",
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
        ToolSpec(Icons.Outlined.Folder, "Files", "Browse and manage files", a.onFiles),
        ToolSpec(Icons.Outlined.Extension, "Proton versions", "Install ARM64 Proton builds", a.onProtons),
        ToolSpec(Icons.Outlined.Speed, "Performance", "CPU core assignment", a.onPerformance),
        ToolSpec(Icons.Outlined.VideogameAsset, "ROMs folder", s.romsDir ?: "Choose where emulator games are stored", a.onRoms),
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
internal fun LaunchSettings(s: FrontEndState, a: FrontEndActions, host: MenuHost) {
    // Three across, two on a narrow page; each row's cards share one height.
    val columns = if (LocalNarrowPane.current) 2 else 3
    val controller = a.controller
    val cards = buildList<@Composable (Modifier) -> Unit> {
        add { m -> SettingCard("Components", "FEX, DXVK, VKD3D", "card:components", m) { a.onComponents(true) } }
        add { m ->
            Box(m) {
                SettingCard(stringResource(R.string.frame_gen_title), s.frameGenLabel, "card:fg", Modifier.fillMaxSize()) {
                    host.open = if (host.open == "fg") null else "fg"
                }
                FrameGenMenu(s, a, host)
            }
        }
        if (controller != null) add { m -> SettingCard("Controls", "Button mapping", "card:controls", m, controller.onMapping) }
    }
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
