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
    fun searchesStayOnTheSitesAndSkipGameFaqsWhenItHasAPage() {
        val youtube = GameLinks.searches("Arcade Paradise", emptyList()).first { it.id == "youtube" }.url
        assertEquals("https://m.youtube.com/results?search_query=Arcade+Paradise+walkthrough", youtube)
        assertTrue(GameLinks.searches("Bully", emptyList()).none { "duckduckgo" in it.url })
        val faqs = GameLinks.Link(GameLinks.Kind.GAMEFAQS, "https://gamefaqs.gamespot.com/-/1-")
        assertEquals(listOf("youtube"), GameLinks.searches("Bully", listOf(faqs)).map { it.id })
    }

    @Test
    fun slugsTryTheFullNameThenTheShortOne() {
        assertEquals(listOf("bully-scholarship-edition", "bully"), GameLinks.slugs("Bully: Scholarship Edition"))
        assertEquals(listOf("sonic-mania"), GameLinks.slugs("Sonic Mania"))
        assertEquals(listOf("tom-clancys-the-division-2"), GameLinks.slugs("Tom Clancy’s The Division 2"))
        assertEquals(listOf("hotline-miami"), GameLinks.slugs("Hotline Miami™"))
    }

    @Test
    fun directPagesComeFromTheSitesOwnRoutes() {
        val live = setOf("https://mapgenie.io/bully", "https://www.ign.com/wikis/bully", "https://bully.fandom.com/api.php?action=query&meta=siteinfo&format=json")
        val found = GameLinks.direct("Bully: Scholarship Edition",
            listOf(GameLinks.Link(GameLinks.Kind.IGN, "https://www.ign.com/games/bully-scholarship-edition")),
            exists = { it in live }, strategyWiki = { if (it == "Bully") "https://strategywiki.org/wiki/Bully" else null })
        assertEquals(mapOf(
            GameLinks.Kind.MAPGENIE to "https://mapgenie.io/bully",
            GameLinks.Kind.IGN to "https://www.ign.com/wikis/bully",
            GameLinks.Kind.WIKI to "https://bully.fandom.com/",
            GameLinks.Kind.STRATEGYWIKI to "https://strategywiki.org/wiki/Bully",
        ), found.associate { it.kind to it.url })
        assertTrue(found.none { "duckduckgo" in it.url })
    }

    @Test
    fun aMissingSiteLeavesWikidataLinksAlone() {
        val ign = GameLinks.Link(GameLinks.Kind.IGN, "https://www.ign.com/games/x")
        assertEquals(listOf(ign), GameLinks.direct("X", listOf(ign), exists = { false }, strategyWiki = { null }))
    }

    @Test
    fun openSearchPicksTheExactTitle() {
        val json = """["Bully",["Bully's Sporting Darts","Bully"],["",""],["https://strategywiki.org/wiki/Bully%27s_Sporting_Darts","https://strategywiki.org/wiki/Bully"]]"""
        assertEquals("https://strategywiki.org/wiki/Bully", GameLinks.parseOpenSearch(json, "Bully"))
        assertNull(GameLinks.parseOpenSearch(json, "Canis Canem Edit"))
    }

    @Test
    fun cachedDiscoveriesYieldToNewWikidataLinks() {
        val fandom = GameLinks.Link(GameLinks.Kind.WIKI, "https://bully.fandom.com/")
        val map = GameLinks.Link(GameLinks.Kind.MAPGENIE, "https://mapgenie.io/bully")
        val wiki = GameLinks.Link(GameLinks.Kind.WIKI, "https://bully.wiki.gg/")
        assertEquals(listOf(wiki, map), GameLinks.merge(listOf(wiki), listOf(fandom, map)))
        assertEquals(listOf(fandom, map), GameLinks.merge(emptyList(), listOf(fandom, map)))
    }

    @Test
    fun cachedLinksRoundTrip() {
        val links = listOf(GameLinks.Link(GameLinks.Kind.MAPGENIE, "https://mapgenie.io/bully"))
        assertEquals(links, GameLinks.readLinks(GameLinks.writeLinks(links)))
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
