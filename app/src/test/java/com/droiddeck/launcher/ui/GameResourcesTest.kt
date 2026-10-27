package com.droiddeck.launcher.ui

import com.droiddeck.launcher.R
import com.droiddeck.launcher.frontend.Library
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.URI
import java.net.URLDecoder

class GameResourcesTest {
    @Test fun searchesPreserveUnicodeAndEscapeQueryDelimiters() {
        val title = "Ōkami HD & friends + 100% #1?"
        val links = gameResources(" $title ", null)
        assertEquals(3, links.size)
        links.forEach { link ->
            val uri = URI(link.url)
            assertEquals("https", uri.scheme)
            assertEquals(null, uri.fragment)
            assertEquals(title, URLDecoder.decode(uri.rawQuery.substringAfter('='), "UTF-8"))
        }
    }

    @Test fun onlySteamClientGamesGetAppSpecificSteamResources() {
        val steam = Library.SteamGame(292030, "The Witcher 3", null, "/steam")
        val links = gameResources(steam.name, steam)
        assertEquals("https://www.protondb.com/app/292030", links.single { it.label == R.string.game_resource_protondb }.url)
        assertEquals("https://store.steampowered.com/account/remotestorageapp/?appid=292030", links.single { it.label == R.string.game_resource_steam_cloud }.url)
        val others = listOf(
            Library.SteamGame(292030, steam.name, null, Library.ADDED),
            Library.SteamGame(292030, steam.name, null, Library.ADDED, source = "gog", storeId = "292030"),
            Library.SteamGame(292030, steam.name, null, Library.XBOX_CLOUD, source = "xbox"),
            Library.SteamGame(-1, steam.name, null, "/steam"),
        )
        others.forEach { game ->
            assertEquals(3, gameResources(game.name, game).size)
        }
    }

    @Test fun loadingPagesHaveNoResourcesUntilTheyHaveATitle() {
        assertTrue(gameResources("  ", null).isEmpty())
    }
}
