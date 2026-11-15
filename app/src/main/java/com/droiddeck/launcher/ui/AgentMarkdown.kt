package com.droiddeck.launcher.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.text.Selection
import android.text.Spannable
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.widget.TextView
import android.widget.Toast
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import com.droiddeck.launcher.R
import io.noties.markwon.AbstractMarkwonPlugin
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.MarkwonVisitor
import io.noties.markwon.core.MarkwonTheme
import io.noties.markwon.ext.strikethrough.StrikethroughPlugin
import io.noties.markwon.ext.tables.TableAwareMovementMethod
import io.noties.markwon.ext.tables.TablePlugin
import io.noties.markwon.ext.tables.TableRowSpan
import io.noties.markwon.ext.tasklist.TaskListPlugin
import org.commonmark.ext.autolink.AutolinkExtension
import org.commonmark.parser.Parser
import org.commonmark.ext.gfm.tables.TableCell
import org.commonmark.ext.gfm.tables.TableHead
import org.commonmark.ext.gfm.tables.TableRow
import org.commonmark.node.AbstractVisitor
import org.commonmark.node.CustomNode
import org.commonmark.node.Node

@Composable
internal fun AgentMarkdown(text: String, modifier: Modifier) {
    val context = LocalContext.current
    val colors = MaterialTheme.colorScheme
    val pal = LocalPalette.current
    val density = LocalDensity.current
    val textSize = with(density) { 15.sp.toPx() }
    val lineSpacing = with(density) { 3.sp.toPx() }
    val renderer = remember(context, pal.primary2, pal.surfaceVariant, density) {
        agentMarkdownRenderer(context, pal.primary2.toArgb(), pal.surfaceVariant.toArgb())
    }
    val rendered = remember(renderer, text) { renderer.toMarkdown(text) }
    AndroidView(
        modifier = modifier,
        factory = { TextView(it).apply {
            setTextIsSelectable(true)
            movementMethod = TableAwareMovementMethod.create()
            includeFontPadding = false
        } },
        update = { view ->
            view.setTextColor(colors.onSurface.toArgb())
            view.setLinkTextColor(pal.primary2.toArgb())
            view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, textSize)
            view.setLineSpacing(lineSpacing, 1f)
            setAgentMarkdown(view, renderer, rendered)
        },
    )
}

internal fun agentMarkdownRenderer(context: Context, linkColor: Int, codeBackground: Int): Markwon =
    Markwon.builder(context)
        .usePlugin(StrikethroughPlugin.create())
        .usePlugin(TablePlugin.create(context))
        .usePlugin(TaskListPlugin.create(context))
        .usePlugin(object : AbstractMarkwonPlugin() {
            override fun configureParser(builder: Parser.Builder) {
                builder.extensions(listOf(AutolinkExtension.create()))
            }

            override fun afterRender(node: Node, visitor: MarkwonVisitor) {
                val rows = mutableListOf<String>()
                node.accept(object : AbstractVisitor() {
                    override fun visit(customNode: CustomNode) {
                        if (customNode is TableHead || customNode is TableRow) {
                            val cells = generateSequence(customNode.firstChild) { it.next }.filterIsInstance<TableCell>().toList()
                            if (cells.isNotEmpty()) rows += cells.joinToString(" | ") { markdownCellText(it) }
                            else visitChildren(customNode)
                        } else visitChildren(customNode)
                    }
                })
                if (rows.isEmpty()) return
                // Markwon keeps table cells outside the text buffer; expose them for copy and accessibility.
                val builder = visitor.builder()
                val text = SpannableStringBuilder(builder.removeFromEnd(0))
                val spans = text.getSpans(0, text.length, TableRowSpan::class.java).sortedBy { text.getSpanStart(it) }
                for ((row, span) in spans.withIndex().reversed()) {
                    val start = text.getSpanStart(span)
                    val end = text.getSpanEnd(span)
                    val content = rows[row].ifEmpty { "\u00a0" }
                    text.removeSpan(span)
                    text.replace(start, end, content)
                    text.setSpan(span, start, start + content.length, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
                builder.append(text)
            }

            override fun configureTheme(builder: MarkwonTheme.Builder) {
                builder.linkColor(linkColor).codeBackgroundColor(codeBackground)
            }

            override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                builder.linkResolver { view, link ->
                    val intent = agentBrowserIntent(link) ?: return@linkResolver
                    try {
                        view.context.startActivity(intent)
                    } catch (_: android.content.ActivityNotFoundException) {
                        Toast.makeText(view.context, R.string.agent_browser_unavailable, Toast.LENGTH_SHORT).show()
                    }
                }
            }
        })
        .build()

private fun markdownCellText(node: Node): String = buildString {
    node.accept(object : AbstractVisitor() {
        override fun visit(text: org.commonmark.node.Text) { append(text.literal) }
        override fun visit(code: org.commonmark.node.Code) { append(code.literal) }
        override fun visit(lineBreak: org.commonmark.node.SoftLineBreak) { append(' ') }
        override fun visit(lineBreak: org.commonmark.node.HardLineBreak) { append('\n') }
        override fun visit(html: org.commonmark.node.HtmlInline) { append(html.literal) }
    })
}

internal fun setAgentMarkdown(view: TextView, renderer: Markwon, rendered: Spanned) {
    if (view.tag === rendered) return
    val start = view.selectionStart
    val end = view.selectionEnd
    val sameText = view.text.toString() == rendered.toString()
    renderer.setParsedMarkdown(view, rendered)
    view.tag = rendered
    if (sameText && start in 0..rendered.length && end in 0..rendered.length) {
        (view.text as? Spannable)?.let { Selection.setSelection(it, start, end) }
    }
}

internal fun agentBrowserIntent(link: String): Intent? {
    val uri = Uri.parse(link)
    if (uri.scheme?.lowercase() !in listOf("http", "https") || uri.host.isNullOrBlank()) return null
    return Intent(Intent.ACTION_VIEW, uri).apply {
        addCategory(Intent.CATEGORY_BROWSABLE)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        selector = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_BROWSER)
    }
}
