package com.droiddeck.launcher.ui

import android.app.Activity
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.WindowManager
import androidx.activity.compose.LocalActivityResultRegistryOwner
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.AttachFile
import androidx.compose.material.icons.outlined.AutoAwesome
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.History
import androidx.compose.material.icons.outlined.Memory
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
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentAttachments
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentHost.Entry
import com.droiddeck.launcher.agent.AgentSetup

internal enum class AgentPanel { NONE, MODEL, HISTORY, PROCESSES }

/** Slash commands the app handles itself; others go to the CLI, which has its own. */
private val LOCAL_COMMANDS = listOf("/new", "/model", "/plan", "/history", "/processes", "/stop")
private val CLI_COMMANDS = listOf("/compact", "/cost", "/review", "/init")

@Composable
internal fun AgentPage(modifier: Modifier, inputModifier: Modifier = Modifier, visible: Boolean = true) {
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
    LaunchedEffect(AgentHost.unseen, visible) { if (visible) AgentHost.seen() }
    remember { AgentSetup.check(context) }
    var setup by rememberSaveable { mutableStateOf(false) }
    val draft = rememberSaveable { mutableStateOf("") }
    if (setup || AgentHost.state == AgentHost.State.MISSING || !(AgentSetup.ready || AgentSetup.completed(context))) {
        AgentSetupView(modifier, onDone = { setup = false })
        return
    }
    var panel by rememberSaveable { mutableStateOf(AgentPanel.NONE) }
    val collapseHeader = panel == AgentPanel.NONE || panel == AgentPanel.MODEL
    var headerHeight by remember { mutableStateOf(0) }
    var headerOffset by remember(collapseHeader) { mutableStateOf(0f) }
    val headerScroll = remember(collapseHeader) {
        object : NestedScrollConnection {
            override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset {
                if (!collapseHeader || source != NestedScrollSource.Drag) return Offset.Zero
                val previous = headerOffset
                headerOffset = (headerOffset + available.y).coerceIn(-headerHeight.toFloat(), 0f)
                return Offset(0f, headerOffset - previous)
            }
        }
    }
    BoxWithConstraints(modifier.fillMaxSize().imePadding().nestedScroll(headerScroll)) {
        val headerMaxHeight = maxHeight / 2
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.heightIn(max = headerMaxHeight).clipToBounds().layout { measurable, constraints ->
                val placeable = measurable.measure(constraints)
                val offset = if (collapseHeader) headerOffset.roundToInt().coerceIn(-placeable.height, 0) else 0
                layout(placeable.width, placeable.height + offset) { placeable.placeRelative(0, offset) }
            }) {
                Column(Modifier.onSizeChanged { headerHeight = it.height }) {
                    AgentHeader(panel, { panel = if (panel == it) AgentPanel.NONE else it }, onSetup = { setup = true })
                    if (panel == AgentPanel.MODEL) AgentModelPicker()
                }
            }
            when (panel) {
                AgentPanel.HISTORY -> AgentHistory(Modifier.weight(1f)) { panel = AgentPanel.NONE }
                AgentPanel.PROCESSES -> AgentProcessList(Modifier.weight(1f))
                else -> {
                    Column(Modifier.weight(1f).clipToBounds()) {
                        AgentConversation(Modifier.weight(1f))
                        AgentStatus()
                    }
                    AgentComposer(draft, inputModifier, compact = panel == AgentPanel.MODEL) { panel = it }
                }
            }
        }
    }
}

@Composable
private fun AgentHeader(panel: AgentPanel, onPanel: (AgentPanel) -> Unit, onSetup: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val working = AgentHost.state == AgentHost.State.WORKING
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
        Column(Modifier.weight(1f)) {
            Text(stringResource(R.string.rail_agent), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
            val model = AgentHost.models(context).firstOrNull { it.id == AgentHost.model }?.label ?: AgentHost.model ?: stringResource(R.string.agent_model_default)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.clip(Shape12).clickable { onPanel(AgentPanel.MODEL) }.padding(vertical = 2.dp).testTag("agent-model"),
            ) {
                val parts = listOfNotNull(AgentHost.harness.name, model, AgentHost.effort, stringResource(R.string.agent_plan_short).takeIf { AgentHost.plan && AgentHost.harness.plans })
                Text(parts.joinToString(" · "), fontSize = 13.sp, color = if (panel == AgentPanel.MODEL) pal.primary else pal.onSurfaceVariant)
                Icon(Icons.Outlined.ExpandMore, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
            }
        }
        HeaderAction(Icons.Outlined.History, stringResource(R.string.agent_history), selected = panel == AgentPanel.HISTORY, tag = "agent-history") { onPanel(AgentPanel.HISTORY) }
        HeaderAction(Icons.Outlined.Memory, stringResource(R.string.agent_processes), selected = panel == AgentPanel.PROCESSES, tag = "agent-processes") { onPanel(AgentPanel.PROCESSES) }
        HeaderAction(Icons.Outlined.Add, stringResource(R.string.agent_new), enabled = !working && AgentHost.entries.isNotEmpty(), tag = "agent-new") {
            AgentHost.forget(context)
            if (panel != AgentPanel.NONE) onPanel(panel)
        }
        HeaderAction(Icons.Outlined.Settings, stringResource(R.string.agent_setup_title), enabled = !working, tag = "agent-settings", onClick = onSetup)
    }
}

