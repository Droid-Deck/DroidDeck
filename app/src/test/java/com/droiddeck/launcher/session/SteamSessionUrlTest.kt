package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamSessionUrlTest {
    @Test
    fun onlySupportedSteamNavigationRoutesAreAccepted() {
        listOf("steam://open/downloads", "steam://uninstall/12345")
            .forEach { assertTrue(it, SteamSessionUrl.isSupported(it)) }
        listOf("steam://open/settings", "steam://nav/library", "steam://uninstall/0", "steam://uninstall/01",
            "steam://open/downloads\n--exec", "https://example.com")
            .forEach { assertFalse(it, SteamSessionUrl.isSupported(it)) }
    }

    @Test
    fun bridgeMustConfirmLaunch() {
        assertTrue(SteamSessionUrl.wasOpened(ok = true, launched = true))
        assertFalse(SteamSessionUrl.wasOpened(ok = false, launched = true))
        assertFalse(SteamSessionUrl.wasOpened(ok = true, launched = false))
    }
}
