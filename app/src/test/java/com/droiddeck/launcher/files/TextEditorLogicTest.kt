package com.droiddeck.launcher.files

import java.nio.charset.Charset
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TextEditorLogicTest {

    @Test
    fun nulByteMarksBinary() {
        val clean = "hello world".toByteArray()
        assertFalse(editorIsBinary(clean))
        val withNul = byteArrayOf(1, 2, 0, 3)
        assertTrue(editorIsBinary(withNul))
    }

    @Test
    fun binaryScanStopsAtSampleLimit() {
        val bytes = ByteArray(9000) { if (it == 8999) 0 else 65 }
        assertFalse(editorIsBinary(bytes))
        assertTrue(editorIsBinary(bytes, sample = 9000))
    }

    @Test
    fun utf8TextDecodesAsUtf8() {
        val decoded = decodeEditorText("你好, world".toByteArray(Charsets.UTF_8))
        assertEquals(EditorCharset.UTF8, decoded.charset)
        assertFalse(decoded.lossy)
        assertEquals("你好, world", decoded.text)
    }

    @Test
    fun gbkBytesFallBackToGb18030() {
        val gbk = "你好".toByteArray(Charset.forName("GBK"))
        val decoded = decodeEditorText(gbk)
        assertEquals(EditorCharset.GB18030, decoded.charset)
        assertFalse(decoded.lossy)
        assertEquals("你好", decoded.text)
    }

    @Test
    fun undecodableBytesStayLossyUtf8() {
        val decoded = decodeEditorText(byteArrayOf(0xFF.toByte()))
        assertEquals(EditorCharset.UTF8, decoded.charset)
        assertTrue(decoded.lossy)
    }

    @Test
    fun tabsExpandToTabStops() {
        assertEquals("ab  c", expandEditorTabs("ab\tc"))
        assertEquals("    x", expandEditorTabs("\tx"))
        assertEquals("abcd    e", expandEditorTabs("abcd\te"))
        assertEquals("abc def g", expandEditorTabs("abc\tdef\tg"))
        assertEquals("a   \n    b", expandEditorTabs("a\t\n\tb"))
        assertEquals("plain text", expandEditorTabs("plain text"))
    }

    @Test
    fun lineCountCountsTrailingEmptyLine() {
        assertEquals(1, lineCountOf(""))
        assertEquals(1, lineCountOf("abc"))
        assertEquals(2, lineCountOf("a\nb"))
        assertEquals(2, lineCountOf("a\n"))
    }

    @Test
    fun offsetMapsToZeroBasedLineAndOneBasedColumn() {
        val text = "ab\ncd"
        assertEquals(0, lineIndexOfOffset(text, 0))
        assertEquals(0, lineIndexOfOffset(text, 2))
        assertEquals(1, lineIndexOfOffset(text, 3))
        assertEquals(1, lineIndexOfOffset(text, 5))
        assertEquals(1, columnOfOffset(text, 0))
        assertEquals(3, columnOfOffset(text, 2))
        assertEquals(2, columnOfOffset(text, 4))
        assertEquals(3, columnOfOffset(text, 5))
    }

    @Test
    fun findMatchesHonorsCaseAndLimit() {
        val text = "abcABCabc"
        val all = findEditorMatches(text, "abc", matchCase = false)
        assertEquals(listOf(0..2, 3..5, 6..8), all)
        val exact = findEditorMatches(text, "abc", matchCase = true)
        assertEquals(listOf(0..2, 6..8), exact)
        assertEquals(2, findEditorMatches(text, "abc", matchCase = false, limit = 2).size)
        assertEquals(emptyList<IntRange>(), findEditorMatches(text, ""))
        assertEquals(listOf(0..1), findEditorMatches("aaa", "aa"))
    }

    @Test
    fun replaceSpansEveryMatch() {
        val text = "one two one"
        val matches = findEditorMatches(text, "one", matchCase = true)
        assertEquals("1 two 1", replaceEditorMatches(text, matches, "1"))
        assertEquals(text, replaceEditorMatches(text, emptyList(), "x"))
    }
}
