package com.droiddeck.launcher.ui

import android.app.Activity
import android.content.ContextWrapper
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.shrinkHorizontally
import androidx.compose.animation.expandHorizontally
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.waitForUpOrCancellation
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Extension
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.MicOff
import androidx.compose.material.icons.outlined.Mic
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.Terminal
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Lock
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Pets
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogWindowProvider
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentAttachments
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentHost.Entry
import com.droiddeck.launcher.agent.AgentSetup
import kotlinx.coroutines.launch

internal enum class AgentPanel { NONE, MODEL, USAGE, HISTORY, PROCESSES, TOOLS }

/** Slash commands the app handles itself; others go to the CLI, which has its own. */
private val LOCAL_COMMANDS = listOf("/new", "/model", "/permissions", "/plan", "/history", "/processes", "/stop")
private val CLI_COMMANDS = listOf("/compact", "/cost", "/review", "/init")

/**
 * The chat with the model, usage and menu on top and the editor at the bottom. Scrolling back
 * through older messages hides the editor behind a small pill and stops following new output.
 * [inputModifier] goes on the first control, where a controller lands without raising the keyboard.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AgentPage(
    modifier: Modifier, inputModifier: Modifier = Modifier, visible: Boolean = true, startTyping: Boolean = true, hidesPet: Boolean = true,
    menuSlot: AgentMenuSlot? = null,
) = AgentPageContent(modifier, inputModifier, visible, hidesPet, menuSlot)

// Lab overlays run against the installed APK, so these entry points keep their JVM signatures.
@Suppress("UNUSED_PARAMETER")
@Composable
internal fun AgentPageWithControls(
    modifier: Modifier, inputModifier: Modifier = Modifier, visible: Boolean = true, startTyping: Boolean = true,
    showControlsWithoutKeyboard: Boolean,
) = AgentPageContent(modifier, inputModifier, visible, hidesPet = true, drawer = true)

/** Whether the session drawer's chat keeps the model, usage and menu in the editor's row, giving the conversation that row's height. */
internal object AgentDrawerLayout {
    private const val KEY = "drawer_inline_controls"
    var inline by mutableStateOf(true)
        private set
    private var loaded = false

    fun load(context: android.content.Context) {
        if (loaded) return
        loaded = true
        inline = AgentHost.prefs(context).getBoolean(KEY, true)
    }

    fun set(context: android.content.Context, on: Boolean) {
        inline = on
        AgentHost.prefs(context).edit().putBoolean(KEY, on).apply()
    }
}

