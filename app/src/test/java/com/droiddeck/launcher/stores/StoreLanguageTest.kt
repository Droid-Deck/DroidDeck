package com.droiddeck.launcher.stores

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.Locale

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class StoreLanguageTest {
    @Test
    fun polishDepotIsKeptWhenPolishIsChosen() {
        assertTrue(StoreLanguage.depotAccepted(arrayOf("pl-PL"), "pl"))
        assertTrue(StoreLanguage.depotAccepted(arrayOf("pl"), "pl-PL"))
        assertTrue(StoreLanguage.depotAccepted(arrayOf("polish"), "pl-PL"))
        assertTrue(StoreLanguage.depotAccepted(arrayOf("*"), "pl"))
        assertTrue(StoreLanguage.depotAccepted(emptyArray(), "pl"))
        assertTrue(StoreLanguage.depotAccepted(null, "pl"))
    }

    @Test
    fun englishDepotIsSkippedWhenPolishIsChosen() {
        assertFalse(StoreLanguage.depotAccepted(arrayOf("en-US"), "pl-PL"))
        assertFalse(StoreLanguage.depotAccepted(arrayOf("en", "english"), "pl"))
        assertTrue(StoreLanguage.depotAccepted(arrayOf("en-US"), "en"))
        assertTrue(StoreLanguage.depotAccepted(arrayOf("en"), "en-US"))
        assertTrue(StoreLanguage.depotAccepted(arrayOf("english"), "en-US"))
    }

    @Test
    fun pickPrefersSystemLanguageThenEnglish() {
        val available = listOf("en-US", "de-DE", "pl-PL")
        assertEquals("pl-PL", StoreLanguage.pick(available, "pl"))
        assertEquals("en-US", StoreLanguage.pick(available, "ja"))
        assertEquals("de-DE", StoreLanguage.pick(listOf("de-DE", "fr-FR"), "de"))
        assertEquals("wanted", StoreLanguage.pick(emptyList(), "wanted"))
    }

    @Test
    fun pickPrefersExactCodeBeforeTheBaseLanguage() {
        val both = listOf("pl", "pl-PL", "en-US")
        assertEquals("pl-PL", StoreLanguage.pick(both, "pl-PL"))
        assertEquals("pl", StoreLanguage.pick(both, "pl"))
        assertEquals("pl-PL", StoreLanguage.pick(listOf("en-US", "pl-PL"), "pl"))
    }

    @Test
    fun resolvedDepotKeepsSharedFilesAndOnlyTheChosenVariant() {
        assertTrue(StoreLanguage.depotMatchesResolved(arrayOf("*"), "pl-PL"))
        assertTrue(StoreLanguage.depotMatchesResolved(emptyArray(), "pl-PL"))
        assertTrue(StoreLanguage.depotMatchesResolved(null, "pl-PL"))
        assertTrue(StoreLanguage.depotMatchesResolved(arrayOf("pl-PL", "*"), "pl-PL"))
        assertTrue(StoreLanguage.depotMatchesResolved(arrayOf("pl-PL"), "pl-PL"))
        assertFalse(StoreLanguage.depotMatchesResolved(arrayOf("pl"), "pl-PL"))
        assertFalse(StoreLanguage.depotMatchesResolved(arrayOf("en-US"), "pl-PL"))
        assertTrue(StoreLanguage.depotMatchesResolved(arrayOf("polish"), "pl"))
    }

    @Test
    fun tagForFollowsLocale() {
        assertEquals("pl-PL", StoreLanguage.tagFor(Locale("pl", "PL")))
        assertEquals("pl", StoreLanguage.tagFor(Locale("pl")))
        assertEquals("pt-BR", StoreLanguage.tagFor(Locale("pt", "BR")))
        assertEquals("es-419", StoreLanguage.tagFor(Locale("es", "MX")))
        assertEquals("zh-Hans", StoreLanguage.tagFor(Locale.SIMPLIFIED_CHINESE))
        assertEquals("zh-Hant", StoreLanguage.tagFor(Locale.TRADITIONAL_CHINESE))
    }

    @Test
    fun codesFromDepotsSkipStarAndDuplicates() {
        val depots = JSONArray()
            .put(JSONObject().put("languages", JSONArray().put("*").put("en-US")))
            .put(JSONObject().put("languages", JSONArray().put("pl-PL").put("en-US")))
            .put(JSONObject().put("languages", JSONArray()))
        assertEquals(listOf("en-US", "pl-PL"), StoreLanguage.codesFromDepots(depots))
        assertTrue(StoreLanguage.codesFromDepots(null).isEmpty())
    }

    @Test
    fun selectableStoresAreGogAndEpic() {
        assertTrue(StoreLanguage.selectable(Store.GOG))
        assertTrue(StoreLanguage.selectable(Store.EPIC))
        assertFalse(StoreLanguage.selectable(Store.AMAZON))
        assertFalse(StoreLanguage.selectable(Store.XBOX))
        assertFalse(StoreLanguage.selectable(Store.ITCH))
    }
}
