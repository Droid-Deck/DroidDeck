package com.droiddeck.launcher.stores.gog

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/** The account listing: everything the tab needs to draw a library before a product is asked about. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GogListingTest {
    @Test
    fun aPageCarriesTheGamesTheAppCanInstall() {
        val page = GogLibrary.parseListing(PAGE)!!
        assertEquals(2, page.totalPages)
        assertEquals(listOf("Shadowrun Returns", "Witcher 3: Wild Hunt, The"), page.games.map { it.title })
        val shadowrun = page.games[0]
        assertEquals("1207660413", shadowrun.gameId)
        assertEquals("https://images-2.gog.com/6c35ecb988f57725cc0f385acf860241082da16eda9fab66115f4da883dae3d1_glx_vertical_cover.webp", shadowrun.verticalCover)
        assertEquals("", shadowrun.imageUrl)
        assertEquals("Role-playing", shadowrun.category)
        assertEquals("2013-07-25", shadowrun.releaseDate)
        assertEquals("", shadowrun.description)
    }

    @Test
    fun moviesOtherPlatformsAndGalaxysOwnEntryAreLeftOut() {
        assertEquals(listOf("1207660413", "1207664663"), GogLibrary.parseListing(PAGE)!!.games.map { it.gameId })
    }

    @Test
    fun aResponseThatIsNotAListingIsNull() {
        assertNull(GogLibrary.parseListing("""{"error":"unauthorized"}"""))
        assertNull(GogLibrary.parseListing("not json"))
    }

    private companion object {
        val PAGE = """
            {
              "sortBy": "title",
              "page": 1,
              "totalProducts": 6,
              "totalPages": 2,
              "productsPerPage": 100,
              "products": [
                {
                  "id": 1207660413,
                  "title": "Shadowrun Returns",
                  "image": "//images-2.gog.com/6c35ecb988f57725cc0f385acf860241082da16eda9fab66115f4da883dae3d1",
                  "worksOn": { "Windows": true, "Mac": true, "Linux": true },
                  "category": "Role-playing",
                  "isComingSoon": false,
                  "isMovie": false,
                  "isGame": true,
                  "slug": "shadowrun_returns",
                  "dlcCount": 0,
                  "releaseDate": { "date": "2013-07-25 00:00:00.000000", "timezone_type": 3, "timezone": "Europe/Nicosia" }
                },
                {
                  "id": 1207664663,
                  "title": "  Witcher 3: Wild Hunt, The  ",
                  "image": "//images-3.gog.com/60c724a052275a049d857d53957dc38e9347742f52372bb956d992b43efa8fb5",
                  "worksOn": { "Windows": true, "Mac": false, "Linux": false },
                  "category": "Role-playing",
                  "isMovie": false,
                  "isGame": true,
                  "releaseDate": { "date": "2015-05-19 02:00:00.000000", "timezone_type": 3, "timezone": "Europe/Nicosia" }
                },
                { "id": 1582718039, "title": "A Movie", "image": "//images-1.gog.com/movie", "isMovie": true, "isGame": false },
                { "id": 1430747193, "title": "Mac Only", "image": "//images-1.gog.com/mac", "worksOn": { "Windows": false, "Mac": true }, "isMovie": false, "isGame": true },
                { "id": 1801418160, "title": "GOG Galaxy", "image": "//images-1.gog.com/galaxy", "worksOn": { "Windows": true }, "isMovie": false, "isGame": true },
                { "id": 1401362385, "title": "Soundtrack", "image": "//images-1.gog.com/ost", "worksOn": { "Windows": true }, "isMovie": false, "isGame": false }
              ]
            }
        """.trimIndent()
    }
}