@Composable
private fun AgentPageContent(
    modifier: Modifier, inputModifier: Modifier, visible: Boolean, hidesPet: Boolean, menuSlot: AgentMenuSlot? = null, drawer: Boolean = false,
) {
    val context = LocalContext.current
    val view = LocalView.current
    DisposableEffect(view, visible) {
        val window = (view.parent as? DialogWindowProvider)?.window
            ?: generateSequence(view.context) { (it as? ContextWrapper)?.baseContext }
                .filterIsInstance<Activity>().firstOrNull()?.window
        val previousMode = window?.attributes?.softInputMode
        if (visible) window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        onDispose {
            if (visible && previousMode != null) window?.setSoftInputMode(previousMode)
        }
    }
    remember { AgentHost.refresh(context) }
    remember { AgentDrawerLayout.load(context) }
    val inline = drawer && AgentDrawerLayout.inline
    LaunchedEffect(AgentHost.unseen, visible) { if (visible) AgentHost.seen() }
    // A stopped activity keeps its composition; the page is only on screen while its host is started.
    val lifecycle = androidx.compose.ui.platform.LocalLifecycleOwner.current.lifecycle
    var started by remember(lifecycle) { mutableStateOf(lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) }
    DisposableEffect(lifecycle) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, _ -> started = lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED) }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer) }
    }
    DisposableEffect(visible, hidesPet, started) {
        val counts = visible && hidesPet && started
        if (counts) AgentPet.agentOnScreen++
        onDispose { if (counts) AgentPet.agentOnScreen-- }
    }
    remember { AgentSetup.check(context) }
    var setup by rememberSaveable { mutableStateOf(false) }
    val draft = rememberSaveable { mutableStateOf("") }
    if (setup || AgentHost.state == AgentHost.State.MISSING || !(AgentSetup.ready || AgentSetup.completed(context))) {
        AgentSetupView(modifier, firstControl = inputModifier, onDone = { setup = false })
        return
    }
    var panel by rememberSaveable { mutableStateOf(AgentPanel.NONE) }
    val composer = remember { ComposerState() }
    val focusManager = LocalFocusManager.current
    val togglePanel: (AgentPanel) -> Unit = { panel = if (panel == it) AgentPanel.NONE else it }
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    val menu: @Composable () -> Unit = {
        AgentMenu(menuOpen, { menuOpen = it }, onPanel = { panel = it }, onSetup = { setup = true })
    }
    val topBar: @Composable () -> Unit = { AgentTopBar(panel, togglePanel, inputModifier, menu) }
    val inlineControls: (@Composable () -> Unit)? = if (inline) ({
        ModelChip(panel, togglePanel, inputModifier, compact = true)
        ContextRing("agent-usage", onClick = { togglePanel(AgentPanel.USAGE) })
        menu()
    }) else null
    // A host with a title bar shows the top bar there.
    val inSlot = menuSlot != null && panel != AgentPanel.HISTORY && panel != AgentPanel.PROCESSES && panel != AgentPanel.TOOLS
    if (menuSlot != null) DisposableEffect(menuSlot, inSlot) {
        menuSlot.content = if (inSlot) topBar else null
        onDispose { menuSlot.content = null }
    }
    // The keyboard leaves too little height for a panel and the editor together, so typing closes the panel.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(keyboardOpen) { if (keyboardOpen && (panel == AgentPanel.MODEL || panel == AgentPanel.USAGE)) panel = AgentPanel.NONE }
    // History, processes and tools take the whole page, which would hide an approval the turn is waiting on.
    LaunchedEffect(AgentHost.approvals.size) {
        if (AgentHost.approvals.isNotEmpty() && (panel == AgentPanel.HISTORY || panel == AgentPanel.PROCESSES || panel == AgentPanel.TOOLS)) panel = AgentPanel.NONE
    }
    Column(
        modifier.fillMaxSize().imePadding().onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent
            if (k.keyCode != KeyEvent.KEYCODE_BUTTON_Y || composer.focused) return@onPreviewKeyEvent false
            if (k.action == KeyEvent.ACTION_UP) composer.open()
            true
        },
    ) {
        when (panel) {
            AgentPanel.HISTORY -> {
                AgentPanelBar(stringResource(R.string.agent_history)) { panel = AgentPanel.NONE }
                AgentHistory(Modifier.weight(1f)) { panel = AgentPanel.NONE }
            }
            AgentPanel.PROCESSES -> {
                AgentPanelBar(stringResource(R.string.agent_processes)) { panel = AgentPanel.NONE }
                AgentProcessList(Modifier.weight(1f))
            }
            AgentPanel.TOOLS -> {
                AgentPanelBar(stringResource(R.string.agent_tools)) { panel = AgentPanel.NONE }
                AgentToolsPanel(Modifier.weight(1f))
            }
            else -> {
                if (menuSlot == null && !inline) Box(Modifier.padding(bottom = 6.dp)) { topBar() }
                when (panel) {
                    AgentPanel.MODEL -> Box(Modifier.padding(bottom = 8.dp)) { AgentModelPicker() }
                    AgentPanel.USAGE -> Box(Modifier.padding(bottom = 8.dp)) { AgentUsage() }
                    else -> Unit
                }
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                // Only dragging back through older messages stops following the latest; reaching the bottom again resumes it.
                var readingBack by remember { mutableStateOf(false) }
                LaunchedEffect(AgentHost.chat) { readingBack = false }
                LaunchedEffect(list) { snapshotFlow { list.canScrollForward }.collect { if (!it) readingBack = false } }
                val dragBack = remember {
                    object : NestedScrollConnection {
                        override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                            if (source == NestedScrollSource.Drag && available.y > 1f && list.canScrollBackward) readingBack = true
                            return Offset.Zero
                        }
                    }
                }
                val toLatest: () -> Unit = {
                    readingBack = false
                    scope.launch { list.scrollToItem(maxOf(0, list.layoutInfo.totalItemsCount - 1), Int.MAX_VALUE / 2) }
                }
                val hidden = !composer.focused && !composer.hasUnconsumedFocusRequest() && readingBack && AgentHost.entries.isNotEmpty() &&
                    AgentHost.approvals.isEmpty()
                var listHeight by remember { mutableIntStateOf(0) }
                Box(
                    Modifier.weight(1f).fillMaxWidth().clipToBounds().nestedScroll(dragBack).onGloballyPositioned {
                        // The editor sliding in or the keyboard rising shrinks the list from below; a list following the latest keeps it in view, even mid-drag.
                        // Scrolling remeasures the list, which Compose refuses during a measure pass, so this runs once positioning is done.
                        val height = it.size.height
                        val shrunk = listHeight - height
                        listHeight = height
                        if (shrunk > 0 && !readingBack) list.dispatchRawDelta(shrunk.toFloat())
                    },
                ) {
                    AgentConversation(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (panel == AgentPanel.MODEL || panel == AgentPanel.USAGE) panel = AgentPanel.NONE
                                // Forced: the unforced path walks the focus chain and throws ("ActiveParent with no focused child") when a tap lands while focus is moving.
                                if (composer.focused) focusManager.clearFocus(force = true)
                            }
                        },
                        list, following = !readingBack, onRevise = { composer.open() }, onSent = toLatest,
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        hidden, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    ) {
                        AgentJumpPill(toLatest)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(!hidden, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    AgentComposer(composer, draft, visible && started, inlineControls, onPanel = { panel = it }, onSent = toLatest)
                }
            }
        }
    }
}

/** Whether the editor has the cursor; [open] puts it there. */
@Stable
private class ComposerState {
    var focused by mutableStateOf(false)
    var focusRequests by mutableIntStateOf(0)
        private set
    private var consumedFocusRequests by mutableIntStateOf(0)

