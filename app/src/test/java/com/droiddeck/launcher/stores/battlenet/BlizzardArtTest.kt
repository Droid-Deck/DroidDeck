package com.droiddeck.launcher.stores.battlenet

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class BlizzardArtTest {
    @Test fun theShopsKeyArtIsUsed() {
        val page = """<meta property="og:image" content="https://blz-contentstack-images.akamaized.net/v3/assets/a/b/c/art.png">"""
        assertEquals("https://blz-contentstack-images.akamaized.net/v3/assets/a/b/c/art.png", BlizzardArt.imageUrl(page))
    }

    @Test fun aVideoStillFallsBackToTheFirstThumbnailThatIsNotABundle() {
        val page = """<meta property="og:image" content="https://img.youtube.com/vi/x/maxresdefault.jpg">""" +
            """\"imageUrl\":\"//catalog.blzstatic.com//ff9/a-prod-thumb_bundle.jpg\",""" +
            """\"imageUrl\":\"//catalog.blzstatic.com//feb/b-prod-thumb.jpg\""""
        assertEquals("https://catalog.blzstatic.com//feb/b-prod-thumb.jpg", BlizzardArt.imageUrl(page))
    }

    @Test fun anOpenGraphImageFromAnotherHostFallsBackToTheThumbnail() {
        val page = """<meta property="og:image" content="https://example.com/art.png">""" +
            """\"imageUrl\":\"//catalog.blzstatic.com//feb/b-prod-thumb.jpg\""""
        assertEquals("https://catalog.blzstatic.com//feb/b-prod-thumb.jpg", BlizzardArt.imageUrl(page))
    }

    @Test fun artFromOtherHostsIsRefused() {
        assertNull(BlizzardArt.imageUrl("""<meta property="og:image" content="https://example.com/art.png">"""))
        assertNull(BlizzardArt.imageUrl("""\"imageUrl\":\"//catalog.blzstatic.com//ff9/a-prod-thumb_bundle.jpg\""""))
    }
}
