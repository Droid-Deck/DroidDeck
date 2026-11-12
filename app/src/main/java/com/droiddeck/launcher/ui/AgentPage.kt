package com.droiddeck.launcher.ui

import android.view.KeyEvent
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.ArrowDownward
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Build
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
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
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentAttachments
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentHost.Entry
import com.droiddeck.launcher.agent.AgentSetup
import kotlinx.coroutines.launch

internal enum class AgentPanel { NONE, MODEL, USAGE, HISTORY, PROCESSES }

/** Slash commands the app handles itself; others go to the CLI, which has its own. */
private val LOCAL_COMMANDS = listOf("/new", "/model", "/plan", "/history", "/processes", "/stop")
private val CLI_COMMANDS = listOf("/compact", "/cost", "/review", "/init")
private const val COMPOSER_SLIDE_MS = 350L

/**
 * The chat with the composer at the bottom. The composer is the full editor only while it is
 * being typed in; reading shrinks it to one line, and scrolling back through older messages
 * hides it behind a small pill. [startTyping] opens an empty conversation ready to type. A window that pans
 * for the keyboard instead of resizing passes false for [resizesForKeyboard], or the editor moves up twice.
 */
@Composable
internal fun AgentPage(
    modifier: Modifier, inputModifier: Modifier = Modifier, visible: Boolean = true, startTyping: Boolean = true, resizesForKeyboard: Boolean = true,
) {
    val context = LocalContext.current
    remember { AgentHost.refresh(context) }
    LaunchedEffect(AgentHost.unseen, visible) { if (visible) AgentHost.seen() }
    remember { AgentSetup.check(context) }
    var setup by rememberSaveable { mutableStateOf(false) }
    val draft = rememberSaveable { mutableStateOf("") }
    if (setup || AgentHost.state == AgentHost.State.MISSING || !(AgentSetup.ready || AgentSetup.completed(context))) {
        AgentSetupView(modifier, onDone = { setup = false })
        return
    }
    var panel by rememberSaveable { mutableStateOf(AgentPanel.NONE) }
    val composer = remember { ComposerState(startTyping && AgentHost.entries.isEmpty()) }
    val focusManager = LocalFocusManager.current
    val togglePanel: (AgentPanel) -> Unit = { panel = if (panel == it) AgentPanel.NONE else it }
    // The keyboard leaves too little height for a panel and the editor together, so typing closes the panel.
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    LaunchedEffect(keyboardOpen) { if (keyboardOpen && (panel == AgentPanel.MODEL || panel == AgentPanel.USAGE)) panel = AgentPanel.NONE }
    Column(
        modifier.fillMaxSize().then(if (resizesForKeyboard) Modifier.imePadding() else Modifier).onPreviewKeyEvent { e ->
            val k = e.nativeKeyEvent
            if (k.keyCode != KeyEvent.KEYCODE_BUTTON_Y || composer.typing) return@onPreviewKeyEvent false
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
            else -> {
                val list = rememberLazyListState()
                val scope = rememberCoroutineScope()
                // Only dragging back through older messages hides the composer; showing it again shrinks the list, which must not hide it once more.
                var readingBack by remember { mutableStateOf(false) }
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
                val hidden = !composer.typing && readingBack && AgentHost.entries.isNotEmpty()
                var wasHidden by remember { mutableStateOf(false) }
                LaunchedEffect(hidden) {
                    if (hidden) wasHidden = true
                    else if (wasHidden) {
                        wasHidden = false
                        // The composer takes its height back as it slides in; the latest message stays above it.
                        kotlinx.coroutines.delay(COMPOSER_SLIDE_MS)
                        list.scrollToItem(maxOf(0, list.layoutInfo.totalItemsCount - 1), Int.MAX_VALUE / 2)
                    }
                }
                Box(Modifier.weight(1f).fillMaxWidth().nestedScroll(dragBack)) {
                    AgentConversation(
                        Modifier.fillMaxSize().pointerInput(Unit) {
                            awaitEachGesture {
                                awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
                                if (panel == AgentPanel.MODEL || panel == AgentPanel.USAGE) panel = AgentPanel.NONE
                                if (composer.typing) focusManager.clearFocus()
                            }
                        },
                        list, onRevise = { composer.open() },
                    )
                    androidx.compose.animation.AnimatedVisibility(
                        hidden, enter = fadeIn(), exit = fadeOut(), modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp),
                    ) {
                        AgentJumpPill(toLatest)
                    }
                }
                androidx.compose.animation.AnimatedVisibility(!hidden, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
                    AgentComposer(composer, draft, inputModifier, panel, togglePanel) { setup = true }
                }
            }
        }
    }
}

/** Whether the composer is the full editor; [open] also puts the cursor in it. */
@Stable
private class ComposerState(typing: Boolean) {
    var typing by mutableStateOf(typing)
    var focusRequests by mutableIntStateOf(0)
        private set
    /** Opened and waiting for the text field to take focus; focus passes through nothing on the way. */
    var opening = false