    fun open() {
        focusRequests++
        if (focused) consumeFocusRequest()
    }

    fun hasUnconsumedFocusRequest() = focusRequests > consumedFocusRequests

    fun consumeFocusRequest() {
        consumedFocusRequests = focusRequests
    }
}

/** Where a host with its own title bar shows the conversation's menu. */
@Stable
internal class AgentMenuSlot {
    var content by mutableStateOf<(@Composable () -> Unit)?>(null)
}

/** The model and how full the context is, then the [menu]. */
@Composable
private fun AgentTopBar(panel: AgentPanel, onPanel: (AgentPanel) -> Unit, firstControl: Modifier, menu: @Composable () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
            ModelChip(panel, onPanel, firstControl, Modifier.weight(1f, fill = false))
            ContextRing("agent-usage", onClick = { onPanel(AgentPanel.USAGE) })
        }
        menu()
    }
}

/** The harness, model, effort, a permission mode other than asking first and plan mode, opening the model picker; [compact] names only the model. */
@Composable
private fun ModelChip(panel: AgentPanel, onPanel: (AgentPanel) -> Unit, firstControl: Modifier, modifier: Modifier = Modifier, compact: Boolean = false) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val model = AgentHost.models(context).firstOrNull { it.id == AgentHost.model }?.label ?: AgentHost.model ?: stringResource(R.string.agent_model_default)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = modifier.then(if (compact) Modifier.widthIn(max = 120.dp) else Modifier).height(36.dp).clip(Shape12).then(firstControl)
            .clickable { onPanel(AgentPanel.MODEL) }.padding(horizontal = 8.dp).testTag("agent-model"),
    ) {
        val mode = AgentHost.harness.permissions.drop(1).firstOrNull { it.id == AgentHost.permission }?.let { stringResource(it.label) }
        val parts = if (compact) listOfNotNull(mode ?: model)
        else listOfNotNull(
            AgentHost.harness.name, model, AgentHost.effort, mode,
            stringResource(R.string.agent_plan_short).takeIf { AgentHost.plan && AgentHost.harness.plans },
        )
        Text(parts.joinToString(" · "), fontSize = 13.sp, color = if (panel == AgentPanel.MODEL) pal.primary else pal.onSurfaceVariant,
            maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
        Icon(Icons.Outlined.ExpandMore, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** History, processes, a new conversation, the pet and setup, folded behind one button. */
@Composable
private fun AgentMenu(open: Boolean, onOpen: (Boolean) -> Unit, onPanel: (AgentPanel) -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val working = AgentHost.state == AgentHost.State.WORKING
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.clip(Shape16).background(colors.surface.copy(alpha = if (open) 0.97f else 0.88f))
            .border(1.dp, pal.line, Shape16).padding(end = 2.dp),
    ) {
        androidx.compose.animation.AnimatedVisibility(open, enter = expandHorizontally(expandFrom = Alignment.End) + fadeIn(),
            exit = shrinkHorizontally(shrinkTowards = Alignment.End) + fadeOut()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                HeaderAction(Icons.Outlined.History, stringResource(R.string.agent_history), size = 36.dp, tag = "agent-history") { onOpen(false); onPanel(AgentPanel.HISTORY) }
                HeaderAction(Icons.Outlined.Memory, stringResource(R.string.agent_processes), size = 36.dp, tag = "agent-processes") { onOpen(false); onPanel(AgentPanel.PROCESSES) }
                if (AgentHost.harness == com.droiddeck.launcher.agent.ClaudeCode) HeaderAction(
                    Icons.Outlined.Extension, stringResource(R.string.agent_tools), size = 36.dp, tag = "agent-tools",
                ) { onOpen(false); onPanel(AgentPanel.TOOLS) }
                HeaderAction(Icons.Outlined.Add, stringResource(R.string.agent_new), enabled = !working && AgentHost.entries.isNotEmpty(), size = 36.dp, tag = "agent-new") {
                    onOpen(false)
                    AgentHost.forget(context)
                }
                HeaderAction(Icons.Outlined.Pets, stringResource(R.string.setup_agent_pet), selected = AgentPet.enabled, size = 36.dp, tag = "agent-pet-toggle") {
                    AgentPet.set(context, !AgentPet.enabled)
                }
                HeaderAction(Icons.Outlined.Settings, stringResource(R.string.agent_setup_title), enabled = !working, size = 36.dp, tag = "agent-settings") {
                    onOpen(false)
                    onSetup()
                }
            }
        }
        HeaderAction(Icons.Outlined.MoreHoriz, stringResource(R.string.agent_more), selected = open, size = 36.dp, tag = "agent-menu") { onOpen(!open) }
    }
}

@Composable
private fun AgentPanelBar(title: String, onClose: () -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 6.dp)) {
        Text(title, fontSize = 17.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.weight(1f))
        HeaderAction(Icons.Outlined.Close, stringResource(R.string.agent_close), size = 36.dp, tag = "agent-panel-close", onClick = onClose)
    }
}

