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
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
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
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.outlined.Stop
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentHost.Entry

@Composable
internal fun AgentPage(modifier: Modifier, inputModifier: Modifier = Modifier, visible: Boolean = true) {
    val context = LocalContext.current
    remember { AgentHost.refresh(context) }
    LaunchedEffect(AgentHost.unseen, visible) { if (visible) AgentHost.seen() }
    remember { com.droiddeck.launcher.agent.AgentSetup.check(context) }
    var setup by rememberSaveable { mutableStateOf(false) }
    if (setup || AgentHost.state == AgentHost.State.MISSING ||
        !(com.droiddeck.launcher.agent.AgentSetup.ready || com.droiddeck.launcher.agent.AgentSetup.completed(context))) {
        AgentSetupView(modifier, onDone = { setup = false })
        return
    }
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
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
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp)) {
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.rail_agent), fontSize = 22.sp, fontWeight = FontWeight.Bold, color = colors.onBackground)
                Text(AgentHost.harness.name, fontSize = 13.sp, color = pal.onSurfaceVariant)
            }
            HeaderAction(Icons.Outlined.Add, stringResource(R.string.agent_new), enabled = !working && AgentHost.entries.isNotEmpty()) { AgentHost.forget(context) }
            HeaderAction(Icons.Outlined.Settings, stringResource(R.string.agent_setup_title), enabled = !working) { setup = true }
        }
        if (AgentHost.entries.isEmpty() && !working) {
            Column(Modifier.weight(1f).fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
                Icon(Icons.Outlined.AutoAwesome, null, tint = pal.primary, modifier = Modifier.size(40.dp))
                Text(stringResource(R.string.agent_empty), fontSize = 15.sp, color = pal.onSurfaceVariant,
                    textAlign = TextAlign.Center, modifier = Modifier.widthIn(max = 460.dp).padding(top = 12.dp))
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth(), state = list, verticalArrangement = Arrangement.spacedBy(10.dp)) {
                itemsIndexed(AgentHost.entries) { _, entry -> AgentEntry(entry) }
                if (working) item {
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(4.dp)) {
                        CircularProgressIndicator(Modifier.size(14.dp), strokeWidth = 2.dp, color = pal.primary)
                        Text(stringResource(R.string.agent_working), fontSize = 13.sp, color = pal.onSurfaceVariant)
                    }
                }
            }
        }
        var focused by remember { mutableStateOf(false) }
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(top = 10.dp, bottom = 6.dp).clip(Shape16).background(colors.surface)
                .border(1.dp, if (focused) pal.primary else pal.line, Shape16).padding(start = 18.dp, end = 6.dp, top = 6.dp, bottom = 6.dp),
        ) {
            Box(Modifier.weight(1f).padding(vertical = 8.dp)) {
                if (draft.isEmpty()) Text(stringResource(R.string.agent_hint), fontSize = 16.sp, color = pal.onSurfaceVariant)
                BasicTextField(
                    draft, { draft = it },
                    Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused }.testTag("agent-input").then(inputModifier),
                    textStyle = TextStyle(fontSize = 16.sp, color = colors.onSurface),
                    cursorBrush = SolidColor(pal.primary),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { send() }),
                    maxLines = 5,
                )
            }
            val canSend = !working && draft.isNotBlank()
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(44.dp).clip(CircleShape)
                    .background(if (canSend) pal.primary else pal.surfaceVariant)
                    .clickable(enabled = working || canSend) { if (working) AgentHost.stop() else send() }
                    .testTag("agent-send"),
            ) {
                Icon(
                    if (working) Icons.Outlined.Stop else Icons.AutoMirrored.Outlined.Send,
                    stringResource(if (working) R.string.agent_stop else R.string.agent_send),
                    tint = if (canSend) pal.onPrimary else pal.onSurfaceVariant, modifier = Modifier.size(20.dp),
                )
            }
        }
    }
}

@Composable
private fun HeaderAction(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, enabled: Boolean, onClick: () -> Unit) {
    val pal = LocalPalette.current
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier.padding(start = 8.dp).size(44.dp).clip(Shape12).background(pal.surfaceVariant)
            .clickable(enabled = enabled, onClick = onClick),
    ) {
        Icon(icon, label, tint = if (enabled) pal.onBackground else pal.onSurfaceVariant.copy(alpha = 0.5f), modifier = Modifier.size(22.dp))
    }
}