@Composable
internal fun HeaderAction(icon: ImageVector, label: String, enabled: Boolean = true, selected: Boolean = false, tag: String? = null, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(start = 8.dp).size(44.dp).clip(Shape12)
            .background(if (selected) pal.primary.copy(alpha = 0.2f) else pal.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick)
            .then(if (tag != null) Modifier.testTag(tag) else Modifier),
    ) {
        Icon(icon, label, tint = when {
            selected -> pal.primary
            enabled -> pal.onBackground
            else -> pal.onSurfaceVariant.copy(alpha = 0.5f)
        }, modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AgentConversation(modifier: Modifier) {
    val pal = LocalPalette.current
    val list = rememberLazyListState()
    val working = AgentHost.state == AgentHost.State.WORKING
    val atBottom by remember { derivedStateOf { !list.canScrollForward } }
    val items = AgentHost.entries.size + (if (working) 1 else 0)
    LaunchedEffect(items) { if (items > 0) list.animateScrollToItem(items - 1) }
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
            AgentEntry(entry, running = working && i > start && entry is Entry.Used && entry.output == null, last = !working && i == AgentHost.entries.lastIndex)
        }
        if (working) item { AgentLive() }
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

/** Above the composer: a Lab build's progress, the todo item in progress, and queued messages. */
@Composable
private fun AgentStatus() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val lab = AgentHost.lab
    if (lab != null) {
        var armed by remember(lab.pid) { mutableStateOf(false) }
        Column(
            Modifier.fillMaxWidth().padding(top = 10.dp).clip(Shape14).background(colors.surface).border(1.dp, if (lab.failed) AttentionAmber else pal.line, Shape14)
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
    val todos = AgentHost.todos()?.lines()?.filter { it.isNotBlank() }
    if (AgentHost.state == AgentHost.State.WORKING && !todos.isNullOrEmpty()) {
        val doing = todos.firstOrNull { it.startsWith("[~]") } ?: todos.firstOrNull { it.startsWith("[ ]") }
        val done = todos.count { it.startsWith("[x]") }
        if (doing != null) Text(
            "${doing.drop(4)}  ·  $done/${todos.size}", fontSize = 13.sp, color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(top = 8.dp, start = 4.dp),
        )
    }
    for (queued in AgentHost.queue) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(top = 8.dp).fillMaxWidth().clip(Shape12).background(pal.surfaceVariant).padding(start = 12.dp),
        ) {
            Text(stringResource(R.string.agent_queued, queued.text), fontSize = 13.sp, color = pal.onSurfaceVariant, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Box(contentAlignment = Alignment.Center, modifier = Modifier.size(40.dp).clickable { AgentHost.unqueue(queued) }) {
                Icon(Icons.Outlined.Close, stringResource(R.string.agent_unqueue), tint = pal.onSurfaceVariant, modifier = Modifier.size(18.dp))
            }
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

@Composable
private fun AgentComposer(draftState: MutableState<String>, inputModifier: Modifier, compact: Boolean, onPanel: (AgentPanel) -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var draft by draftState
    val working = AgentHost.state == AgentHost.State.WORKING
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
    if (draft.startsWith("/") && !draft.contains(' ')) {
        val matches = (LOCAL_COMMANDS + CLI_COMMANDS).filter { it.startsWith(draft) && it != draft }
        if (matches.isNotEmpty()) Row(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
        ) {
            for (command in matches) Text(
                command, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = pal.primary,
                modifier = Modifier.clip(Shape12).background(pal.surfaceVariant).clickable { draft = "$command " }.padding(horizontal = 12.dp, vertical = 8.dp),
            )
        }
    }
    if (AgentAttachments.pending.isNotEmpty()) Row(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        modifier = Modifier.padding(top = 10.dp).fillMaxWidth().horizontalScroll(rememberScrollState()),
    ) {
        for (path in AgentAttachments.pending.toList()) AttachmentChip(path) { AgentAttachments.pending.remove(path) }
    }
    var focused by remember { mutableStateOf(false) }
    val keyboardOpen = WindowInsets.ime.getBottom(LocalDensity.current) > 0
    val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    Row(
        verticalAlignment = Alignment.Bottom,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = if (keyboardOpen) 4.dp else 16.dp).clip(Shape16).background(colors.surface)
            .border(1.dp, if (focused) pal.primary else pal.line, Shape16).padding(start = 6.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
    ) {
        if (LocalActivityResultRegistryOwner.current != null) AttachButton()
        Box(Modifier.weight(1f).padding(start = 8.dp, top = 11.dp, bottom = 11.dp)) {
            if (draft.isEmpty()) Text(
                stringResource(if (working) R.string.agent_hint_working else R.string.agent_hint), fontSize = 16.sp,
                color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis,
            )
            BasicTextField(
                draft, { draft = it },
                Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("agent-input").then(inputModifier),
                textStyle = TextStyle(fontSize = 16.sp, color = colors.onSurface),
                cursorBrush = SolidColor(pal.primary),
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                minLines = 1,
                maxLines = if (compact) 1 else if (landscape) 3 else 8,
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
        modifier = Modifier.padding(start = 6.dp).size(46.dp).clip(CircleShape).background(background)
            .clickable(enabled = enabled, onClick = onClick).testTag(tag),
    ) {
        Icon(icon, label, tint = tint, modifier = Modifier.size(22.dp))
    }
}