@Composable
internal fun HeaderAction(
    icon: ImageVector, label: String, enabled: Boolean = true, selected: Boolean = false, tag: String? = null, size: Dp = 44.dp, onClick: () -> Unit,
) {
    val pal = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(start = if (size < 44.dp) 4.dp else 8.dp).size(size).clip(Shape12)
            .background(if (selected) pal.primary.copy(alpha = 0.2f) else pal.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Icon(icon, label, tint = when {
            selected -> pal.primary
            enabled -> pal.onBackground
            else -> pal.onSurfaceVariant.copy(alpha = 0.5f)
        }, modifier = Modifier.size(if (size < 44.dp) 19.dp else 22.dp))
    }
}

@Composable
private fun AgentConversation(modifier: Modifier, list: LazyListState, following: Boolean, onRevise: () -> Unit, onSent: () -> Unit) {
    val pal = LocalPalette.current
    val working = AgentHost.state == AgentHost.State.WORKING
    // A subagent's calls are shown inside the call that started it.
    val shown = AgentHost.entries.indices.filter { (AgentHost.entries[it] as? Entry.Used)?.parent.isNullOrEmpty() }
    val items = shown.size + (if (working) 1 else 0)
    val follow by rememberUpdatedState(following)
    LaunchedEffect(items) {
        if (items == 0 || !follow) return@LaunchedEffect
        list.animateScrollToItem(items - 1)
        list.scrollToItem(items - 1, Int.MAX_VALUE / 2)
    }
    LaunchedEffect(AgentHost.live.length / 80, AgentHost.liveThinking.length / 80) {
        if (follow && items > 0) list.scrollToItem(items - 1, Int.MAX_VALUE / 2)
    }
    if (AgentHost.entries.isEmpty() && !working) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.Terminal, null, tint = pal.primary, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.agent_empty), fontSize = 15.sp, color = pal.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp).padding(top = 12.dp))
        }
        return
    }
    val start = AgentHost.entries.indexOfLast { it is Entry.Asked }
    LazyColumn(modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(shown.size) { n ->
            val i = shown[n]
            val entry = AgentHost.entries[i]
            AgentEntry(
                entry, running = working && i > start && entry is Entry.Used && entry.output == null, last = !working && i == AgentHost.entries.lastIndex,
                children = if (entry is Entry.Used) AgentHost.entries.filter { it is Entry.Used && it.parent == entry.id && entry.id.isNotEmpty() }.map { it as Entry.Used } else emptyList(),
                changes = if (entry is Entry.Finished) turnChanges(AgentHost.entries, i) else null,
                turn = if (entry is Entry.Finished) AgentHost.entries.subList(AgentHost.entries.subList(0, i).indexOfLast { it is Entry.Asked } + 1, i) else emptyList(),
                answerable = !working && i > start && AgentHost.queue.isEmpty(), onRevise = onRevise, onSent = onSent,
            )
        }
        if (working) item { AgentLive() }
    }
}

/** Where the composer was, while it is hidden for reading back: what the agent is doing, and the way to the latest message. */
@Composable
private fun AgentJumpPill(onClick: () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.clip(CircleShape).background(colors.surface).border(1.dp, pal.line, CircleShape)
            .clickable(onClick = onClick).padding(horizontal = 14.dp, vertical = 8.dp).testTag("agent-latest"),
    ) {
        if (AgentHost.state == AgentHost.State.WORKING) CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = pal.primary)
        Text(stringResource(if (AgentHost.state == AgentHost.State.WORKING) R.string.agent_working_short else R.string.agent_latest), fontSize = 13.sp, color = pal.onSurfaceVariant)
        Icon(Icons.Outlined.ArrowDownward, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
    }
}

/** The reply being written, and what the agent is doing right now. */
@Composable
private fun AgentLive() {
    val pal = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (AgentHost.liveThinking.isNotBlank()) AgentThought(AgentHost.liveThinking, live = true)
        if (AgentHost.live.isNotBlank()) AgentMarkdown(AgentHost.live, Modifier.widthIn(max = 760.dp).padding(horizontal = 4.dp))
        val running = AgentHost.running()
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(4.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = pal.primary)
            val elapsed = clock((AgentHost.now - AgentHost.turnStarted) / 1000)
            Text(
                if (running != null) stringResource(R.string.agent_running, running.tool, elapsed) else stringResource(R.string.agent_thinking_for, elapsed),
                fontSize = 13.sp, color = pal.onSurfaceVariant,
            )
        }
    }
}

internal fun clock(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    return when {
        s < 60 -> "${s}s"
        s < 3600 -> "${s / 60}m ${s % 60}s"
        else -> "${s / 3600}h ${s % 3600 / 60}m"
    }
}

