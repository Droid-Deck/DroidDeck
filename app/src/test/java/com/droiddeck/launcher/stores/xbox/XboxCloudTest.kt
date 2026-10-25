package com.droiddeck.launcher.stores.xbox

import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.Store
import org.junit.Assert.*
import org.junit.Test

@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner::class)
@org.robolectric.annotation.Config(sdk = [33], application = android.app.Application::class)
class XboxCloudTest {
    @Test fun settingsRoundTripAndOutOfRangeValuesAreClamped() {
        val settings = XboxCloudSettings(resolution = "720p", bitrateMbps = 20, volume = 200, sharpen = 4, fxaa = true, statsCorner = "left",
            playerEngine = "native", frameGen = "lsfg3", fpsLimit = 120, fpsCustomOn = true, fpsCustom = 90, smallBuffer = true, zeroCopy = true)
        assertEquals(settings, XboxCloudSettings.decode(settings.toJson()))
        val wild = XboxCloudSettings.decode("""{"resolution":"4k","codec":"x","bitrateMbps":900,"volume":5,"statsLevel":9,"gamma":40}""")
        assertEquals("1080p", wild.resolution); assertEquals("default", wild.codec); assertEquals(100, wild.bitrateMbps)
        assertEquals(100, wild.volume); assertEquals(4, wild.statsLevel); assertEquals(3f, wild.gamma)
        assertEquals("right", XboxCloudSettings.decode("""{"statsCorner":"middle"}""").statsCorner)
        assertEquals("ddfg", XboxCloudSettings.decode("""{"frameGen":"winfg"}""").frameGen)
        assertEquals(0, XboxCloudSettings.decode("""{"fpsLimit":77,"fpsCustom":500}""").fpsLimit)
        assertEquals(165, XboxCloudSettings.decode("""{"fpsCustomOn":true,"fpsCustom":500}""").fpsTarget)
        assertEquals(XboxCloudSettings(), XboxCloudSettings.decode("not json"))
        assertEquals("android", XboxCloudSettings(resolution = "720p").osName)
        assertEquals("tizen", XboxCloudSettings(resolution = "1080p_hq").osName)
    }

    @Test fun addedCloudGamesKeepValidRowsOnly() {
        val list = listOf(XboxCloudGames.Entry("9AAAAAAAAAAA", "A game", "https://store-images.s-microsoft.com/p", null))
        val decoded = XboxCloudGames.decode(XboxCloudGames.encode(list))
        assertEquals(1, decoded.size); assertEquals("A game", decoded[0].title); assertNull(decoded[0].hero)
        assertTrue(XboxCloudGames.decode("""[{"id":"bad","title":"x"},{"id":"9BBBBBBBBBBB","title":""}]""").isEmpty())
        assertTrue(XboxCloudGames.decode("nope").isEmpty())
        assertTrue(XboxCloudGames.rowId("9AAAAAAAAAAA") < 0)
    }

    @Test fun libraryFilterSplitsOwnedGamePassAndCloud() {
        fun item(owned: Boolean, pass: Boolean, cloud: Boolean) = CatalogItem(Store.XBOX, "9AAAAAAAAAAA", "A", null,
            owned = owned, subscription = pass, extra = mapOf("cloud" to if (cloud) "1" else "0"))
        val owned = item(true, false, false); val pass = item(false, true, true)
        assertTrue(XboxLibraryFilter.keeps(XboxLibraryFilter.ALL, owned))
        assertTrue(XboxLibraryFilter.keeps(XboxLibraryFilter.OWNED, owned)); assertFalse(XboxLibraryFilter.keeps(XboxLibraryFilter.OWNED, pass))
        assertTrue(XboxLibraryFilter.keeps(XboxLibraryFilter.GAME_PASS, pass)); assertFalse(XboxLibraryFilter.keeps(XboxLibraryFilter.GAME_PASS, owned))
        assertTrue(XboxLibraryFilter.keeps(XboxLibraryFilter.CLOUD, pass)); assertFalse(XboxLibraryFilter.keeps(XboxLibraryFilter.CLOUD, owned))
    }

    @Test fun cloudLaunchUrlUsesTheStreamingEdition() {
        assertEquals("https://www.xbox.com/play/launch/ball-x-pit/9BBBBBBBBBBB", XboxCatalog.cloudUrl("9AAAAAAAAAAA", "BALL x PIT", "9BBBBBBBBBBB"))
    }
}
