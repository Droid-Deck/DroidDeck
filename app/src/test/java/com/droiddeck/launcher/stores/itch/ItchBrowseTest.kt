package com.droiddeck.launcher.stores.itch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ItchBrowseTest {
    private fun path(sort: String = ItchBrowse.SORT_POPULAR, price: String = ItchBrowse.PRICE_ANY, platforms: Set<String> = emptySet(), tags: List<String> = emptyList()) =
        ItchBrowse.path(BrowseQuery(sort, price, platforms, tags))

    @Test fun theDefaultQueryIsTheFrontPage() {
        assertTrue(BrowseQuery().isDefault)
        assertFalse(BrowseQuery(sort = ItchBrowse.SORT_NEWEST).isDefault)
        assertFalse(BrowseQuery(price = ItchBrowse.PRICE_FREE).isDefault)
    }

    @Test fun sortsUseItchsOwnPaths() {
        assertEquals("https://itch.io/games", path())
        assertEquals("https://itch.io/games/new-and-popular", path(sort = ItchBrowse.SORT_NEW_POPULAR))
        assertEquals("https://itch.io/games/top-sellers", path(sort = ItchBrowse.SORT_TOP_SELLERS))
        assertEquals("https://itch.io/games/top-rated", path(sort = ItchBrowse.SORT_TOP_RATED))
        assertEquals("https://itch.io/games/newest", path(sort = ItchBrowse.SORT_NEWEST))
    }

    @Test fun pricesUseItchsOwnPaths() {
        assertEquals("https://itch.io/games/free", path(price = ItchBrowse.PRICE_FREE))
        assertEquals("https://itch.io/games/on-sale", path(price = ItchBrowse.PRICE_SALE))
        assertEquals("https://itch.io/games/store", path(price = ItchBrowse.PRICE_PAID))
        assertEquals("https://itch.io/games/5-dollars-or-less", path(price = ItchBrowse.PRICE_UNDER_5))
        assertEquals("https://itch.io/games/15-dollars-or-less", path(price = ItchBrowse.PRICE_UNDER_15))
    }

    @Test fun platformsUseItchsOwnNames() {
        val expected = mapOf(
            "windows" to "platform-windows", "osx" to "platform-osx", "linux" to "platform-linux", "android" to "platform-android",
            "ios" to "platform-ios", "web" to "platform-web", "mobile-web" to "platform-mobile-web",
        )
        assertEquals(expected.keys.toList(), ItchBrowse.platformNames)
        for ((name, segment) in expected) assertEquals("https://itch.io/games/$segment", path(platforms = setOf(name)))
    }

    @Test fun aSortAPriceAndAPlatformCombineInThatOrder() {
        assertEquals(
            "https://itch.io/games/top-rated/free/platform-windows",
            path(ItchBrowse.SORT_TOP_RATED, ItchBrowse.PRICE_FREE, setOf("windows")),
        )
    }

    @Test fun aTagIsItsLowercaseNameWithHyphens() {
        assertEquals("https://itch.io/games/tag-pixel-art", path(tags = listOf("Pixel Art")))
        assertEquals("pixel-art", ItchBrowse.tagSlug(" Pixel Art "))
    }

    @Test fun aSortAPricePlatformAndTagCombineInThatOrder() {
        assertEquals(
            "https://itch.io/games/top-rated/free/platform-windows/tag-pixel-art.xml",
            ItchBrowse.url(BrowseQuery(ItchBrowse.SORT_TOP_RATED, ItchBrowse.PRICE_FREE, setOf("windows"), listOf("pixel-art")), 1),
        )
    }

    @Test fun severalPlatformsAndTagsAreAllInTheAddress() {
        assertEquals(
            "https://itch.io/games/platform-windows/platform-linux/tag-pixel-art/tag-2d",
            path(platforms = setOf("linux", "windows"), tags = listOf("pixel-art", "2d")),
        )
    }

    @Test fun aTypedTagBecomesItsSlug() {
        assertEquals("visual-novel", ItchBrowse.tagSlug("Visual  Novel!"))
        assertEquals("", ItchBrowse.tagSlug("  !! "))
    }

    @Test fun theSamePlatformsInAnyOrderGiveTheSameAddress() {
        assertEquals(path(platforms = setOf("web", "linux")), path(platforms = setOf("linux", "web")))
    }

    @Test fun laterPagesAskForThePageNumberAndTheFirstDoesNot() {
        val query = BrowseQuery(sort = ItchBrowse.SORT_TOP_RATED)
        assertEquals("https://itch.io/games/top-rated.xml", ItchBrowse.url(query, 1))
        assertEquals("https://itch.io/games/top-rated.xml?page=2", ItchBrowse.url(query, 2))
    }
}