/** The oldest tool call that waits for the user; tapping its input shows all of it. */
@Composable
private fun AgentApprovalCard(approval: AgentHost.Approval, more: Int) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var open by remember(approval.id) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, AttentionAmber, Shape14)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("agent-approval"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_approval_title, approval.tool), fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                if (more > 0) Text(stringResource(R.string.agent_approval_more, more), fontSize = 12.5.sp, color = pal.onSurfaceVariant)
            }
            SecondaryButton(stringResource(R.string.agent_approval_deny), compact = true, modifier = Modifier.testTag("agent-approval-deny")) {
                AgentHost.answer(context, approval, allow = false)
            }
            PrimaryButton(stringResource(R.string.agent_approval_allow), compact = true, modifier = Modifier.testTag("agent-approval-allow")) {
                AgentHost.answer(context, approval, allow = true)
            }
        }
        val body = approval.input.ifBlank { approval.detail }
        val shown = Modifier.fillMaxWidth().padding(top = 6.dp)
            .then(if (open) Modifier.heightIn(max = 280.dp).verticalScroll(rememberScrollState()) else Modifier).clickable { open = !open }
        if (body.isNotBlank()) Text(
            body, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = pal.onSurfaceVariant,
            maxLines = if (open) Int.MAX_VALUE else 4, overflow = TextOverflow.Ellipsis, modifier = shown,
        )
    }
}

@Composable
private fun AgentLabCard() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val lab = AgentHost.lab ?: return
    var armed by remember(lab.pid) { mutableStateOf(false) }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape14).background(colors.surface).border(1.dp, if (lab.failed) AttentionAmber else pal.line, Shape14)
            .padding(horizontal = 14.dp, vertical = 10.dp).testTag("agent-lab"),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(
                    stringResource(if (lab.prepare) R.string.agent_lab_preparing else R.string.agent_lab_building) + " · " + labStage(lab),
                    fontSize = 14.sp, fontWeight = FontWeight.SemiBold, color = if (lab.failed) AttentionAmber else colors.onBackground,
                )
                val facts = buildList {
                    if (lab.running && lab.total > 0) add("${lab.done}/${lab.total}")
                    add(clock(lab.elapsedSeconds.toLong()))
                    if (lab.running && lab.etaSeconds >= 0) add(stringResource(R.string.agent_lab_left, clock(lab.etaSeconds.toLong())))
                }
                Text(facts.joinToString(" · "), fontSize = 12.5.sp, color = pal.onSurfaceVariant)
            }
            if (lab.running) SecondaryButton(stringResource(if (armed) R.string.agent_lab_cancel_confirm else R.string.agent_lab_cancel), compact = true) {
                if (armed) AgentHost.cancelLabBuild(context) else armed = true
            }
        }
        if (lab.running) {
            LinearProgressIndicator(
                progress = { lab.permille / 1000f }, color = pal.primary, trackColor = pal.surfaceVariant,
                modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(6.dp).clip(CircleShape),
            )
            if (lab.task.isNotEmpty()) Text(lab.task, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = pal.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
        }
    }
}

@Composable
private fun labStage(lab: com.droiddeck.launcher.agent.LabProgress) = when (lab.stage) {
    "building" -> stringResource(R.string.agent_lab_stage_building, lab.permille / 10)
    "packaging" -> stringResource(R.string.agent_lab_stage_packaging)
    "restarting" -> stringResource(R.string.agent_lab_stage_restarting)
    "ready", "prepared" -> stringResource(R.string.agent_lab_stage_ready)
    "failed" -> stringResource(R.string.agent_lab_stage_failed, lab.task)
    else -> stringResource(R.string.agent_lab_stage_starting)
}

private fun hasActivity() = AgentHost.lab != null || AgentHost.queue.isNotEmpty() || currentTodo() != null ||
    com.droiddeck.launcher.agent.AgentSetup.problems.isNotEmpty()

/** The todo item in progress and how many are done, while the agent works through a list. */
private fun currentTodo(): String? {
    if (AgentHost.state != AgentHost.State.WORKING) return null
    val todos = AgentHost.todos()?.lines()?.filter { it.isNotBlank() }.orEmpty()
    val doing = todos.firstOrNull { it.startsWith("[~]") } ?: todos.firstOrNull { it.startsWith("[ ]") } ?: return null
    return "${todos.count { it.startsWith("[x]") }}/${todos.size} · ${doing.drop(4)}"
}

/** A Lab build, the todo item in progress and queued messages, one chip each. */
@Composable
private fun AgentActivity(labOpen: Boolean, onLab: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    AgentHost.lab?.let { lab ->
        AgentChip(selected = labOpen, warn = lab.failed, tag = "agent-lab-chip", onClick = onLab) {
            Icon(Icons.Outlined.Build, null, tint = if (lab.failed) AttentionAmber else pal.onSurfaceVariant, modifier = Modifier.size(14.dp))
            Text(labStage(lab), fontSize = 12.5.sp, color = if (lab.failed) AttentionAmber else pal.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 160.dp))
        }
    }
    currentTodo()?.let { todo ->
        AgentChip {
            Text(todo, fontSize = 12.5.sp, color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 220.dp))
        }
    }
    val problems = com.droiddeck.launcher.agent.AgentSetup.problems
    if (problems.isNotEmpty()) AgentChip(warn = true, tag = "agent-tools-missing") {
        Text(stringResource(R.string.agent_tools_missing, problems.joinToString(", ")), fontSize = 12.5.sp, color = AttentionAmber,
            maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 260.dp))
    }
    for (queued in AgentHost.queue) AgentChip(warn = true) {
        Text(stringResource(R.string.agent_queued, queued.text), fontSize = 12.5.sp, color = AttentionAmber, maxLines = 1,
            overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 180.dp))
        Text(
            stringResource(R.string.agent_send_now), fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.onPrimary,
            modifier = Modifier.clip(Shape12).background(pal.primary).clickable { AgentHost.sendNow(context, queued) }
                .padding(horizontal = 8.dp, vertical = 3.dp).testTag("agent-send-now"),
        )
        Icon(Icons.Outlined.Close, stringResource(R.string.agent_unqueue), tint = pal.onSurfaceVariant,
            modifier = Modifier.size(22.dp).clip(CircleShape).clickable { AgentHost.unqueue(queued) }.padding(3.dp))
    }
}

