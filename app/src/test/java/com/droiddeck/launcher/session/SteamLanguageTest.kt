package com.droiddeck.launcher.session

import java.util.Locale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamLanguageTest {
    private fun steam(tag: String, region: String? = null) = SteamLanguage.forLocale(Locale.forLanguageTag(tag), region)

    @Test fun theAppsLanguagesMapToSteams() {
        assertEquals("english", steam("en"))
        assertEquals("spanish", steam("es"))
        assertEquals("japanese", steam("ja"))
        assertEquals("koreana", steam("ko"))
        assertEquals("schinese", steam("zh-CN"))
        assertEquals("tchinese", steam("zh-TW"))
        assertEquals("tchinese", steam("zh-HK"))
    }

    @Test fun chineseFollowsTheScriptBeforeTheRegion() {
        assertEquals("tchinese", steam("zh-Hant-CN"))
        assertEquals("schinese", steam("zh-Hans-HK"))
        assertEquals("schinese", steam("zh"))
        assertEquals("schinese", steam("zh-SG"))
        assertEquals("tchinese", steam("zh-MO"))
    }

    @Test fun spanishInTheAmericasIsLatinAmerican() {
        assertEquals("latam", steam("es-MX"))
        assertEquals("latam", steam("es-419"))
        assertEquals("spanish", steam("es-ES"))
        // The app's Spanish has no region; the system's says where the player is.
        assertEquals("latam", steam("es", region = "AR"))
        assertEquals("spanish", steam("es", region = "ES"))
    }

    @Test fun systemLanguagesTheAppDoesNotShipStillReachSteam() {
        assertEquals("german", steam("de-DE"))
        assertEquals("brazilian", steam("pt-BR"))
        assertEquals("portuguese", steam("pt-PT"))
        assertEquals("norwegian", steam("nb-NO"))
        assertEquals("indonesian", steam("in-ID"))
        assertEquals("ukrainian", steam("uk-UA"))
    }

    @Test fun anythingElseIsEnglish() {
        assertEquals("english", steam("sw-KE"))
        assertEquals("english", steam("und"))
    }

    @Test fun everyAnswerIsALanguageSteamHas() {
        Locale.getAvailableLocales().forEach { assertTrue(it.toLanguageTag(), SteamLanguage.forLocale(it) in SteamLanguage.all) }
    }
}
