package com.droiddeck.launcher.ui

import android.text.format.DateUtils
import android.text.format.Formatter
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material3.Icon
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.TextStyle
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import com.droiddeck.launcher.agent.AgentTools
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentChats
import com.droiddeck.launcher.agent.AgentHarness
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentProcesses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The model, reasoning effort and plan mode for the next turns; a conversation keeps going with whatever is chosen. */
@Composable
internal fun AgentModelPicker() {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val models = remember(AgentHost.harness) { AgentHost.models(context) }
    Column(Modifier.fillMaxWidth().padding(bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
            val choices = listOf(AgentHarness.Model("", stringResource(R.string.agent_model_default))) + models
            for (choice in choices) {
                val id = choice.id.ifEmpty { null }
                if (id == AgentHost.model) PrimaryButton(choice.label, compact = true) {}
                else SecondaryButton(choice.label, compact = true) { AgentHost.chooseModel(context, id) }
            }
        }
        if (AgentHost.harness.efforts.isNotEmpty()) Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.agent_effort), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground, modifier = Modifier.padding(horizontal = 2.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.horizontalScroll(rememberScrollState())) {
                for (level in listOf<String?>(null) + AgentHost.harness.efforts) {
                    val label = level ?: stringResource(R.string.agent_model_default)
                    if (level == AgentHost.effort) PrimaryButton(label, compact = true) {}
                    else SecondaryButton(label, compact = true) { AgentHost.chooseEffort(context, level) }
                }
            }
        }
        if (AgentHost.harness.plans) Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(horizontal = 2.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.agent_plan), fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onBackground)
                Text(stringResource(R.string.agent_plan_hint), fontSize = 12.5.sp, color = pal.onSurfaceVariant)
            }
            ToggleSwitch(AgentHost.plan, label = stringResource(R.string.agent_plan)) { AgentHost.choosePlan(context, it) }
        }
    }
}

@Composable
internal fun AgentHistory(modifier: Modifier, onOpened: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var version by remember { mutableIntStateOf(0) }
    val chats by produceState(emptyList<AgentChats.Chat>(), version, AgentHost.entries.size) {
        value = withContext(Dispatchers.IO) { AgentChats.list(context) }
    }
    if (chats.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.agent_history_empty), fontSize = 15.sp, color = pal.onSurfaceVariant)
        }
        return
    }
    var query by rememberSaveable { mutableStateOf("") }
    val found by produceState<List<AgentChats.Chat>?>(null, chats, query) {
        if (query.isNotBlank()) delay(250)
        value = withContext(Dispatchers.IO) { AgentChats.search(context, chats, query) }
    }
    val working = AgentHost.state == AgentHost.State.WORKING
    Column(modifier.fillMaxWidth()) {
        AgentHistorySearch(query) { query = it }
        val shown = found ?: chats
        if (shown.isEmpty()) Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.agent_history_no_match), fontSize = 15.sp, color = pal.onSurfaceVariant)
        } else LazyColumn(Modifier.weight(1f).fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
            items(shown, key = { it.id }) { chat -> AgentHistoryRow(chat, working, onOpened) { version++ } }
        }
    }
}

@Composable
private fun AgentHistorySearch(query: String, onQuery: (String) -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp).height(44.dp).clip(Shape14).background(colors.surface)
            .border(1.dp, pal.line, Shape14).padding(horizontal = 12.dp),
    ) {
        Icon(Icons.Outlined.Search, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(18.dp))
        Box(Modifier.weight(1f).padding(start = 8.dp)) {
            if (query.isEmpty()) Text(stringResource(R.string.agent_history_search), fontSize = 15.sp, color = pal.onSurfaceVariant, maxLines = 1)
            BasicTextField(
                query, onQuery, singleLine = true, textStyle = TextStyle(fontSize = 15.sp, color = colors.onSurface),
                cursorBrush = SolidColor(pal.primary), modifier = Modifier.fillMaxWidth().testTag("agent-history-search"),
            )
        }
        if (query.isNotEmpty()) Icon(Icons.Outlined.Close, stringResource(R.string.agent_close), tint = pal.onSurfaceVariant,
            modifier = Modifier.size(28.dp).clip(CircleShape).clickable { onQuery("") }.padding(5.dp))
    }
}

