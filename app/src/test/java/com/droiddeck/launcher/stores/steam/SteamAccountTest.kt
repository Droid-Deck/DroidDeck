package com.droiddeck.launcher.stores.steam

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamAccountTest {
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
