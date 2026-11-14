package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserHandoffTest {
    private fun hidden(handoff: BrowserHandoff, activityStarted: Boolean, now: Long) =
        SessionVisibilityPolicy.isHidden(activityStarted || handoff.active(now), true, true, true)

    @Test fun pendingHandoffKeepsBackgroundedSessionAwake() {
        val handoff = BrowserHandoff(1_000)
        handoff.begin(0)
        handoff.sessionHidden()
        assertFalse(hidden(handoff, activityStarted = false, now = 500))
    }

    @Test fun handoffEndsWhenSessionViewReturns() {
        val handoff = BrowserHandoff(1_000)
        handoff.begin(0)
        handoff.sessionHidden()
        handoff.sessionShown()
        assertFalse(handoff.active(10))
        assertTrue(hidden(handoff, activityStarted = false, now = 20))
    }

    @Test fun shownBeforeLeavingDoesNotEndHandoff() {
        val handoff = BrowserHandoff(1_000)
        handoff.begin(0)
        handoff.sessionShown()
        handoff.sessionHidden()
        assertFalse(hidden(handoff, activityStarted = false, now = 100))
    }

    @Test fun handoffExpiresAfterTimeout() {
        val handoff = BrowserHandoff(1_000)
        handoff.begin(0)
        handoff.sessionHidden()
        assertTrue(handoff.active(999))
        assertTrue(hidden(handoff, activityStarted = false, now = 1_000))
    }

    @Test fun normalBackgroundingStillHidesSession() {
        val handoff = BrowserHandoff(1_000)
        handoff.sessionHidden()
        assertFalse(handoff.active(0))
        assertTrue(hidden(handoff, activityStarted = false, now = 0))
    }

    @Test fun newHandoffRestartsTimer() {
        val handoff = BrowserHandoff(1_000)
        handoff.begin(0)
        handoff.begin(900)
        assertTrue(handoff.active(1_500))
        handoff.clear()
        assertFalse(handoff.active(1_500))
    }
}