@Composable
private fun AgentHistoryRow(chat: AgentChats.Chat, working: Boolean, onOpened: () -> Unit, onDeleted: () -> Unit) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column {
        var armed by remember(chat.id) { mutableStateOf(false) }
        val current = chat.id == AgentHost.chat
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().heightIn(min = 62.dp)
                .clickable(enabled = !working || current) {
                    AgentHost.open(context, chat.id)
                    onOpened()
                }
                .background(if (current) pal.primary.copy(alpha = 0.12f) else colors.surface)
                .padding(horizontal = 14.dp, vertical = 10.dp).testTag("agent-chat"),
        ) {
            Column(Modifier.weight(1f)) {
                Text(chat.title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(
                    if (armed) stringResource(R.string.agent_history_delete_warning)
                    else AgentHarness.byId(chat.harness).name + " · " + when {
                        System.currentTimeMillis() - chat.updated < 60_000 -> stringResource(R.string.agent_just_now)
                        else -> DateUtils.getRelativeTimeSpanString(chat.updated, System.currentTimeMillis(), DateUtils.MINUTE_IN_MILLIS).toString()
                    },
                    fontSize = 12.5.sp, color = if (armed) AttentionAmber else pal.onSurfaceVariant,
                )
            }
            SecondaryButton(
                stringResource(if (armed) R.string.agent_storage_confirm else R.string.agent_history_delete), compact = true,
                enabled = !(current && working),
            ) {
                if (armed) {
                    AgentHost.delete(context, chat.id)
                    onDeleted()
                } else armed = true
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
    }
}

/** Processes in the Linux userland, the agent's own first, each with a way to end it. */
@Composable
internal fun AgentProcessList(modifier: Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var refresh by remember { mutableIntStateOf(0) }
    var procs by remember { mutableStateOf<List<AgentProcesses.Proc>?>(null) }
    LaunchedEffect(refresh) {
        while (true) {
            procs = withContext(Dispatchers.IO) { AgentProcesses.snapshot(AgentHost.turnPid) }
            delay(2_000)
        }
    }
    val list = procs ?: return
    if (list.isEmpty()) {
        Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(stringResource(R.string.agent_processes_empty), fontSize = 15.sp, color = pal.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        items(list, key = { it.pid }) { proc ->
            var open by remember(proc.pid) { mutableStateOf(false) }
            Column(Modifier.fillMaxWidth().clickable { open = !open }.padding(horizontal = 14.dp, vertical = 10.dp).testTag("agent-process")) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            Text(proc.name, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground)
                            if (proc.agent) Text(stringResource(R.string.agent_process_agent), fontSize = 11.sp, fontWeight = FontWeight.SemiBold, color = pal.primary,
                                modifier = Modifier.clip(Shape12).background(pal.primary.copy(alpha = 0.15f)).padding(horizontal = 6.dp, vertical = 1.dp))
                        }
                        Text(
                            listOf("pid ${proc.pid}", clock(proc.seconds), Formatter.formatShortFileSize(context, proc.rssKb * 1024), "${proc.cpuPercent}% CPU").joinToString(" · "),
                            fontSize = 12.5.sp, color = pal.onSurfaceVariant,
                        )
                    }
                    SecondaryButton(stringResource(R.string.agent_process_stop), compact = true) {
                        AgentProcesses.killTree(proc.pid)
                        refresh++
                    }
                }
                SelectionContainer {
                    Text(proc.args, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = pal.onSurfaceVariant,
                        maxLines = if (open) 40 else 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 4.dp))
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
    }
}

