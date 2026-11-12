package com.droiddeck.launcher.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.Psychology
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.droiddeck.launcher.R
import com.droiddeck.launcher.agent.AgentAttachments
import com.droiddeck.launcher.agent.AgentHost
import com.droiddeck.launcher.agent.AgentHost.Entry

@Composable
internal fun AgentEntry(entry: Entry, running: Boolean, last: Boolean = false, changes: Changes? = null, onRevise: () -> Unit = {}) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    when (entry) {
        is Entry.Asked -> Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entry.files.isNotEmpty()) Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) { entry.files.forEach { AttachmentChip(it, null) } }
            if (entry.text.isNotEmpty()) SelectionContainer {
                Text(entry.text, fontSize = 15.sp, color = pal.onPrimary,
                    modifier = Modifier.widthIn(max = 560.dp).clip(Shape16).background(pal.primary).padding(horizontal = 14.dp, vertical = 10.dp))
            }
        }
        is Entry.Said -> SelectionContainer { AgentMarkdown(entry.text, Modifier.widthIn(max = 760.dp).padding(horizontal = 4.dp)) }
        is Entry.Thought -> AgentThought(entry.text, live = false)
        is Entry.Used -> if (entry.tool == "TodoWrite") AgentTodos(entry.input) else AgentToolCall(entry, running)
        is Entry.Result -> Unit
        is Entry.Finished -> when {
            last && AgentHost.answerless(AgentHost.entries) -> AgentNoReply()
            last && entry.planned -> Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                AgentTurnEnd(entry, changes)
                AgentPlanActions(onRevise)
            }
            else -> AgentTurnEnd(entry, changes)
        }
    }
}

/** A plan turn changed nothing; Go carries the plan out with plan mode off, Revise asks for another. */
@Composable
private fun AgentPlanActions(onRevise: () -> Unit) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    val go = stringResource(R.string.agent_plan_go_message)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        PrimaryButton(stringResource(R.string.agent_plan_go), compact = true, modifier = Modifier.testTag("agent-plan-go")) {
            AgentHost.choosePlan(context, false)
            AgentHost.send(context, go)
        }
        SecondaryButton(stringResource(R.string.agent_plan_revise), compact = true, modifier = Modifier.testTag("agent-plan-revise")) {
            AgentHost.choosePlan(context, true)
            onRevise()
        }
        Text(stringResource(R.string.agent_plan_untouched), fontSize = 12.sp, color = pal.onSurfaceVariant)
    }
}

/** What a turn's edits added up to: files touched and lines added and removed. */
internal data class Changes(val files: Int, val added: Int, val removed: Int)

/** The edits of the turn that ends at [end], or null when it changed no file. */
internal fun turnChanges(entries: List<Entry>, end: Int): Changes? {
    val start = entries.subList(0, end).indexOfLast { it is Entry.Asked }
    // Plan mode saves its plan as a file of its own, which is not a change to anything.
    val edits = entries.subList(start + 1, end).filterIsInstance<Entry.Used>().filter { !it.failed }.mapNotNull { diffOf(it) }
        .filter { !it.path.contains("/.claude/plans/") }
    if (edits.isEmpty()) return null
    return Changes(edits.map { it.path }.distinct().size, edits.sumOf { it.added }, edits.sumOf { it.removed })
}

internal class Diff(val path: String, val lines: List<String>) {
    val added = lines.count { it.startsWith("+") }
    val removed = lines.count { it.startsWith("-") }
}

/** The diff an Edit or Write carries: its path, then lines led by ' ', '-' or '+'. */
internal fun diffOf(entry: Entry.Used): Diff? {
    if (entry.tool != "Edit" && entry.tool != "Write") return null
    val lines = entry.input.lines()
    val body = lines.drop(1).filter { it.isNotEmpty() }
    if (lines.first().isBlank() || body.isEmpty() || body.any { it[0] != ' ' && it[0] != '-' && it[0] != '+' }) return null
    if (body.none { it[0] != ' ' }) return null
    return Diff(lines.first(), body)
}

