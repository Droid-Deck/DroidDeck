package com.droiddeck.launcher.ui

import android.content.Intent
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
