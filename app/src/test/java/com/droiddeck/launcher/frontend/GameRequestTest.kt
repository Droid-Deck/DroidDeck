package com.droiddeck.launcher.frontend

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameRequestTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun game(id: Long, name: String, source: String = Library.SOURCE_STEAM, storeId: String? = null, files: File? = null,
                     library: String = "internal", appId: Int = id.toInt()) =
        Library.SteamGame(appId, name, null, library, id, gameFiles = files, source = source, storeId = storeId)

    @Test fun steamIdsMatchTheGameIdOrAnAddedGamesSteamAppId() {
        val portal = game(400, "Portal")
        val added = game(-1L, "Celeste", library = Library.ADDED, source = Library.ADDED, appId = 504230)
        val games = listOf(portal, added)
        assertSame(portal, GameRequest("400").find(games))
        assertSame(added, GameRequest("18446744073709551615").find(games))
        assertSame(added, GameRequest("504230").find(games))
        assertNull(GameRequest("401").find(games))
    }

    @Test fun titleMatchingWaitsForTheFullScan() {
        val cached = game(-2L, "Example", source = "gog", storeId = "111")
        assertNull(GameRequest("222", "gog", "Example").find(listOf(cached), byTitle = false))
        assertSame(cached, GameRequest("222", "gog", "Example").find(listOf(cached)))
        assertSame(cached, GameRequest("111", "gog", "Other").find(listOf(cached), byTitle = false))
    }

    @Test fun gogMatchesItsProductIdThenTitle() {
        val witcher = game(-2L, "The Witcher 3: Wild Hunt", source = "gog", storeId = "1207664663")
        val games = listOf(game(292030, "The Witcher 3: Wild Hunt"), witcher)
        assertSame(witcher, GameRequest("1207664663", "gog").find(games))
        assertSame(witcher, GameRequest("5", "gog", "The Witcher 3 Wild Hunt").find(games))
    }

    @Test fun storesWithAppLocalIdsMatchByTitleWithinTheirStore() {
        val hades = game(-3L, "Hades II", source = "epic", storeId = "Ixion")
        val custom = game(-4L, "Cave Story+", library = Library.ADDED, source = Library.ADDED)
        val games = listOf(game(1145350, "Hades II"), hades, custom)
        assertSame(hades, GameRequest("17", "epic", "hades ii").find(games))
        assertSame(custom, GameRequest("9", Library.ADDED, "Cave Story").find(games))
        assertNull(GameRequest("17", "epic").find(games))
        assertNull(GameRequest("17", "amazon", "Hades II").find(games))
    }

    @Test fun programsStartTheDeepestGameThatOwnsThem() {
        val root = tmp.newFolder("Games")
        val suite = File(root, "Suite").apply { mkdirs() }
        val inner = File(suite, "Episode 2").apply { mkdirs() }
        val outer = game(-5L, "Suite", files = suite)
        val episode = game(-6L, "Episode 2", files = inner)
        val games = listOf(outer, episode)
        assertSame(episode, GameRequest(program = File(inner, "bin/game.exe")).find(games))
        assertSame(outer, GameRequest(program = File(suite, "launcher.exe")).find(games))
        assertNull(GameRequest(program = File(root, "Other/other.exe")).find(games))
        assertNull(GameRequest(program = File(root, "Suite2/game.exe")).find(games))
    }
}
