package com.droiddeck.launcher.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Send
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentHost.Entry

@Composable
internal fun AgentPage(modifier: Modifier) {
    val context = LocalContext.current
    remember { AgentHost.refresh(context) }
    if (AgentHost.state == AgentHost.State.MISSING) {
        Column(modifier) { Note(stringResource(R.string.agent_missing)) }
        return
    }
    val colors = MaterialTheme.colorScheme
    val list = rememberLazyListState()
    var draft by rememberSaveable { mutableStateOf("") }
    val working = AgentHost.state == AgentHost.State.WORKING
    val send = {
        if (!working && draft.isNotBlank()) {
            AgentHost.send(context, draft)
            draft = ""
        }
    }
    LaunchedEffect(AgentHost.entries.size) {
        if (AgentHost.entries.isNotEmpty()) list.animateScrollToItem(AgentHost.entries.size - 1)
    }
    Column(modifier.fillMaxSize()) {
        LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            itemsIndexed(AgentHost.entries) { _, entry -> AgentEntry(entry) }
            if (working) item {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(4.dp)) {
                    CircularProgressIndicator(Modifier.size(16.dp), strokeWidth = 2.dp)
                }
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
            IconButton(onClick = { AgentHost.forget(context) }, enabled = !working) {
                Icon(Icons.Outlined.Add, stringResource(R.string.agent_new))
            }
            OutlinedTextField(
                draft, { draft = it }, Modifier.weight(1f),
                placeholder = { Text(stringResource(R.string.agent_hint)) },
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                keyboardActions = KeyboardActions(onSend = { send() }),
                maxLines = 4,
            )
            IconButton(onClick = send, enabled = !working && draft.isNotBlank()) {
                Icon(Icons.AutoMirrored.Outlined.Send, stringResource(R.string.agent_send), tint = colors.primary)
            }
        }
    }
}

@Composable
private fun AgentEntry(entry: Entry) {
    val colors = MaterialTheme.colorScheme
    when (entry) {
        is Entry.Asked -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(entry.text, fontSize = 15.sp, color = colors.onPrimaryContainer,
                modifier = Modifier.widthIn(max = 560.dp).clip(Shape12).background(colors.primaryContainer).padding(12.dp))
        }
        is Entry.Said -> Text(entry.text, fontSize = 15.sp, color = colors.onSurface, modifier = Modifier.widthIn(max = 720.dp).padding(horizontal = 4.dp))
        is Entry.Used -> Text(
            if (entry.detail.isEmpty()) entry.tool else "${entry.tool}  ${entry.detail}",
            fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.onSurfaceVariant, maxLines = 2,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        is Entry.Finished -> entry.error?.let {
            Text(stringResource(R.string.agent_failed, it), fontSize = 13.sp, color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}
