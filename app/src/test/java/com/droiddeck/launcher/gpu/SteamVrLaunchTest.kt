package com.droiddeck.launcher.gpu

import org.junit.Assert.*
import org.junit.Test

class SteamVrLaunchTest {
    @Test fun manualRequestKeepsPollingWithoutGlassesControlOrCardboard() {
        for (status in listOf(null, SteamVr.Status.READY, SteamVr.Status.UNAVAILABLE, SteamVr.Status.RUNNING)) {
            assertTrue(SteamVr.shouldPoll(status, false, false, false, true))
        }
    }

    @Test fun observedActivityIsPolledUntilItStopsEvenWithoutARequest() {
        assertTrue(SteamVr.shouldPoll(SteamVr.Status.RUNNING, false, false, false, false))
        assertTrue(SteamVr.shouldPoll(SteamVr.Status.INSTALLING, false, false, false, false))
        assertFalse(SteamVr.shouldPoll(SteamVr.Status.READY, false, false, false, false))
        assertFalse(SteamVr.shouldPoll(null, false, false, false, false))
    }

    @Test fun explicitPlayRequestStartsWhenRuntimeIsReadyWithoutWaitingForGlassesControlOrOutput() {
        assertEquals("start", action(SteamVr.Status.READY))
    }

    @Test fun pendingRequestWaitsForGuestAndLoadingThenStarts() {
        assertNull(action(SteamVr.Status.UNAVAILABLE))
        assertNull(action(SteamVr.Status.READY, loading = true))
        assertEquals("start", action(SteamVr.Status.READY))
    }

    @Test fun missingRuntimeIsInstalledOnceAndThenStarted() {
        assertEquals("install", action(SteamVr.Status.NOT_INSTALLED))
        assertNull(action(SteamVr.Status.NOT_INSTALLED, installTried = true))
        assertNull(action(SteamVr.Status.INSTALLING, installTried = true))
        assertEquals("start", action(SteamVr.Status.READY, installTried = true))
    }

    @Test fun runningRuntimeOrAttemptedStartDoesNotLaunchAnotherInstance() {
        assertNull(action(SteamVr.Status.RUNNING))
        assertNull(action(SteamVr.Status.READY, startTried = true))
    }

    @Test fun ordinarySessionsAndCancelledRequestsDoNotAutostart() {
        assertNull(SteamVr.autoAction(SteamVr.Status.READY, false, false, true, false, false))
        assertNull(SteamVr.autoAction(SteamVr.Status.READY, true, false, false, false, false))
    }

    private fun action(status: SteamVr.Status, loading: Boolean = false,
                       startTried: Boolean = false, installTried: Boolean = false) =
        SteamVr.autoAction(status, true, loading, true, startTried, installTried)
}
