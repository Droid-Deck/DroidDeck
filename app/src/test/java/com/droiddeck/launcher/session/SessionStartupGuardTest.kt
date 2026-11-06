package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionStartupGuardTest {
    @Test fun staleOrStoppedStarterCannotReplaceCurrentSessionState() {
        var route = "new-session"

        assertFalse(updateCurrentSessionStart(4, 5, running = true) { route = "stale-session" })
        assertEquals("new-session", route)
        assertFalse(updateCurrentSessionStart(5, 5, running = false) { route = "stopped-session" })
        assertEquals("new-session", route)
    }

    @Test fun currentStarterMayPublishItsFrozenState() {
        var route = "unset"

        assertTrue(updateCurrentSessionStart(5, 5, running = true) { route = "system-vulkan" })
        assertEquals("system-vulkan", route)
    }

    @Test fun cancelledDownloadIsNotReportedAsMissing() {
        assertEquals(StartupAwaitResult.CANCELLED, startupAwaitResult(stillStarting = false, ready = false))
        assertEquals(StartupAwaitResult.MISSING, startupAwaitResult(stillStarting = true, ready = false))
        assertEquals(StartupAwaitResult.READY, startupAwaitResult(stillStarting = true, ready = true))
    }
}