@Composable
private fun AgentChip(selected: Boolean = false, warn: Boolean = false, tag: String? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier.height(30.dp).clip(Shape12)
            .background(if (warn) AttentionAmber.copy(alpha = 0.14f) else if (selected) pal.primary.copy(alpha = 0.2f) else pal.surfaceVariant)
            .then(if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier)
            .padding(horizontal = 9.dp),
    ) { content() }
}

/** How full the context is, or failing that the plan limit nearest its end; amber when little is left. */
@Composable
private fun ContextRing(tag: String, onClick: () -> Unit) {
    val pal = LocalPalette.current
    val percent = usagePercent() ?: return
    val warn = percent >= 80
    val tint = if (warn) AttentionAmber else pal.primary
    Row(
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp),
        modifier = Modifier.height(30.dp).clip(Shape12).clickable(onClick = onClick).padding(horizontal = 6.dp).testTag(tag),
    ) {
        val track = pal.line
        Canvas(Modifier.size(16.dp)) {
            val stroke = Stroke(width = 2.5.dp.toPx(), cap = StrokeCap.Round)
            drawArc(track, 0f, 360f, false, style = stroke)
            drawArc(tint, -90f, 360f * percent / 100f, false, style = stroke)
        }
        Text("$percent%", fontSize = 12.5.sp, color = if (warn) AttentionAmber else pal.onSurfaceVariant)
    }
}

/** The context's fill, unless a plan limit is nearly reached: that is the wall the user meets first. */
internal fun usagePercent(): Int? {
    val limit = AgentHost.limits.maxOfOrNull { liveUsed(it) }
    val context = if (AgentHost.window > 0 && AgentHost.context > 0) (AgentHost.context * 100 / AgentHost.window).toInt().coerceIn(0, 100) else null
    return if (limit != null && (limit >= 90 || context == null)) maxOf(limit, context ?: 0) else context
}

/** A limit whose window has passed since it was reported is back to nothing used. */
internal fun liveUsed(limit: AgentHost.Limit) = if (limit.resetsAt > 0 && limit.resetsAt * 1000 < System.currentTimeMillis()) 0 else limit.usedPercent

@Composable
private fun AgentComposer(
    composer: ComposerState, draftState: MutableState<String>, visible: Boolean, controls: (@Composable () -> Unit)?,
    onPanel: (AgentPanel) -> Unit, onSent: () -> Unit,
) {
    val context = LocalContext.current
    var draft by draftState
    var labOpen by rememberSaveable { mutableStateOf(false) }
    if (AgentHost.lab == null) labOpen = false
    val send = send@{
        val text = draft.trim()
        when (text.substringBefore(' ')) {
            "/new" -> AgentHost.forget(context)
            "/model", "/permissions" -> onPanel(AgentPanel.MODEL)
            "/plan" -> AgentHost.choosePlan(context, !AgentHost.plan)
            "/history" -> onPanel(AgentPanel.HISTORY)
            "/processes" -> onPanel(AgentPanel.PROCESSES)
            "/stop" -> AgentHost.stop(context)
            else -> {
                if (text.isEmpty() && AgentAttachments.pending.isEmpty()) return@send
                AgentHost.send(context, text, AgentAttachments.pending.toList())
                AgentAttachments.pending.clear()
                onSent()
            }
        }
        draft = ""
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    Column(Modifier.fillMaxWidth().padding(bottom = if (keyboardOpen) 4.dp else 8.dp)) {
        if (labOpen) AgentLabCard()
        AgentHost.approvals.firstOrNull()?.let { AgentApprovalCard(it, AgentHost.approvals.size - 1) }
        if (hasActivity()) Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) { AgentActivity(labOpen) { labOpen = !labOpen } }
        AgentEditor(composer, draftState, visible, controls, send)
    }
}

