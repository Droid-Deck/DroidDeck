package com.droiddeck.launcher.stores.steam

import com.droiddeck.launcher.session.WebCookie
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamAccountTest {
    @Test fun promotesCookiesFromTheUsualSteamSession() {
        val session = SteamAccount.fromWebCookies(
            listOf(
                WebCookie("steamcommunity.com", "steamLoginSecure=76561198000000001%7C%7Cold; Path=/"),
                WebCookie("store.steampowered.com", "steamLoginSecure=76561198012345678%7C%7Csecret; Path=/"),
                WebCookie("store.steampowered.com", "sessionid=abc123; Path=/"),
            ),
        )

        assertEquals("76561198012345678", session?.steamId)
        assertEquals("steamLoginSecure=76561198012345678%7C%7Csecret; sessionid=abc123", session?.cookieHeader)
    }

    @Test fun readsSteamIdAndSessionFromWebViewCookies() {
        val session = SteamAccount.fromCookieHeader(
            "sessionid=abc123; steamLoginSecure=76561198012345678%7C%7Csecret; path=/",
        )

        assertEquals("76561198012345678", session?.steamId)
        assertEquals("abc123", session?.sessionId)
        assertEquals("steamLoginSecure=76561198012345678%7C%7Csecret; sessionid=abc123", session?.cookieHeader)
    }

    @Test fun rejectsCookiesWithoutSteamLoginSecureIdentity() {
        assertNull(SteamAccount.fromCookieHeader("sessionid=abc123"))
        assertNull(SteamAccount.fromCookieHeader("steamLoginSecure=not-a-steam-id||secret"))
    }
}
