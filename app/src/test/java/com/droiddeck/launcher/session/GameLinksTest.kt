package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GameLinksTest {
    private fun row(vararg pairs: Pair<String, String>) =
        pairs.joinToString(",", "{", "}") { (k, v) -> "\"$k\":{\"type\":\"literal\",\"value\":\"$v\"}" }

    @Test
    fun mapsWikidataIdsToSiteUrls() {
        val json = """{"results":{"bindings":[
            ${row("fandom" to "it.eldenring", "pcgw" to "Elden_Ring", "gamefaqs" to "265984", "ign" to "elden-ring", "hltb" to "68151", "sw" to "Elden Ring")},
            ${row("fandom" to "eldenring", "pcgw" to "Elden_Ring", "gamefaqs" to "323053")}
        ]}}"""
        val links = GameLinks.parse(json).associate { it.kind to it.url }
        assertEquals("https://eldenring.fandom.com/", links[GameLinks.Kind.WIKI])
        assertEquals("https://strategywiki.org/wiki/Elden_Ring", links[GameLinks.Kind.STRATEGYWIKI])
        assertEquals("https://www.pcgamingwiki.com/wiki/Elden_Ring", links[GameLinks.Kind.PCGAMINGWIKI])
        assertEquals("https://gamefaqs.gamespot.com/-/265984-", links[GameLinks.Kind.GAMEFAQS])
        assertEquals("https://www.ign.com/games/elden-ring", links[GameLinks.Kind.IGN])
        assertEquals("https://howlongtobeat.com/game/68151", links[GameLinks.Kind.HOWLONGTOBEAT])
    }

    @Test
    fun aLanguageOnlyFandomWikiKeepsItsLanguage() {
        val links = GameLinks.parse("""{"results":{"bindings":[${row("fandom" to "de.hollowknight")}]}}""")
        assertEquals("https://hollowknight.fandom.com/de/", links.single().url)
    }

    @Test
    fun noMatchIsNoLinks() {
        assertTrue(GameLinks.parse("""{"results":{"bindings":[]}}""").isEmpty())
    }

    @Test
    fun protonDbTiers() {
        assertEquals("gold", GameLinks.parseTier("""{"bestReportedTier":"platinum","tier":"gold","total":2109}"""))
        assertNull(GameLinks.parseTier("""{"tier":"pending"}"""))
    }

    @Test
    fun searchesEncodeTheName() {
        val youtube = GameLinks.searches("Arcade Paradise").first { it.id == "youtube" }.url
        assertEquals("https://m.youtube.com/results?search_query=Arcade+Paradise+walkthrough", youtube)
        assertTrue(GameLinks.mapSearches("Elden Ring").all { it.url.startsWith("https://duckduckgo.com/html/?q=site%3A") })
    }

    @Test
    fun steamGuideTagFilter() {
        assertEquals("https://steamcommunity.com/app/620/guides/?browsefilter=toprated&browsesort=toprated&requiredtags%5B%5D=Maps+or+Levels",
            SteamGuides.listUrl(620, "Maps or Levels"))
        assertEquals("https://steamcommunity.com/app/620/guides/?browsefilter=toprated&browsesort=toprated", SteamGuides.listUrl(620, null))
    }

    @Test
    fun guideLanguageFollowsTheDevice() {
        assertEquals("english", SteamGuides.language(java.util.Locale.US))
        assertEquals("german", SteamGuides.language(java.util.Locale.GERMANY))
        assertEquals("schinese", SteamGuides.language(java.util.Locale.SIMPLIFIED_CHINESE))
        assertEquals("tchinese", SteamGuides.language(java.util.Locale.TRADITIONAL_CHINESE))
        assertEquals("brazilian", SteamGuides.language(java.util.Locale("pt", "BR")))
        assertEquals("latam", SteamGuides.language(java.util.Locale("es", "MX")))
        assertEquals("spanish", SteamGuides.language(java.util.Locale("es", "ES")))
        assertEquals("english", SteamGuides.language(java.util.Locale("eo")))
        assertEquals("https://steamcommunity.com/app/620/guides/?browsefilter=toprated&browsesort=toprated&requiredtags%5B%5D=Secrets&requiredtags%5B%5D=english",
            SteamGuides.listUrl(620, "Secrets", "english"))
    }
}
