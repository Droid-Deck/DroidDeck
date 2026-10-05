package com.droiddeck.launcher.runtime

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestFontsTest {
    private fun region(tag: String) = GuestFonts.cjkRegion(Locale.forLanguageTag(tag))

    @Test fun sharedCharactersTakeTheReadersRegionalShapes() {
        assertEquals("JP", region("ja"))
        assertEquals("KR", region("ko"))
        assertEquals("SC", region("zh-CN"))
        assertEquals("TC", region("zh-TW"))
        assertEquals("HK", region("zh-HK"))
        assertEquals("HK", region("zh-MO"))
        assertEquals("SC", region("zh-Hans-HK"))
        assertEquals("TC", region("zh-Hant"))
        assertEquals("SC", region("en"))
    }

    @Test fun latinStaysOnDejaVuWithTheRegionsCjkNext() {
        val conf = GuestFonts.preferenceConf(Locale.JAPANESE)
        val sans = conf.substringAfter("<family>sans-serif</family>").substringBefore("</alias>")
        assertTrue(sans, sans.indexOf("DejaVu Sans") in 0 until sans.indexOf("Noto Sans CJK JP"))
        assertTrue(conf.contains("<family>Noto Serif CJK JP</family>"))
        assertTrue(conf.contains("<family>Noto Sans Mono CJK JP</family>"))
        assertTrue(conf.startsWith("<?xml"))
    }
}
