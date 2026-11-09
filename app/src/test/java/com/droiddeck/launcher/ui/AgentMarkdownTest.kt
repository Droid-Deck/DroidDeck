package com.droiddeck.launcher.ui

import androidx.compose.ui.graphics.Color
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentMarkdownTest {
    @Test fun blocksSplitHeadingsBulletsAndCode() {
        val blocks = markdownBlocks("## Done\n\n- one\n* two\n```kotlin\nval a = 1\n\nval b = 2\n```\nplain")
        assertEquals(
            listOf(
                MdBlock.Line("Done", heading = true),
                MdBlock.Line("•  one", bullet = true),
                MdBlock.Line("•  two", bullet = true),
                MdBlock.Code("val a = 1\n\nval b = 2"),
                MdBlock.Line("plain"),
            ),
            blocks,
        )
    }

    @Test fun inlineMarkersAreDroppedAndUnclosedOnesKept() {
        val text = inlineMarkdown("a **b** `c` d ** e ` f", Color.Red, Color.Black)
        assertEquals("a b c d ** e ` f", text.text)
        assertEquals(2, text.spanStyles.size)
    }
}