    fun open() {
        typing = true
        opening = true
        focusRequests++
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
private fun AgentConversation(modifier: Modifier, list: LazyListState, onRevise: () -> Unit) {
    val pal = LocalPalette.current
    val working = AgentHost.state == AgentHost.State.WORKING
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    val items = AgentHost.entries.size + (if (working) 1 else 0)
    LaunchedEffect(items) {
        if (items == 0) return@LaunchedEffect
        list.animateScrollToItem(items - 1)
        list.scrollToItem(items - 1, Int.MAX_VALUE / 2)
    }
    LaunchedEffect(AgentHost.live.length / 80, AgentHost.liveThinking.length / 80) {
        if (atBottom && items > 0) list.scrollToItem(items - 1, Int.MAX_VALUE / 2)
    }
    if (AgentHost.entries.isEmpty() && !working) {
        Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Outlined.AutoAwesome, null, tint = pal.primary, modifier = Modifier.size(40.dp))
            Text(stringResource(R.string.agent_empty), fontSize = 15.sp, color = pal.onSurfaceVariant,
                textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp).padding(top = 12.dp))
        }
        return
    }
    val start = AgentHost.entries.indexOfLast { it is Entry.Asked }
    LazyColumn(modifier.fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
        items(AgentHost.entries.size) { i ->
            val entry = AgentHost.entries[i]
            AgentEntry(
                entry, running = working && i > start && entry is Entry.Used && entry.output == null, last = !working && i == AgentHost.entries.lastIndex,
                changes = if (entry is Entry.Finished) turnChanges(AgentHost.entries, i) else null, onRevise = onRevise,
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

private fun hasActivity() = AgentHost.lab != null || AgentHost.queue.isNotEmpty() || currentTodo() != null

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
    composer: ComposerState, draftState: MutableState<String>, inputModifier: Modifier, panel: AgentPanel, onPanel: (AgentPanel) -> Unit, onSetup: () -> Unit,
) {
    val context = LocalContext.current
    var draft by draftState
    var labOpen by rememberSaveable { mutableStateOf(false) }
    if (AgentHost.lab == null) labOpen = false
    var hadFocus by remember { mutableStateOf(false) }
    val send = send@{
        val text = draft.trim()
        when (text.substringBefore(' ')) {
            "/new" -> AgentHost.forget(context)
            "/model" -> onPanel(AgentPanel.MODEL)
            "/plan" -> AgentHost.choosePlan(context, !AgentHost.plan)
            "/history" -> onPanel(AgentPanel.HISTORY)
            "/processes" -> onPanel(AgentPanel.PROCESSES)
            "/stop" -> AgentHost.stop(context)
            else -> {
                if (text.isEmpty() && AgentAttachments.pending.isEmpty()) return@send
                AgentHost.send(context, text, AgentAttachments.pending.toList())
                AgentAttachments.pending.clear()
            }
        }
        draft = ""
    }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    // Leaving the composer for the conversation, by touch or D-pad, is reading.
    Column(
        Modifier.fillMaxWidth().padding(bottom = if (keyboardOpen) 4.dp else 8.dp).onFocusChanged {
            if (it.hasFocus) hadFocus = true
            else if (hadFocus) {
                hadFocus = false
                if (composer.typing && !composer.opening) composer.typing = false
            }
        },
    ) {
        if (labOpen) AgentLabCard()
        when (panel) {
            AgentPanel.MODEL -> Box(Modifier.padding(top = 8.dp)) { AgentModelPicker() }
            AgentPanel.USAGE -> Box(Modifier.padding(top = 8.dp)) { AgentUsage() }
            else -> Unit
        }
        if (composer.typing) {
            if (hasActivity()) Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.padding(top = 8.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
            ) { AgentActivity(labOpen) { labOpen = !labOpen } }
            AgentEditor(composer, draftState, inputModifier, panel, onPanel, onSetup, send)
        } else AgentReadingBar(composer, draft, inputModifier, labOpen, onLab = { labOpen = !labOpen }) { onPanel(AgentPanel.USAGE) }
    }
}

/** The composer while reading: one line holding what is going on, opened by a press or by Y. */
@Composable
private fun AgentReadingBar(
    composer: ComposerState, draft: String, inputModifier: Modifier, labOpen: Boolean, onLab: () -> Unit, onUsage: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val working = AgentHost.state == AgentHost.State.WORKING
    var focused by remember { mutableStateOf(false) }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp).height(44.dp).clip(Shape16).background(colors.surface)
            .border(if (focused) 2.dp else 1.dp, if (focused) pal.primary else pal.line, Shape16)
            .then(inputModifier).onFocusChanged { focused = it.isFocused }.clickable { composer.open() }.testTag("agent-composer-bar")
            .padding(start = 14.dp, end = 4.dp),
    ) {
        Text(
            draft.ifBlank { stringResource(if (working) R.string.agent_hint_working else R.string.agent_message) }, fontSize = 15.sp,
            color = if (draft.isBlank()) pal.onSurfaceVariant else colors.onSurface, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f, fill = !hasActivity()).widthIn(min = 72.dp),
        )
        if (hasActivity()) Row(
            horizontalArrangement = Arrangement.spacedBy(6.dp), verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.weight(1f).padding(start = 8.dp).horizontalScroll(rememberScrollState()),
        ) { AgentActivity(labOpen, onLab) }
        ContextRing("agent-usage-bar", onUsage)
        if (working) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.padding(start = 4.dp).size(36.dp).clip(CircleShape).background(pal.error.copy(alpha = 0.22f))
                .clickable { AgentHost.stop(context) }.testTag("agent-stop"),
        ) { Icon(Icons.Outlined.Stop, stringResource(R.string.agent_stop), tint = colors.error, modifier = Modifier.size(20.dp)) }
    }
}