@Composable
private fun AgentTurnEnd(entry: Entry.Finished, changes: Changes?) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val text = when {
        entry.stopped -> stringResource(R.string.agent_stopped)
        entry.error != null -> stringResource(R.string.agent_failed, entry.error)
        else -> entry.usage?.let { u ->
            buildList {
                if (u.durationMs > 0) add(clock(u.durationMs / 1000))
                if (u.costUsd >= 0.005) add("$" + String.format(java.util.Locale.US, "%.2f", u.costUsd))
                if (u.inputTokens > 0 || u.outputTokens > 0) add(stringResource(R.string.agent_tokens, tokens(u.inputTokens), tokens(u.outputTokens)))
            }.joinToString(" · ")
        }
    }
    if (text.isNullOrEmpty() && changes == null) return
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        if (changes != null) Row(
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp),
            modifier = Modifier.clip(Shape12).background(pal.surfaceVariant).padding(horizontal = 9.dp, vertical = 4.dp).testTag("agent-changes"),
        ) {
            Text(pluralStringResource(R.plurals.agent_files_changed, changes.files, changes.files), fontSize = 12.sp, color = pal.onSurfaceVariant)
            Text("+${changes.added}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = pal.good)
            Text("−${changes.removed}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.error)
        }
        if (!text.isNullOrEmpty()) Text(text, fontSize = 12.sp, color = if (entry.error != null && !entry.stopped) colors.error else pal.onSurfaceVariant)
    }
}

@Composable
private fun AgentNoReply() {
    val context = LocalContext.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.padding(horizontal = 4.dp)) {
        Text(stringResource(R.string.agent_no_reply), fontSize = 13.sp, color = AttentionAmber)
        SecondaryButton(stringResource(R.string.agent_retry), compact = true) { AgentHost.retry(context) }
    }
}

internal fun tokens(n: Long) = when {
    n >= 1_000_000 -> if (n % 1_000_000 == 0L) "${n / 1_000_000}M" else String.format(java.util.Locale.US, "%.1fM", n / 1_000_000.0)
    n >= 1000 -> "${n / 1000}k"
    else -> n.toString()
}

@Composable
internal fun AgentThought(text: String, live: Boolean) {
    val pal = LocalPalette.current
    var open by rememberSaveable(text.take(40)) { mutableStateOf(false) }
    Column(Modifier.padding(horizontal = 4.dp).animateContentSize()) {
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clip(Shape12).clickable { open = !open }.padding(vertical = 4.dp)) {
            Icon(Icons.Outlined.Psychology, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
            Text(stringResource(if (live) R.string.agent_thinking else R.string.agent_thought), fontSize = 13.sp, color = pal.onSurfaceVariant,
                modifier = Modifier.padding(start = 6.dp))
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
        }
        if (open || live) Text(
            if (open) text else text.takeLast(240), fontSize = 13.sp, fontStyle = FontStyle.Italic, color = pal.onSurfaceVariant, lineHeight = 19.sp,
            maxLines = if (open) Int.MAX_VALUE else 3, overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 760.dp).padding(start = 22.dp),
        )
    }
}

@Composable
private fun AgentTodos(list: String) {
    val pal = LocalPalette.current
    val colors = MaterialTheme.colorScheme
    Column(
        Modifier.widthIn(max = 560.dp).padding(horizontal = 4.dp).clip(Shape12).background(pal.surfaceVariant).padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        for (line in list.lines().filter { it.isNotBlank() }) {
            val done = line.startsWith("[x]")
            val doing = line.startsWith("[~]")
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(14.dp).clip(Shape12).background(when {
                    done -> pal.good
                    doing -> pal.primary
                    else -> pal.line
                }))
                Text(line.drop(4), fontSize = 13.sp, color = if (done) pal.onSurfaceVariant else colors.onSurface,
                    fontWeight = if (doing) FontWeight.SemiBold else null, modifier = Modifier.padding(start = 8.dp))
            }
        }
    }
}

