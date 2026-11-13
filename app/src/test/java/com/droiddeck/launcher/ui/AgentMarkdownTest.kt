package com.droiddeck.launcher.ui

import android.content.Intent
import android.content.ClipboardManager
import android.text.Selection
import android.text.Spannable
import io.noties.markwon.ext.tables.TableRowSpan
import android.graphics.Color
import android.text.Spanned
import android.text.style.ClickableSpan
import android.widget.TextView
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.ConscryptMode
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@ConscryptMode(ConscryptMode.Mode.OFF)
class AgentMarkdownTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val renderer get() = agentMarkdownRenderer(context, Color.BLUE, Color.DKGRAY)
    private fun Spanned.links() = getSpans(0, length, ClickableSpan::class.java).toList()

    @Test fun namedPullRequestLinksKeepTheirLabelsAndOpenAnExternalBrowser() {
        val text = renderer.toMarkdown("Created [**PR #2540**](https://github.com/Droid-Deck/DroidDeck/pull/2540).")
        assertEquals("Created PR #2540.", text.toString())
        val link = text.links().single()
        assertEquals("PR #2540", text.subSequence(text.getSpanStart(link), text.getSpanEnd(link)).toString())
        link.onClick(TextView(context))
        val intent = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("https://github.com/Droid-Deck/DroidDeck/pull/2540", intent.dataString)
        val browser = requireNotNull(intent.selector)
        assertEquals(Intent.ACTION_MAIN, browser.action)
        assertTrue(browser.hasCategory(Intent.CATEGORY_APP_BROWSER))
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test fun bareUrlsAndAutolinksAreClickableWithoutTrailingPunctuation() {
        val text = renderer.toMarkdown("Visit https://github.com/Droid-Deck/DroidDeck/pull/2540. Also <https://example.org/help>.")
        assertEquals(2, text.links().size)
        val labels = text.links().map { text.subSequence(text.getSpanStart(it), text.getSpanEnd(it)).toString() }
        assertTrue(labels.contains("https://github.com/Droid-Deck/DroidDeck/pull/2540"))
        assertTrue(labels.contains("https://example.org/help"))
    }

    @Test fun referenceLinksAndEscapedParenthesesAreParsed() {
        val text = renderer.toMarkdown("[Docs][guide] and [details](https://example.org/a\\(b\\)).\n\n[guide]: https://example.org/docs")
        assertEquals("Docs and details.", text.toString().trimEnd())
        assertEquals(2, text.links().size)
    }

    @Test fun codeStaysLiteralAndDoesNotBecomeClickable() {
        val text = renderer.toMarkdown("`[PR](https://example.org)`\n\n~~~kotlin\nval url = \"https://example.org\"\n~~~")
        assertTrue(text.toString().contains("[PR](https://example.org)"))
        assertTrue(text.toString().contains("val url = \"https://example.org\""))
        assertTrue(text.links().isEmpty())
    }

    @Test fun commonMarkdownBlocksAndNestedFormattingRenderWithoutMarkers() {
        val text = renderer.toMarkdown("# Heading\n\n> A **bold and *italic*** quote\n\n1. First\n   - Nested\n2. Second\n\n~~Removed~~\n\n- [x] Done\n- [ ] Pending")
        val result = text.toString()
        for (word in listOf("Heading", "A bold and italic quote", "First", "Nested", "Second", "Removed", "Done", "Pending")) {
            assertTrue(result, result.contains(word))
        }
        for (marker in listOf("# Heading", "**", "~~", "[x]", "[ ]")) assertFalse(result, result.contains(marker))
    }

    @Test fun tablesAreParsedWithLinksInsideCells() {
        val root = renderer.parse("| Result | Link |\n| --- | --- |\n| Ready | [PR](https://github.com/owner/repo/pull/1) |")
        assertEquals("TableBlock", root.firstChild.javaClass.simpleName)
        var links = 0
        root.accept(object : org.commonmark.node.AbstractVisitor() {
            override fun visit(link: org.commonmark.node.Link) {
                links++
                assertEquals("https://github.com/owner/repo/pull/1", link.destination)
            }
        })
        assertEquals(1, links)
        renderer.render(root)
    }

    @Test fun colorChangesPreserveSelectionAndRefreshLinkSpans() {
        val source = "Read [the PR](https://github.com/owner/repo/pull/1)"
        val view = TextView(context).apply { setTextIsSelectable(true) }
        val original = renderer
        setAgentMarkdown(view, original, original.toMarkdown(source))
        Selection.setSelection(view.text as Spannable, 5, 11)
        val updated = agentMarkdownRenderer(context, Color.RED, Color.LTGRAY)
        val rendered = updated.toMarkdown(source)
        setAgentMarkdown(view, updated, rendered)
        assertEquals(5, view.selectionStart)
        assertEquals(11, view.selectionEnd)
        assertSame(rendered, view.tag)
        val paint = android.text.TextPaint()
        (view.text as Spanned).links().single().updateDrawState(paint)
        assertEquals(Color.RED, paint.color)
    }

    @Test fun reversedSelectionsAndCursorsSurviveColorChangesButNotContentChanges() {
        val view = TextView(context).apply { setTextIsSelectable(true) }
        val original = renderer
        val updated = agentMarkdownRenderer(context, Color.RED, Color.LTGRAY)
        setAgentMarkdown(view, original, original.toMarkdown("A long reply"))
        for ((start, end) in listOf(8 to 2, 4 to 4)) {
            Selection.setSelection(view.text as Spannable, start, end)
            setAgentMarkdown(view, updated, updated.toMarkdown("A long reply"))
            assertEquals(start, view.selectionStart)
            assertEquals(end, view.selectionEnd)
        }
        setAgentMarkdown(view, updated, updated.toMarkdown("New"))
        assertFalse(view.selectionStart == 4 && view.selectionEnd == 4)
    }

    @Test fun tableCellsRemainInNativeCopyAndAccessibilityTextOnAndroid28() {
        val source = "Before\n\n| Result | Link |\n| --- | --- |\n| **Ready** | [PR](https://github.com/owner/repo/pull/1) |\n| Pending | `code` |\n\nAfter"
        val markwon = renderer
        val rendered = markwon.toMarkdown(source)
        val view = TextView(context).apply { setTextIsSelectable(true) }
        setAgentMarkdown(view, markwon, rendered)
        val expected = "Result | Link\nReady | PR\nPending | code"
        assertTrue(rendered.toString(), rendered.toString().contains(expected))
        assertTrue(view.createAccessibilityNodeInfo().text.toString().contains(expected))
        Selection.setSelection(view.text as Spannable, 0, view.text.length)
        assertTrue(view.onTextContextMenuItem(android.R.id.copy))
        val copied = context.getSystemService(ClipboardManager::class.java).primaryClip!!.getItemAt(0).text.toString()
        assertEquals(rendered.toString(), copied)
        val rows = rendered.getSpans(0, rendered.length, TableRowSpan::class.java)
        assertEquals(3, rows.size)
        assertTrue(rows.all { rendered.getSpanEnd(it) > rendered.getSpanStart(it) })
    }

    @Test fun tableLinksStillOpenTheBrowserAfterExposingCellText() {
        val markwon = renderer
        val rendered = markwon.toMarkdown("| Result | Link |\n| --- | --- |\n| Ready | [PR](https://github.com/owner/repo/pull/1) |")
        val view = TextView(context).apply { setTextIsSelectable(true) }
        setAgentMarkdown(view, markwon, rendered)
        view.measure(android.view.View.MeasureSpec.makeMeasureSpec(900, android.view.View.MeasureSpec.EXACTLY),
            android.view.View.MeasureSpec.makeMeasureSpec(2000, android.view.View.MeasureSpec.AT_MOST))
        view.layout(0, 0, 900, view.measuredHeight)
        val canvas = android.graphics.Canvas(android.graphics.Bitmap.createBitmap(900, 2000, android.graphics.Bitmap.Config.ARGB_8888))
        view.draw(canvas)
        val row = rendered.getSpans(0, rendered.length, TableRowSpan::class.java).maxBy { rendered.getSpanStart(it) }
        val cell = requireNotNull(row.findLayoutForHorizontalOffset(row.cellWidth() + 1))
        (cell.text as Spanned).links().single().onClick(view)
        assertEquals("https://github.com/owner/repo/pull/1", shadowOf(context).nextStartedActivity.dataString)
    }

    @Test fun multipleTablesAndEmptyCellsKeepTheirOwnTextAndRowSpans() {
        val rendered = renderer.toMarkdown("| One | Two |\n| --- | --- |\n| | X |\n\nBetween\n\n| Three | Four |\n| --- | --- |\n| Y | |")
        val text = rendered.toString()
        assertTrue(text, text.contains("One | Two\n | X"))
        assertTrue(text, text.contains("Three | Four\nY | "))
        assertTrue(text, text.contains("Between"))
        assertEquals(4, rendered.getSpans(0, rendered.length, TableRowSpan::class.java).size)
    }

    @Test fun unfinishedStreamingMarkdownDoesNotLoseTextOrCrash() {
        val source = "Read [the PR](https://github.com/owner/repo/pull/1)\n\n```kotlin\nval x = 1"
        for (end in 1..source.length) renderer.toMarkdown(source.take(end))
        assertTrue(renderer.toMarkdown(source).toString().contains("val x = 1"))
    }

    @Test fun browserIntentsOnlyAcceptAbsoluteWebLinks() {
        for (link in listOf("javascript:alert(1)", "file:///etc/passwd", "intent://open", "content://private", "/local/file", "https:")) {
            assertNull(link, agentBrowserIntent(link))
        }
        assertNotNull(agentBrowserIntent("http://example.org"))
        assertNotNull(agentBrowserIntent("https://github.com/owner/repo/pull/1?diff=split#discussion"))
    }
}
