package com.droiddeck.launcher.core

import java.io.File
import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AppLanguageTest {
    private fun tag(languageTag: String) = AppLanguage.supportedTag(Locale.forLanguageTag(languageTag))

    private val folders = mapOf(
        "id" to "in", "ms" to "ms", "cs" to "cs", "da" to "da", "de" to "de", "es" to "es",
        "es-419" to "b+es+419", "fr" to "fr", "it" to "it", "hu" to "hu", "nl" to "nl", "nb" to "nb",
        "pl" to "pl", "pt-BR" to "pt", "pt-PT" to "pt-rPT", "ro" to "ro", "fi" to "fi", "sv" to "sv",
        "vi" to "vi", "tr" to "tr", "el" to "el", "bg" to "bg", "ru" to "ru", "uk" to "uk", "ar" to "ar",
        "hi" to "hi", "th" to "th", "ja" to "ja", "ko" to "ko", "zh-CN" to "zh-rCN", "zh-TW" to "zh-rTW",
        "zh-HK" to "zh-rHK",
    )

    @Test fun everyLanguageReadsAsItself() {
        AppLanguage.supported.forEach { assertEquals(it, tag(it)) }
    }

    @Test fun everyLanguageHasItsOwnName() {
        val names = AppLanguage.supported.map(AppLanguage::nativeName)
        assertEquals(names.size, names.toSet().size)
        AppLanguage.supported.forEach { assertTrue(it, AppLanguage.nativeName(it) != it) }
    }

    @Test fun systemLocalesFindTheirLanguage() {
        assertEquals("es-419", tag("es-MX"))
        assertEquals("es-419", tag("es-US"))
        assertEquals("es", tag("es-ES"))
        assertEquals("pt-BR", tag("pt-BR"))
        assertEquals("pt-BR", tag("pt"))
        assertEquals("pt-PT", tag("pt-PT"))
        assertEquals("pt-PT", tag("pt-AO"))
        assertEquals("id", tag("in-ID"))
        assertEquals("id", tag("id-ID"))
        assertEquals("de", tag("de-AT"))
        assertEquals("ar", tag("ar-EG"))
        assertEquals("zh-HK", tag("zh-MO"))
        assertNull(tag("sw-KE"))
        assertNull(tag("nn-NO"))
    }

    @Test fun everyLanguageShipsStringsAndIsOfferedToAndroid() {
        assertEquals(AppLanguage.supported.toSet() - "en", folders.keys)
        val res = File("src/main/res")
        folders.values.forEach { assertTrue(it, File(res, "values-$it/strings.xml").isFile) }
        val config = File(res, "xml/locales_config.xml").readText()
        val offered = Regex("""android:name="([^"]+)"""").findAll(config).map { it.groupValues[1] }.toList()
        assertEquals(AppLanguage.supported, offered)
    }
}