/** A tool call: one line with its status, opening to the full input and output, or to the diff of an edit. */
@Composable
private fun AgentToolCall(entry: Entry.Used, running: Boolean) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    var open by rememberSaveable(entry.id.ifEmpty { entry.detail }) { mutableStateOf(false) }
    val diff = remember(entry.input) { diffOf(entry) }
    Column(
        Modifier.fillMaxWidth().padding(horizontal = 4.dp).clip(Shape12).background(pal.surfaceVariant).animateContentSize(),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().clickable { open = !open }.testTag("agent-tool").padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Box(Modifier.size(16.dp), contentAlignment = Alignment.Center) {
                when {
                    running -> CircularProgressIndicator(Modifier.size(13.dp), strokeWidth = 2.dp, color = pal.primary)
                    entry.failed -> Icon(Icons.Outlined.ErrorOutline, null, tint = colors.error, modifier = Modifier.size(16.dp))
                    entry.output != null -> Icon(Icons.Outlined.CheckCircle, null, tint = pal.good, modifier = Modifier.size(16.dp))
                    else -> Icon(Icons.Outlined.Close, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(16.dp))
                }
            }
            Text(entry.tool, fontSize = 12.5.sp, fontWeight = FontWeight.SemiBold, color = pal.primary, modifier = Modifier.padding(start = 8.dp))
            Text(diff?.path?.substringAfterLast('/') ?: entry.detail, fontSize = 12.5.sp, fontFamily = FontFamily.Monospace, color = pal.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f).padding(start = 8.dp))
            if (diff != null) {
                Text("+${diff.added}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = pal.good, modifier = Modifier.padding(start = 8.dp))
                Text("\u2212${diff.removed}", fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = colors.error, modifier = Modifier.padding(start = 6.dp, end = 4.dp))
            }
            Icon(if (open) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(18.dp))
        }
        if (open && diff != null) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (diff.path.contains('/')) Text(diff.path, fontSize = 11.5.sp, fontFamily = FontFamily.Monospace, color = pal.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
            DiffBox(diff)
            if (entry.failed && !entry.output.isNullOrBlank()) CodeBox(stringResource(R.string.agent_tool_output), entry.output, colors.error)
        } else if (open) Column(Modifier.padding(start = 10.dp, end = 10.dp, bottom = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (entry.input.isNotBlank()) CodeBox(stringResource(R.string.agent_tool_input), entry.input, colors.onSurface)
            when {
                entry.output == null && running -> Text(stringResource(R.string.agent_tool_running), fontSize = 12.sp, color = pal.onSurfaceVariant)
                entry.output == null -> Text(stringResource(R.string.agent_tool_no_result), fontSize = 12.sp, color = pal.onSurfaceVariant)
                entry.output.isBlank() -> Text(stringResource(R.string.agent_tool_empty), fontSize = 12.sp, color = pal.onSurfaceVariant)
                else -> CodeBox(stringResource(R.string.agent_tool_output), entry.output, if (entry.failed) colors.error else colors.onSurface)
            }
        }
    }
}

@Composable
private fun DiffBox(diff: Diff) {
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    Column(
        Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(Shape12).background(colors.surface)
            .verticalScroll(rememberScrollState()).padding(vertical = 6.dp),
    ) {
        for (line in diff.lines) {
            val tint = when (line[0]) {
                '+' -> pal.good
                '-' -> colors.error
                else -> null
            }
            Text(
                line[0] + " " + line.substring(1),
                fontSize = 12.sp, fontFamily = FontFamily.Monospace, lineHeight = 17.sp,
                color = tint ?: pal.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().background(tint?.copy(alpha = 0.12f) ?: Color.Transparent).padding(horizontal = 10.dp),
            )
        }
    }
}

@Composable
private fun CodeBox(label: String, text: String, color: Color) {
    val pal = LocalPalette.current
    Column {
        Text(label, fontSize = 11.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 1.sp, color = pal.onSurfaceVariant, modifier = Modifier.padding(bottom = 3.dp))
        SelectionContainer {
            Text(
                text, fontSize = 12.sp, fontFamily = FontFamily.Monospace, color = color, lineHeight = 17.sp,
                modifier = Modifier.fillMaxWidth().heightIn(max = 360.dp).clip(Shape12).background(MaterialTheme.colorScheme.surface)
                    .verticalScroll(rememberScrollState()).padding(10.dp),
            )
        }
    }
}

/** An attached file: a thumbnail for images, the name otherwise; [onRemove] adds a remove mark. */
@Composable
internal fun AttachmentChip(path: String, onRemove: (() -> Unit)?) {
    val context = LocalContext.current
    val pal = LocalPalette.current
    Box {
        if (AgentAttachments.isImage(path)) AsyncImage(
            model = AgentAttachments.host(context, path), contentDescription = path.substringAfterLast('/'), contentScale = ContentScale.Crop,
            modifier = Modifier.size(72.dp).clip(Shape12).background(pal.surfaceVariant),
        ) else Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.heightIn(min = 40.dp).clip(Shape12).background(pal.surfaceVariant).padding(horizontal = 10.dp, vertical = 8.dp),
        ) {
            Icon(Icons.Outlined.Description, null, tint = pal.onSurfaceVariant, modifier = Modifier.size(18.dp))
            Text(path.substringAfterLast('/'), fontSize = 13.sp, color = MaterialTheme.colorScheme.onSurface, maxLines = 1,
                overflow = TextOverflow.Ellipsis, modifier = Modifier.widthIn(max = 200.dp).padding(start = 6.dp, end = if (onRemove != null) 18.dp else 0.dp))
        }
        if (onRemove != null) Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.align(Alignment.TopEnd).padding(3.dp).size(20.dp).clip(Shape12).background(MaterialTheme.colorScheme.surface).clickable(onClick = onRemove),
        ) {
            Icon(Icons.Outlined.Close, stringResource(R.string.agent_remove_attachment), tint = pal.onSurfaceVariant, modifier = Modifier.size(14.dp))
        }
    }
}

/** The Markdown agents write: headings, lists, fenced code, `code` and **bold**. */
@Composable
internal fun AgentMarkdown(text: String, modifier: Modifier) {
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