/** The full editor: model and usage on top, the text with attach, stop and send under them. */
@Composable
private fun AgentEditor(
    composer: ComposerState, draftState: MutableState<String>, inputModifier: Modifier, panel: AgentPanel, onPanel: (AgentPanel) -> Unit,
    onSetup: () -> Unit, send: () -> Unit,
) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var draft by draftState
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
    var focused by remember { mutableStateOf(false) }
    var menu by rememberSaveable { mutableStateOf(false) }
    val field = remember { FocusRequester() }
    LaunchedEffect(composer.focusRequests) { if (composer.focusRequests > 0) runCatching { field.requestFocus() } }
    Column(
        Modifier.fillMaxWidth().padding(top = 8.dp).clip(Shape16).background(colors.surface)
            .border(1.dp, if (focused) pal.primary else pal.line, Shape16).padding(6.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().height(36.dp)) {
            val model = AgentHost.models(context).firstOrNull { it.id == AgentHost.model }?.label ?: AgentHost.model ?: stringResource(R.string.agent_model_default)
            Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f, fill = false).height(30.dp).clip(Shape12).clickable { onPanel(AgentPanel.MODEL) }
                        .padding(horizontal = 8.dp).testTag("agent-model"),
                ) {
                    val parts = listOfNotNull(AgentHost.harness.name, model, AgentHost.effort, stringResource(R.string.agent_plan_short).takeIf { AgentHost.plan && AgentHost.harness.plans })
                    Text(parts.joinToString(" · "), fontSize = 13.sp, color = if (panel == AgentPanel.MODEL) pal.primary else pal.onSurfaceVariant,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
                    Icon(Icons.Outlined.ExpandMore, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
                ContextRing("agent-usage", onClick = { onPanel(AgentPanel.USAGE) })
            }
            if (menu) {
                HeaderAction(Icons.Outlined.History, stringResource(R.string.agent_history), size = 32.dp, tag = "agent-history") { menu = false; onPanel(AgentPanel.HISTORY) }
                HeaderAction(Icons.Outlined.Memory, stringResource(R.string.agent_processes), size = 32.dp, tag = "agent-processes") { menu = false; onPanel(AgentPanel.PROCESSES) }
                HeaderAction(Icons.Outlined.Add, stringResource(R.string.agent_new), enabled = !working && AgentHost.entries.isNotEmpty(), size = 32.dp, tag = "agent-new") {
                    menu = false
                    AgentHost.forget(context)
                }
                HeaderAction(Icons.Outlined.Settings, stringResource(R.string.agent_setup_title), enabled = !working, size = 32.dp, tag = "agent-settings") {
                    menu = false
                    onSetup()
                }
            }
            HeaderAction(if (menu) Icons.Outlined.Close else Icons.Outlined.MoreHoriz, stringResource(R.string.agent_more), size = 32.dp, tag = "agent-menu") { menu = !menu }
        }
        Row(verticalAlignment = Alignment.Bottom) {
            if (LocalActivityResultRegistryOwner.current != null) AttachButton()
            Box(Modifier.weight(1f).padding(start = 8.dp, top = 10.dp, bottom = 10.dp)) {
                if (draft.isEmpty()) Text(
                    stringResource(if (working) R.string.agent_hint_working else R.string.agent_hint), fontSize = 16.sp,
                    color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
                )
                BasicTextField(
                    draft, { draft = it },
                    Modifier.fillMaxWidth().focusRequester(field).onFocusChanged {
                        focused = it.isFocused
                        if (it.isFocused) composer.opening = false
                    }.testTag("agent-input").then(inputModifier),
                    textStyle = TextStyle(fontSize = 16.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(pal.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    minLines = 1,
                    maxLines = 8,
                )
            }
            if (working) RoundButton(Icons.Outlined.Stop, stringResource(R.string.agent_stop), pal.error.copy(alpha = 0.22f), colors.error, "agent-stop") {
                AgentHost.stop(context)
            }
            val canSend = draft.isNotBlank() || AgentAttachments.pending.isNotEmpty()
            RoundButton(
                Icons.AutoMirrored.Outlined.Send, stringResource(if (working) R.string.agent_queue else R.string.agent_send),
                if (canSend) pal.primary else pal.surfaceVariant, if (canSend) pal.onPrimary else pal.onSurfaceVariant, "agent-send", enabled = canSend,
            ) { send() }
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
