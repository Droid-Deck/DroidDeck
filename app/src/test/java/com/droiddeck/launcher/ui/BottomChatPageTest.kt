package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BottomChatPageTest {
    @Test fun steamSitesOpenOthersDoNot() {
        assertTrue(steamChatAllows("steamcommunity.com"))
        assertTrue(steamChatAllows("login.steampowered.com"))
        assertTrue(steamChatAllows("community.cloudflare.steamstatic.com"))
        assertFalse(steamChatAllows("steamcommunity.com.example.net"))
        assertFalse(steamChatAllows("evilsteamcommunity.com"))
        assertFalse(steamChatAllows(null))
    }

    @Test fun desktopAgentKeepsTheWebViewChromeVersion() {
        val ua = desktopUserAgent("Mozilla/5.0 (Linux; Android 15; wv) AppleWebKit/537.36 (KHTML, like Gecko) Version/4.0 Chrome/141.0.7390.122 Mobile Safari/537.36")
        assertEquals("Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/141.0.7390.122 Safari/537.36", ua)
    }
}