@Composable
private fun AgentEntry(entry: Entry) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    when (entry) {
        is Entry.Asked -> Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.CenterEnd) {
            Text(entry.text, fontSize = 15.sp, color = pal.onPrimary,
                modifier = Modifier.widthIn(max = 560.dp).clip(Shape16).background(pal.primary).padding(horizontal = 14.dp, vertical = 10.dp))
        }
        is Entry.Said -> AgentMarkdown(entry.text, Modifier.widthIn(max = 760.dp).padding(horizontal = 4.dp))
        is Entry.Used -> Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 4.dp).clip(Shape12).background(pal.surfaceVariant).padding(horizontal = 10.dp, vertical = 6.dp),
        ) {
            Text(entry.tool, fontSize = 12.sp, fontWeight = FontWeight.SemiBold, color = pal.primary)
            if (entry.detail.isNotEmpty()) Text(entry.detail, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = pal.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 8.dp))
        }
        is Entry.Finished -> entry.error?.let {
            Text(stringResource(R.string.agent_failed, it), fontSize = 13.sp, color = colors.error, modifier = Modifier.padding(horizontal = 4.dp))
        }
    }
}

/** The Markdown agents write: headings, lists, fenced code, `code` and **bold**. */
@Composable
private fun AgentMarkdown(text: String, modifier: Modifier) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(modifier, verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (block in markdownBlocks(text)) when (block) {
            is MdBlock.Code -> Text(block.text, fontSize = 13.sp, fontFamily = FontFamily.Monospace, color = colors.onSurface,
                modifier = Modifier.fillMaxWidth().clip(Shape12).background(pal.surfaceVariant).padding(12.dp))
            is MdBlock.Line -> Text(
                inlineMarkdown(block.text, pal.primary2, pal.surfaceVariant),
                fontSize = if (block.heading) 17.sp else 15.sp,
                fontWeight = if (block.heading) FontWeight.Bold else null,
                lineHeight = 22.sp, color = colors.onSurface,
                modifier = if (block.bullet) Modifier.padding(start = 12.dp) else Modifier,
            )
        }
    }
}

internal sealed interface MdBlock {
    data class Line(val text: String, val heading: Boolean = false, val bullet: Boolean = false) : MdBlock
    data class Code(val text: String) : MdBlock
}

private val HEADING = Regex("^#{1,6}\\s+")
private val BULLET = Regex("^\\s*[-*+]\\s+")

internal fun markdownBlocks(text: String): List<MdBlock> {
    val out = mutableListOf<MdBlock>()
    val code = StringBuilder()
    var inCode = false
    for (raw in text.lines()) {
        if (raw.trimStart().startsWith("```")) {
            if (inCode) {
                out += MdBlock.Code(code.toString().trimEnd('\n'))
                code.clear()
            }
            inCode = !inCode
            continue
        }
        if (inCode) {
            code.append(raw).append('\n')
            continue
        }
        val line = raw.trimEnd()
        if (line.isBlank()) continue
        val heading = HEADING.find(line)
        val bullet = BULLET.find(line)
        out += when {
            heading != null -> MdBlock.Line(line.substring(heading.range.last + 1), heading = true)
            bullet != null -> MdBlock.Line("•  " + line.substring(bullet.range.last + 1), bullet = true)
            else -> MdBlock.Line(line)
        }
    }
    if (inCode && code.isNotEmpty()) out += MdBlock.Code(code.toString().trimEnd('\n'))
    return out
}

internal fun inlineMarkdown(text: String, codeColor: Color, codeBackground: Color): AnnotatedString = buildAnnotatedString {
    var i = 0
    while (i < text.length) {
        val boldEnd = if (text.startsWith("**", i)) text.indexOf("**", i + 2) else -1
        val tickEnd = if (text[i] == '`') text.indexOf('`', i + 1) else -1
        when {
            boldEnd > i + 2 -> {
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) { append(text, i + 2, boldEnd) }
                i = boldEnd + 2
            }
            tickEnd > i + 1 -> {
                withStyle(SpanStyle(fontFamily = FontFamily.Monospace, color = codeColor, background = codeBackground)) { append(text, i + 1, tickEnd) }
                i = tickEnd + 1
            }
            else -> append(text[i++])
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