/** The text with attach, stop and send beside it, and [controls] while the text is not being typed in. */
@Composable
private fun AgentEditor(composer: ComposerState, draftState: MutableState<String>, visible: Boolean, controls: (@Composable () -> Unit)?, send: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var draft by draftState
    val voice = remember { AgentVoice(context.applicationContext, { draftState.value }) { draftState.value = it } }
    DisposableEffect(voice) { onDispose { voice.settle() } }
    var voiceSetup by remember { mutableStateOf(false) }
    LaunchedEffect(voice.serviceMicBlocked) {
        if (voice.serviceMicBlocked) {
            voice.serviceMicBlocked = false
            voiceSetup = true
        }
    }
    LaunchedEffect(visible) { if (!visible) voice.settle() }
    // Sends once dictation has written its last words; while a send already waits on them, there is nothing more to do.
    val sendNow = {
        when {
            voice.listening -> voice.finish(send)
            voice.finishing -> Unit
            else -> send()
        }
    }
    val working = AgentHost.state == AgentHost.State.WORKING
    if (draft.startsWith("/") && !draft.contains(' ')) {
        val matches = (LOCAL_COMMANDS + CLI_COMMANDS).filter { it.startsWith(draft) && it != draft }
        if (matches.isNotEmpty()) Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            for (command in matches) Text(
                command, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = pal.primary,
                modifier = Modifier.clip(Shape12).background(pal.surfaceVariant).clickable { draft = "$command " }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    if (AgentAttachments.pending.isNotEmpty()) Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        for (path in AgentAttachments.pending.toList()) AttachmentChip(path) { AgentAttachments.pending.remove(path) }
    }
    if (voiceSetup) AgentVoiceSetup(onReady = { voiceSetup = false }, onClose = { voiceSetup = false })
    var focused by remember { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(composer.focusRequests) { if (composer.hasUnconsumedFocusRequest()) runCatching { field.requestFocus() } }
    val attach = LocalActivityResultRegistryOwner.current != null
    BoxWithConstraints(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape16).background(colors.surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) pal.primary else pal.line, Shape16).padding(6.dp),
    ) {
        // A narrow editor gives the buttons a row of their own so the text keeps room to be tapped.
        val stacked = maxWidth < (if (controls != null) 560.dp else 320.dp)
        val text: @Composable (Modifier) -> Unit = { modifier -> Box(modifier.padding(start = 8.dp, top = 10.dp, bottom = 10.dp)) {
                if (draft.isEmpty()) Text(
                    stringResource(
                        when {
                            voice.listening && !voice.locked -> R.string.agent_voice_hold
                            voice.listening -> R.string.agent_voice_listening
                            working -> R.string.agent_hint_working
                            else -> R.string.agent_hint
                        },
                    ),
                    fontSize = 16.sp,
                    color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicTextField(
                    draft, {
                        draft = it
                        if (voice.listening) voice.stop()
                    },
                    Modifier.fillMaxWidth().focusRequester(field).onFocusChanged {
                        focused = it.isFocused
                        composer.focused = it.isFocused
                        if (it.isFocused) composer.consumeFocusRequest()
                    }.testTag("agent-input"),
                    textStyle = TextStyle(fontSize = 16.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(pal.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { sendNow() }),
                    minLines = 1,
                    maxLines = 8,
                )
            } }
        val actions: @Composable () -> Unit = {
            if (working) RoundButton(Icons.Outlined.Stop, stringResource(R.string.agent_stop), pal.error.copy(alpha = 0.22f), colors.error, "agent-stop") {
                AgentHost.stop(context)
            }
            val canSend = draft.isNotBlank() || AgentAttachments.pending.isNotEmpty()
            // An empty message has nothing to send, so the button there dictates one instead.
            if ((!canSend || voice.listening) && !voice.finishing) VoiceButton(voice, onStart = { voice.start(draft) }, onSend = send, onSetup = { voiceSetup = true })
            if (voice.finishing || (voice.listening && voice.locked) || (canSend && !voice.listening)) RoundButton(
                Icons.AutoMirrored.Outlined.Send, stringResource(if (working) R.string.agent_queue else R.string.agent_send),
                pal.primary, pal.onPrimary, "agent-send", enabled = !voice.finishing,
            ) { sendNow() }
        }
        if (stacked) Column {
            text(Modifier.fillMaxWidth())
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
                if (attach) AttachButton()
                if (AgentAttachments.gameFrame != null) GameFrameButton()
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f).height(42.dp)) { if (controls != null && !focused) controls() }
                actions()
            }
        } else Row(verticalAlignment = Alignment.Bottom) {
            if (attach) AttachButton()
            if (AgentAttachments.gameFrame != null) GameFrameButton()
            text(Modifier.weight(1f))
            if (controls != null && !focused) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.height(42.dp)) { controls() }
            actions()
        }
    }
}

/**
 * Held, it listens until let go and then sends; slid up, it keeps listening hands-free; slid left, it puts the text back.
 * A tap or a controller press also listens hands-free.
 */