/** How full the conversation's context is, how much of the plan's limits is used, and what this conversation cost. */
@Composable
internal fun AgentUsage() {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(horizontal = 14.dp, vertical = 10.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        if (AgentHost.window > 0) UsageRow(
            stringResource(R.string.agent_usage_context),
            stringResource(R.string.agent_usage_of, tokens(AgentHost.context), tokens(AgentHost.window)),
            (AgentHost.context.toFloat() / AgentHost.window).coerceIn(0f, 1f),
        )
        for (limit in AgentHost.limits) {
            val used = liveUsed(limit)
            val label = when {
                limit.minutes in 9_000..11_000 -> stringResource(R.string.agent_limit_week)
                limit.minutes >= 60 -> stringResource(R.string.agent_limit_hours, limit.minutes / 60)
                else -> stringResource(R.string.agent_limit_minutes, limit.minutes)
            }
            val value = if (limit.resetsAt * 1000 > System.currentTimeMillis()) stringResource(R.string.agent_limit_resets, used, resetTime(context, limit.resetsAt)) else "$used%"
            UsageRow(label, value, used / 100f)
        }
        val turns = AgentHost.entries.filterIsInstance<AgentHost.Entry.Finished>().mapNotNull { it.usage }
        val cost = turns.sumOf { it.costUsd }
        val ms = turns.sumOf { it.durationMs }
        Row(verticalAlignment = Alignment.CenterVertically) {
            val facts = buildList {
                if (ms > 0) add(clock(ms / 1000))
                if (cost >= 0.005) add("$" + String.format(java.util.Locale.US, "%.2f", cost))
            }
            Text(
                if (facts.isEmpty()) stringResource(R.string.agent_usage_unknown) else stringResource(R.string.agent_usage_conversation, facts.joinToString(" · ")),
                fontSize = 12.5.sp, color = pal.onSurfaceVariant, modifier = Modifier.weight(1f),
            )
            if (AgentHost.harness.plans && AgentHost.entries.isNotEmpty() && AgentHost.state == AgentHost.State.IDLE) {
                SecondaryButton("/compact", compact = true) { AgentHost.send(context, "/compact") }
            }
        }
    }
}

@Composable
private fun UsageRow(label: String, value: String, fraction: Float) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(label, fontSize = 14.sp, color = colors.onBackground, modifier = Modifier.weight(1f))
            Text(value, fontSize = 12.5.sp, color = pal.onSurfaceVariant)
        }
        LinearProgressIndicator(
            progress = { fraction }, color = if (fraction >= 0.8f) AttentionAmber else pal.primary, trackColor = pal.surfaceVariant,
            modifier = Modifier.fillMaxWidth().height(5.dp).clip(CircleShape),
        )
    }
}

/** A reset later today shows its time; one further off shows the day too. */
private fun resetTime(context: android.content.Context, epochSeconds: Long): String {
    val ms = epochSeconds * 1000
    val flags = if (ms - System.currentTimeMillis() < 20 * 3_600_000L) DateUtils.FORMAT_SHOW_TIME
    else DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_ABBREV_WEEKDAY or DateUtils.FORMAT_SHOW_TIME
    return DateUtils.formatDateTime(context, ms, flags)
}

