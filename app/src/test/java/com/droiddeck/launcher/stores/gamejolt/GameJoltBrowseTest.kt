package com.droiddeck.launcher.stores.gamejolt

import org.junit.Assert.assertEquals
import org.junit.Test

class GameJoltBrowseTest {
    private fun qs(query: GameJoltQuery = GameJoltQuery(), page: Int = 1) = GameJoltBrowse.queryString(query, page)

    @Test fun theDefaultQueryAsksForTheFeaturedSectionByName() {
        assertEquals("section=featured", qs())
    }

    @Test fun sectionsPriceAndPagesUseTheSiteNames() {
        assertEquals("section=hot", qs(GameJoltQuery(section = GameJoltBrowse.SECTION_HOT)))
        assertEquals("section=featured&f_price=5-less&page=3", qs(GameJoltQuery(price = GameJoltBrowse.PRICE_UNDER_5), 3))
        assertEquals("section=featured&f_price=free", qs(GameJoltQuery(price = GameJoltBrowse.PRICE_FREE)))
    }

    @Test fun listFiltersKeepTheSiteOrderWhateverTheSelectionOrder() {
        assertEquals("section=featured&f_os%5B%5D=windows&f_os%5B%5D=linux", qs(GameJoltQuery(os = setOf("linux", "windows"))))
        assertEquals("section=featured&f_browser%5B%5D=html&f_maturity%5B%5D=everyone&f_status%5B%5D=complete&f_partners%5B%5D=partners",
            qs(GameJoltQuery(browser = setOf("html"), maturity = setOf("everyone"), status = setOf("complete"), partners = true)))
    }

    @Test fun tagsAreSluggedAndRepeatable() {
        assertEquals("co-op", GameJoltBrowse.tagSlug("  Co-Op "))
        assertEquals("section=featured&tag=action&tag=co-op", qs(GameJoltQuery(tags = listOf("Action", "co op"))))
    }
}
