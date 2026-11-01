package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamWebLoginTest {
    private fun hex(s: String) = ByteArray(s.length / 2) { s.substring(it * 2, it * 2 + 2).toInt(16).toByte() }

    // Made with: openssl enc -aes-128-cbc -K <PBKDF2-SHA1("peanuts","saltysalt",1)> -iv <16 spaces>, prefixed "v10".
    @Test fun chromiumV10ValuesDecrypt() {
        val encrypted = hex("7631300b9cf0a6eee9cf1aaac601b85ca37cf9fe9e67078564f0e05835ae2aa3d9b0ab")
        assertEquals("0123456789abcdef01234567", SteamWebLogin.decrypt(encrypted, hostHashed = false))
    }

    @Test fun newerStoresDropTheHostHash() {
        val encrypted = hex("7631304d4114329f1e692e807b8b044d67460f5e14d92ffb48b9f83be318ebd0d84c727674aea32f351b8a33c8aa42553dfeb5")
        assertEquals("hello", SteamWebLogin.decrypt(encrypted, hostHashed = true))
    }

    @Test fun otherFormatsAreSkipped() {
        assertNull(SteamWebLogin.decrypt("v11garbage".toByteArray(), hostHashed = false))
        assertNull(SteamWebLogin.decrypt(ByteArray(2), hostHashed = false))
    }

    @Test fun theSignInIsSentTheWaySteamPagesSendIt() {
        assertEquals("steamLoginSecure=76561190000000000%7C%7Ca.b.c; Path=/; Secure; HttpOnly",
            SteamWebLogin.header("steamLoginSecure", "76561190000000000||a.b.c", httpOnly = true))
        assertEquals("sessionid=0123; Path=/; Secure", SteamWebLogin.header("sessionid", "0123", httpOnly = false))
    }
}
