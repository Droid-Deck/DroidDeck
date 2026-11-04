package com.droiddeck.launcher.runtime

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLanguageTest {
    private val sha = "a".repeat(64)

    @Test fun indexListsPacksAndSkipsMalformedLines() {
        val index = DesktopLanguage.parseIndex("pt_BR $sha 1200\ntr $sha 900\nbad line\nxx short 5\n")
        assertEquals(setOf("pt_BR", "tr"), index.keys)
        assertTrue(DesktopLanguage.parseIndex("../../x $sha 5\n").isEmpty())
        assertEquals(1200L, index.getValue("pt_BR").size)
    }

    @Test fun regionPackThenBareLanguage() {
        assertEquals(listOf("pt_BR", "pt"), DesktopLanguage.candidates(Locale.forLanguageTag("pt-BR")))
        assertEquals(listOf("tr_TR", "tr"), DesktopLanguage.candidates(Locale.forLanguageTag("tr")))
        assertEquals(listOf("zh_HK", "zh_TW", "zh"), DesktopLanguage.candidates(Locale.forLanguageTag("zh-HK")))
    }

    @Test fun englishAndUnknownNeedNoPack() {
        assertTrue(DesktopLanguage.candidates(Locale.forLanguageTag("en")).isEmpty())
        assertTrue(DesktopLanguage.candidates(Locale.forLanguageTag("sw")).isEmpty())
    }

    @Test fun packsLiveBesideTheDesktopArchive() {
        assertEquals("https://h/r/t/", DesktopLanguage.baseUrl("https://h/r/t/desktop-kde.tar.zst"))
    }
}
