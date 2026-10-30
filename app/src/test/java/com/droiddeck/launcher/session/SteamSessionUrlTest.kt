package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamSessionUrlTest {
    @Test
    fun onlySupportedSteamNavigationRoutesAreAccepted() {
        listOf("steam://open/downloads", "steam://uninstall/12345", "steam://uninstall/4294967295")
            .forEach { assertTrue(it, SteamSessionUrl.isSupported(it)) }
        listOf("steam://open/settings", "steam://nav/library", "steam://uninstall/0", "steam://uninstall/01",
            "steam://uninstall/4294967296", "steam://open/downloads\n--exec", "https://example.com")
            .forEach { assertFalse(it, SteamSessionUrl.isSupported(it)) }
    }

    @Test
    fun bridgeMustConfirmLaunch() {
        assertTrue(SteamSessionUrl.wasOpened(ok = true, launched = true))
        assertFalse(SteamSessionUrl.wasOpened(ok = false, launched = true))
        assertFalse(SteamSessionUrl.wasOpened(ok = true, launched = false))
    }

    @Test
    fun resumeTargetsOnlyTheSameLiveSessionAndActivity() {
        assertTrue(SteamSessionUrl.canResume("session-a", "session-a", running = true, stopping = false, activityDestroyed = false))
        assertFalse(SteamSessionUrl.canResume("session-a", "session-b", running = true, stopping = false, activityDestroyed = false))
        assertFalse(SteamSessionUrl.canResume("session-a", "session-a", running = false, stopping = false, activityDestroyed = false))
        assertFalse(SteamSessionUrl.canResume("session-a", "session-a", running = true, stopping = true, activityDestroyed = false))
        assertFalse(SteamSessionUrl.canResume("session-a", "session-a", running = true, stopping = false, activityDestroyed = true))
        assertFalse(SteamSessionUrl.canResume(null, null, running = true, stopping = false, activityDestroyed = false))
    }

}
