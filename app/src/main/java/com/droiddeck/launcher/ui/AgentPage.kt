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
import androidx.compose.animation.core.animateFloat
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.offset
import androidx.compose.material.icons.outlined.AutoAwesome
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
internal fun AgentPage(modifier: Modifier, inputModifier: Modifier = Modifier) {
    val context = LocalContext.current
    remember { AgentHost.refresh(context) }
    LaunchedEffect(AgentHost.unseen) { AgentHost.seen() }
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
                draft, { draft = it }, Modifier.weight(1f).then(inputModifier),
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

private object BubblePlace {
    var x by mutableStateOf(Float.NaN)
    var y by mutableStateOf(Float.NaN)
    var hidden by mutableStateOf(false)
}

/**
 * The agent's bubble over a session: there once the agent has been used, pulsing while it works,
 * marked when a reply or failure has not been seen. Drag it to either edge; a long press hides it
 * until the next session.
 */
@Composable
internal fun AgentBubble(onOpen: () -> Unit) {
    if (!AgentHost.installed || BubblePlace.hidden || AgentHost.entries.isEmpty()) return
    val colors = MaterialTheme.colorScheme
    val density = androidx.compose.ui.platform.LocalDensity.current
    val size = 56.dp
    val sizePx = with(density) { size.toPx() }
    val margin = with(density) { 12.dp.toPx() }
    val working = AgentHost.state == AgentHost.State.WORKING
    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "agentPulse")
    val ring by pulse.animateFloat(
        0.35f, 1f,
        androidx.compose.animation.core.infiniteRepeatable(androidx.compose.animation.core.tween(900), androidx.compose.animation.core.RepeatMode.Reverse),
        label = "agentRing",
    )
    androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxSize()) {
        val width = constraints.maxWidth.toFloat()
        val height = constraints.maxHeight.toFloat()
        if (BubblePlace.x.isNaN()) {
            BubblePlace.x = width - sizePx - margin
            BubblePlace.y = height * 0.3f
        }
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .offset { androidx.compose.ui.unit.IntOffset(BubblePlace.x.toInt(), BubblePlace.y.toInt()) }
                .size(size)
                .graphicsLayer { alpha = if (working || AgentHost.unseen) 1f else 0.7f }
                .clip(androidx.compose.foundation.shape.CircleShape)
                .background(colors.surface)
                .border(
                    2.dp,
                    if (working) colors.primary.copy(alpha = ring) else if (AgentHost.failed && AgentHost.unseen) colors.error else colors.outlineVariant,
                    androidx.compose.foundation.shape.CircleShape,
                )
                .pointerInput(width, height) {
                    detectDragGestures(onDragEnd = {
                        BubblePlace.x = if (BubblePlace.x + sizePx / 2 < width / 2) margin else width - sizePx - margin
                    }) { change, drag ->
                        change.consume()
                        BubblePlace.x = (BubblePlace.x + drag.x).coerceIn(0f, width - sizePx)
                        BubblePlace.y = (BubblePlace.y + drag.y).coerceIn(0f, height - sizePx)
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures(onTap = { onOpen() }, onLongPress = { BubblePlace.hidden = true })
                }
                .testTag("agent-bubble"),
        ) {
            Icon(androidx.compose.material.icons.Icons.Outlined.AutoAwesome, stringResource(R.string.rail_agent), tint = colors.primary)
            if (AgentHost.unseen && !working) Box(
                Modifier.align(Alignment.TopEnd).padding(6.dp).size(10.dp)
                    .clip(androidx.compose.foundation.shape.CircleShape)
                    .background(if (AgentHost.failed) colors.error else colors.primary),
            )
        }
    }
}
