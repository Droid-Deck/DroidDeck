package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.AppLanguage
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DesktopLocaleTest {
    private fun lang(tag: String) = DesktopLocale.lang(Locale.forLanguageTag(tag))

    @Test fun appLanguagesMapToGlibcLocales() {
        assertEquals("en_US.UTF-8", lang("en"))
        assertEquals("tr_TR.UTF-8", lang("tr"))
        assertEquals("pt_BR.UTF-8", lang("pt-BR"))
        assertEquals("pt_PT.UTF-8", lang("pt-PT"))
        assertEquals("es_MX.UTF-8", lang("es-419"))
        assertEquals("zh_CN.UTF-8", lang("zh-CN"))
        assertEquals("zh_TW.UTF-8", lang("zh-TW"))
        assertEquals("zh_HK.UTF-8", lang("zh-HK"))
    }

    @Test fun chineseScriptBeatsRegion() {
        assertEquals("zh_CN.UTF-8", lang("zh-Hans-HK"))
        assertEquals("zh_TW.UTF-8", lang("zh-Hant-CN"))
        assertEquals("zh_TW.UTF-8", lang("zh-MO"))
        assertEquals("zh_HK:zh_TW:zh", DesktopLocale.language(Locale.forLanguageTag("zh-HK")))
    }

    @Test fun systemRegionsSurvive() {
        assertEquals("en_GB.UTF-8", lang("en-GB"))
        assertEquals("fr_CA.UTF-8", lang("fr-CA"))
        assertEquals("de_DE.UTF-8", lang("de-XX"))
    }

    @Test fun everyAppLanguageHasALocale() {
        AppLanguage.supported.forEach { assertTrue(it, lang(it) != DesktopLocale.DEFAULT) }
    }

    @Test fun unknownLanguagesFallBack() {
        assertEquals(DesktopLocale.DEFAULT, lang("sw"))
    }

    @Test fun languageListPrefersTheRegionThenTheLanguage() {
        assertEquals("pt_BR:pt", DesktopLocale.language(Locale.forLanguageTag("pt-BR")))
        assertEquals("tr_TR:tr", DesktopLocale.language(Locale.forLanguageTag("tr")))
        assertEquals("en_US", DesktopLocale.language(Locale.forLanguageTag("en")))
        assertNull(DesktopLocale.language(Locale.forLanguageTag("sw")))
    }
}
