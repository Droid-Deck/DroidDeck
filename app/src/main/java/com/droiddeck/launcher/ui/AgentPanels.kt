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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
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
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentChats
import com.droiddeck.launcher.agent.AgentHarness
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentProcesses
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** The model and plan mode for the next turns; a conversation keeps going with whatever is chosen. */
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
    val working = AgentHost.state == AgentHost.State.WORKING
    LazyColumn(modifier.fillMaxWidth().clip(Shape14).background(colors.surface).border(1.dp, pal.line, Shape14)) {
        items(chats, key = { it.id }) { chat ->
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
                        version++
                    } else armed = true
                }
            }
            Box(Modifier.fillMaxWidth().height(1.dp).background(pal.line))
        }
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
