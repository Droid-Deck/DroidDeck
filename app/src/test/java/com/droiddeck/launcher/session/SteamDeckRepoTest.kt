package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamDeckRepoTest {
    private val sample = """
        {"posts":[
          {"id":"p1","title":"Cool","content":"c","user":{"steam_name":"Ann"},"thumbnail":"https://x/t.png","video":"https://x/v.webm",
           "video_duration":7,"likes":3,"downloads":9,"devices":["steam_deck"],"url":"https://steamdeckrepo.com/post/p1"},
          {"id":"p2","title":"","video":"http://insecure/v.webm"},
          {"id":"","video":"https://x/v.webm"},
          {"id":"p3","video":"https://x/v3.webm","thumbnail":"ftp://bad"}
        ],"currentSort":"trending"}
    """.trimIndent()

    @Test fun parsesPostsAndSkipsBrokenOnes() {
        val posts = SteamDeckRepo.parse(sample)!!
        assertEquals(listOf("p1", "p3"), posts.map { it.id })
        val first = posts[0]
        assertEquals("Cool", first.title)
        assertEquals("Ann", first.author)
        assertEquals(7, first.seconds)
        assertEquals(listOf("steam_deck"), first.devices)
        assertEquals("p3", posts[1].title)
        assertEquals("", posts[1].thumbnail)
    }

    @Test fun notJsonIsNotAPage() {
        assertNull(SteamDeckRepo.parse("<html>"))
        assertNull(SteamDeckRepo.parse("{}"))
    }

    @Test fun buildsTheSameAddressesAsTheWebsite() {
        assertEquals("https://steamdeckrepo.com/api/posts?sort=trending", SteamDeckRepo.url(SteamDeckRepo.Sort.TRENDING, null, ""))
        assertEquals(
            "https://steamdeckrepo.com/api/posts?sort=downloads-desc&device=steam_machine&search=a%20b%26c&page=3",
            SteamDeckRepo.url(SteamDeckRepo.Sort.DOWNLOADS, "steam_machine", " a b&c ", 3),
        )
    }

    @Test fun readsAreCutOffAtTheLimit() {
        assertEquals(10, SteamDeckRepo.readLimited(ByteArray(10).inputStream(), 10)!!.size)
        assertNull(SteamDeckRepo.readLimited(ByteArray(11).inputStream(), 10))
    }

    @Test fun jsonNullsAreEmptyTextNotTheWordNull() {
        val posts = SteamDeckRepo.parse(
            """{"posts":[{"id":"p9","title":null,"content":null,"user":{"steam_name":null},"thumbnail":null,"video":"https://x/v.webm","url":null}]}""",
        )!!
        val post = posts.single()
        assertEquals("p9", post.title)
        assertEquals("", post.content)
        assertEquals("", post.author)
        assertEquals("", post.thumbnail)
        assertEquals("", post.url)
        assertTrue(SteamDeckRepo.parse("""{"posts":[{"id":null,"video":"https://x/v.webm"}]}""")!!.isEmpty())
    }

    @Test fun aPageHoldsTwelveMovies() {
        assertEquals(12, SteamDeckRepo.PAGE_SIZE)
    }

    private fun lastPageOf(pages: Int, tail: Int = SteamDeckRepo.PAGE_SIZE) = SteamDeckRepo.lastPage(SteamDeckRepo.Sort.TRENDING, null, "") { p ->
        when {
            p < pages -> SteamDeckRepo.PAGE_SIZE
            p == pages -> tail
            else -> 0
        }
    }

    @Test fun theLastPageIsFoundByAskingForPages() {
        for (pages in listOf(2, 3, 7, 8, 9, 33, 100, 500)) assertEquals(pages, lastPageOf(pages))
    }

    @Test fun aShortLastPageEndsTheSearchAtOnce() {
        assertEquals(1, lastPageOf(1, tail = 5))
        assertEquals(5, lastPageOf(5, tail = 3))
        assertEquals(37, lastPageOf(37, tail = 1))
    }

    @Test fun aSearchWithNothingHasOnePageAndAnUnreachableSiteHasNone() {
        assertEquals(1, SteamDeckRepo.lastPage(SteamDeckRepo.Sort.TRENDING, null, "x") { 0 })
        assertNull(SteamDeckRepo.lastPage(SteamDeckRepo.Sort.TRENDING, null, "x") { null })
        assertNull(SteamDeckRepo.lastPage(SteamDeckRepo.Sort.TRENDING, null, "x") { p -> if (p < 4) SteamDeckRepo.PAGE_SIZE else null })
    }
}
