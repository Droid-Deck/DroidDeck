package com.droiddeck.launcher.stores

import com.droiddeck.launcher.stores.gog.GogAuth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WebCookiesTest {
    @Test fun gogLoginCookiesAreExpiredOnTheLoginHostAndTheDomain() {
        val lines = WebCookies.expired("galaxy-login-s=a; galaxy-login-tsa=b; galaxy-login-al=c", GogAuth.COOKIE_DOMAIN, listOf("/"), "login.gog.com")
        assertEquals(9, lines.size)
        assertTrue(lines.contains("galaxy-login-al=; Max-Age=0; Path=/; Secure"))
        assertTrue(lines.contains("galaxy-login-al=; Max-Age=0; Path=/; Secure; Domain=.gog.com"))
        assertTrue(lines.contains("galaxy-login-al=; Max-Age=0; Path=/; Secure; Domain=login.gog.com"))
        assertTrue(lines.none { it.startsWith("galaxy-login-al=c") })
        assertEquals(emptyList<String>(), WebCookies.expired(null, GogAuth.COOKIE_DOMAIN, listOf("/"), "login.gog.com"))
    }
}