@Composable
private fun VoiceButton(voice: AgentVoice, onStart: () -> Unit, onSend: () -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val start by rememberUpdatedState(onStart)
    val send by rememberUpdatedState(onSend)
    val setup by rememberUpdatedState(onSetup)
    val handsFree = {
        start()
        if (voice.listening) voice.locked = true
    }
    val toggle = {
        when {
            voice.listening -> voice.finish()
            !voiceReady(context) -> setup()
            else -> handsFree()
        }
    }
    val density = LocalDensity.current
    val lockPx = with(density) { 56.dp.toPx() }
    val cancelPx = with(density) { 72.dp.toPx() }
    var drag by remember { mutableStateOf(Offset.Zero) }
    val holding = voice.listening && !voice.locked
    val icon = when {
        voice.locked -> Icons.Outlined.MicOff
        holding && -drag.x > cancelPx / 2 && -drag.x > -drag.y -> Icons.Outlined.Close
        holding && -drag.y > lockPx / 2 -> Icons.Outlined.Lock
        else -> Icons.Outlined.Mic
    }
    val scale = if (holding) 1.12f + 0.12f * voice.level else 1f
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(start = 6.dp).size(42.dp).graphicsLayer {
            scaleX = scale
            scaleY = scale
        }.clip(CircleShape).background(if (voice.listening) pal.primary else pal.surfaceVariant)
            .pointerInput(voice) {
                awaitEachGesture {
                    val down = awaitFirstDown(pass = PointerEventPass.Initial)
                    down.consume()
                    if (voice.listening || !voiceReady(context)) {
                        waitForUpOrCancellation(PointerEventPass.Initial)?.let {
                            it.consume()
                            toggle()
                        }
                        return@awaitEachGesture
                    }
                    start()
                    var resolved = !voice.listening
                    try {
                        while (!resolved) {
                            val change = awaitPointerEvent(PointerEventPass.Initial).changes.firstOrNull { it.id == down.id } ?: break
                            // Compose ends a cancelled touch stream with a lift that arrives already consumed; that is not a release.
                            if (!change.pressed && change.isConsumed) break
                            change.consume()
                            drag = change.position - down.position
                            when {
                                !voice.listening -> resolved = true
                                drag.y < -lockPx -> {
                                    voice.locked = true
                                    resolved = true
                                }
                                drag.x < -cancelPx -> {
                                    voice.cancel()
                                    resolved = true
                                }
                                !change.pressed -> {
                                    val tap = change.uptimeMillis - down.uptimeMillis < viewConfiguration.longPressTimeoutMillis / 2 &&
                                        drag.getDistance() < viewConfiguration.touchSlop
                                    if (tap) voice.locked = true else voice.finish(send)
                                    resolved = true
                                }
                            }
                        }
                    } finally {
                        drag = Offset.Zero
                        if (!resolved && voice.listening && !voice.locked) voice.finish()
                    }
                }
            }
            .clickable(onClick = toggle).testTag("agent-voice"),
    ) {
        Icon(
            icon, stringResource(if (voice.listening) R.string.agent_voice_stop else R.string.agent_voice),
            tint = if (voice.listening) pal.onPrimary else pal.onBackground, modifier = Modifier.size(21.dp),
        )
    }
}

/** Attaches what the running game shows, taken from its own surface so nothing drawn over it is in the picture. */
@Composable
private fun GameFrameButton() {
    val context = LocalContext.current
    val pal = LocalPalette.current
    RoundButton(Icons.Outlined.PhotoCamera, stringResource(R.string.agent_attach_game), pal.surfaceVariant, pal.onBackground, "agent-game-frame") {
        val grab = AgentAttachments.gameFrame ?: return@RoundButton
        val chat = AgentHost.chat
        grab frame@{ frame ->
            if (frame == null) {
                android.widget.Toast.makeText(context, R.string.agent_attach_game_failed, android.widget.Toast.LENGTH_SHORT).show()
                return@frame
            }
            Thread({
                val path = AgentAttachments.saveFrame(context, chat, frame)
                frame.recycle()
                android.os.Handler(android.os.Looper.getMainLooper()).post {
                    when {
                        path == null -> android.widget.Toast.makeText(context, R.string.agent_attach_game_failed, android.widget.Toast.LENGTH_SHORT).show()
                        AgentHost.chat == chat -> AgentAttachments.pending.add(path)
                        else -> AgentAttachments.discard(context, listOf(path))
                    }
                }
            }, "agent-game-frame").start()
        }
    }
}

@Composable
private fun AttachButton() {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isEmpty()) return@rememberLauncherForActivityResult
        val chat = AgentHost.chat
        Thread({
            val paths = AgentAttachments.copy(context, chat, uris)
            android.os.Handler(android.os.Looper.getMainLooper()).post {
                if (AgentHost.chat == chat) AgentAttachments.pending.addAll(paths) else AgentAttachments.discard(context, paths)
            }
        }, "agent-attach").start()
    }
    RoundButton(Icons.Outlined.AttachFile, stringResource(R.string.agent_attach), pal.surfaceVariant, pal.onBackground, "agent-attach") {
        runCatching { picker.launch(arrayOf("*/*")) }
    }
}

@Composable
private fun RoundButton(
    icon: ImageVector, label: String, background: androidx.compose.ui.graphics.Color, tint: androidx.compose.ui.graphics.Color,
    tag: String, enabled: Boolean = true, onClick: () -> Unit,
) {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(start = 6.dp).size(42.dp).clip(CircleShape).background(background)
            .clickable(enabled = enabled, onClick = onClick).testTag(tag),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(21.dp))
    }
}