/** Claude Code's MCP servers, with a way to add and remove them, and the skills in its skills folder. */
@Composable
internal fun AgentToolsPanel(modifier: Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val scope = rememberCoroutineScope()
    var version by remember { mutableIntStateOf(0) }
    var loading by remember { mutableStateOf(true) }
    val servers by produceState<List<AgentTools.Server>?>(null, version) {
        loading = true
        value = withContext(Dispatchers.IO) { AgentTools.servers(context) }
        loading = false
    }
    val skills by produceState(emptyList<AgentTools.Skill>(), version) { value = withContext(Dispatchers.IO) { AgentTools.skills(context) } }
    var name by rememberSaveable { mutableStateOf("") }
    var target by rememberSaveable { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var message by remember { mutableStateOf<String?>(null) }
    val act: (suspend () -> AgentTools.Result) -> Unit = { action ->
        busy = true
        message = null
        scope.launch {
            val result = withContext(Dispatchers.IO) { action() }
            busy = false
            if (!result.ok) message = result.output.ifBlank { context.getString(R.string.agent_tools_failed) }
            else {
                name = ""
                target = ""
            }
            version++
        }
    }
    LazyColumn(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        item { ToolsHeading(stringResource(R.string.agent_tools_servers)) }
        val list = servers
        when {
            loading && list == null -> item { Text(stringResource(R.string.agent_tools_loading), fontSize = 13.sp, color = pal.onSurfaceVariant) }
            list == null -> item { Text(stringResource(R.string.agent_tools_failed), fontSize = 13.sp, color = AttentionAmber) }
            list.isEmpty() -> item { Text(stringResource(R.string.agent_tools_no_servers), fontSize = 13.sp, color = pal.onSurfaceVariant) }
            else -> items(list, key = { "mcp:" + it.name }) { server ->
                var armed by remember(server.name) { mutableStateOf(false) }
                ToolsRow(server.name, server.target + " · " + server.status) {
                    if (!server.account) SecondaryButton(
                        stringResource(if (armed) R.string.agent_storage_confirm else R.string.agent_tools_remove), compact = true, enabled = !busy,
                    ) { if (armed) act { AgentTools.remove(context, server.name) } else armed = true }
                }
            }
        }
        item {
            Column(
                Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(12.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                ToolsField(name, { name = it }, stringResource(R.string.agent_tools_name), "agent-tools-name")
                ToolsField(target, { target = it }, stringResource(R.string.agent_tools_target), "agent-tools-target")
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(stringResource(R.string.agent_tools_add_hint), fontSize = 12.sp, color = pal.onSurfaceVariant, modifier = Modifier.weight(1f))
                    PrimaryButton(stringResource(R.string.agent_tools_add), enabled = !busy && name.isNotBlank() && target.isNotBlank(), compact = true) {
                        act { AgentTools.add(context, name, target) }
                    }
                }
                message?.let { Text(it, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = AttentionAmber) }
            }
        }
        item { ToolsHeading(stringResource(R.string.agent_tools_skills)) }
        if (skills.isEmpty()) item { Text(stringResource(R.string.agent_tools_no_skills), fontSize = 13.sp, color = pal.onSurfaceVariant) }
        items(skills, key = { "skill:" + it.name }) { skill ->
            var armed by remember(skill.name) { mutableStateOf(false) }
            ToolsRow(skill.name, skill.description) {
                SecondaryButton(stringResource(if (armed) R.string.agent_storage_confirm else R.string.agent_history_delete), compact = true, enabled = !busy) {
                    if (armed) {
                        AgentTools.deleteSkill(context, skill.name)
                        version++
                    } else armed = true
                }
            }
        }
    }
}

@Composable
private fun ToolsHeading(text: String) {
    Text(text, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, color = LocalPalette.current.onSurfaceVariant, modifier = Modifier.padding(top = 6.dp))
}

@Composable
private fun ToolsRow(title: String, detail: String, action: @Composable () -> Unit) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14).padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, fontSize = 15.sp, fontWeight = FontWeight.SemiBold, color = colors.onBackground, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (detail.isNotBlank()) Text(detail, fontSize = 12.5.sp, color = pal.onSurfaceVariant, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        action()
    }
}

@Composable
private fun ToolsField(value: String, onValue: (String) -> Unit, hint: String, tag: String) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Box(Modifier.fillMaxWidth().clip(Shape12).background(pal.surfaceVariant).padding(horizontal = 12.dp, vertical = 10.dp)) {
        if (value.isEmpty()) Text(hint, fontSize = 14.sp, color = pal.onSurfaceVariant, maxLines = 1)
        BasicTextField(
            value, onValue, singleLine = true, textStyle = TextStyle(fontSize = 14.sp, fontFamily = FontFamily.Monospace, color = colors.onSurface),
            cursorBrush = SolidColor(pal.primary), modifier = Modifier.fillMaxWidth().testTag(tag),
        )
    }
}
