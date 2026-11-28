package com.droiddeck.launcher.session

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SteamGuidesTest {
    private val page = """
        <div class="workshopBrowsePagingControls"></div>
        <div data-panel="{}" role="button" id="sharedfile_1848481525_1" class="workshopItemCollectionContainer">
            <a class="workshopItemCollection ugc" href="https://steamcommunity.com/sharedfiles/filedetails/?id=1848481525" data-appid="413150">
                <div class="workshopItemPreviewHolder ">
                    <img class="workshopItemPreviewImage" src="https://images.steamusercontent.com/ugc/1/A/?imw=200&amp;imh=200">
                </div>
                <div class="workshopItemDetails">
                    <img class="fileRating" src="https://community.akamai.steamstatic.com/public/images/sharedfiles/5-star.png?v=2" />
                    <div class="workshopItemTitle">
                        Stardew &amp; You					</div>
                    <div class="workshopItemAuthorLine">by&nbsp;<span class="workshopItemAuthorName">Farmer</span></div>
                    <div class="workshopItemShortDesc">Everything about
                    the valley...</div>
                </div>
            </a>
        </div>
        <div data-panel="{}" role="button" id="sharedfile_2_1" class="workshopItemCollectionContainer">
            <a class="workshopItemCollection ugc" href="https://steamcommunity.com/sharedfiles/filedetails/?id=2">
                <div class="workshopItemTitle"></div>
            </a>
        </div>
    """.trimIndent()

    @Test
    fun parsesGuideCards() {
        val guides = SteamGuides.parse(page)
        assertEquals(1, guides.size)
        val g = guides.single()
        assertEquals("1848481525", g.id)
        assertEquals("Stardew & You", g.title)
        assertEquals("Farmer", g.author)
        assertEquals("Everything about the valley...", g.summary)
        assertEquals("https://images.steamusercontent.com/ugc/1/A/?imw=200&imh=200", g.preview)
        assertEquals(5, g.stars)
        assertEquals("https://steamcommunity.com/sharedfiles/filedetails/?id=1848481525", g.url)
    }

    @Test
    fun remembersOnlyGuidePagesPerGame() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        assertNull(SteamGuides.place(context, "413150"))
        SteamGuides.savePlace(context, "413150", "https://example.com/", 0.5f)
        assertNull(SteamGuides.place(context, "413150"))
        SteamGuides.savePlace(context, "413150", "https://steamcommunity.com/sharedfiles/filedetails/?id=1#Section", 0.4f)
        assertEquals(0.4f, SteamGuides.place(context, "413150")!!.scroll, 0.001f)
        assertNull(SteamGuides.place(context, "620"))
        SteamGuides.clear(context, "413150")
        assertNull(SteamGuides.place(context, "413150"))
    }

    @Test
    fun guideUrls() {
        assertTrue(SteamGuides.isGuide("https://steamcommunity.com/sharedfiles/filedetails/?id=123"))
        assertFalse(SteamGuides.isGuide("https://steamcommunity.com.evil/sharedfiles/filedetails/?id=123"))
        assertFalse(SteamGuides.isGuide("http://steamcommunity.com/sharedfiles/filedetails/?id=123"))
    }
}
