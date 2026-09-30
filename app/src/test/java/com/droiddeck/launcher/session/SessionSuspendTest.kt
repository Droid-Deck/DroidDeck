package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The pause rule, from the report it has to answer: on Samsung DeX the session is on the monitor
 * while Android reports the app hidden and the phone's panel off, so it used to pause and stay
 * paused with no way back.
 */
class SessionSuspendTest {

    private fun away(
        visible: Boolean = true,
        screenOn: Boolean = true,
        external: Boolean = false,
        pauseThere: Boolean = false,
    ) = SessionSuspend.away(visible, screenOn, external, pauseThere)

    @Test fun onThePrimaryScreenTheRuleIsUnchanged() {
        assertFalse(away())
        assertTrue(away(visible = false))
        assertTrue(away(screenOn = false))
    }

    @Test fun dexDoesNotPauseTheSession() {
        // DeX: the session is on the monitor and Android turns the phone's own panel off, which is
        // the case that used to stop and never come back. The activity is the one on show there.
        assertFalse(away(visible = true, screenOn = false, external = true))
    }

    @Test fun leavingTheAppOnAnExternalDisplayStillPauses() {
        // The activity hidden is the app being left, which is what the policy is for - DeX's own
        // hidden report is the case above, where the app is the one on show on the monitor.
        assertTrue(away(visible = false, screenOn = true, external = true))
    }

    @Test fun askingToPauseTherePauses() {
        assertTrue(away(visible = true, screenOn = false, external = true, pauseThere = true))
    }

    @Test fun manualPausesOnArrivingAwayOnly() {
        assertTrue(SessionSuspend.manualPause(SessionPrefs.SUSPEND_MANUAL, away = true, wasAway = false))
        assertFalse(SessionSuspend.manualPause(SessionPrefs.SUSPEND_MANUAL, away = true, wasAway = true))
        assertFalse(SessionSuspend.manualPause(SessionPrefs.SUSPEND_MANUAL, away = false, wasAway = true))
    }

    @Test fun resumeSurvivesTheTriggerStillHolding() {
        // Manual, the trigger still there (the panel off under a dock), the user has resumed: the
        // latch is not taken again, so the session stays up.
        assertFalse(
            SessionSuspend.shouldSuspend(SessionPrefs.SUSPEND_MANUAL, away = true, manualPauseRequested = false),
        )
    }

    @Test fun autoFollowsTheScreenAndNeverIgnoresIt() {
        assertTrue(SessionSuspend.shouldSuspend(SessionPrefs.SUSPEND_AUTO, away = true, manualPauseRequested = false))
        assertFalse(SessionSuspend.shouldSuspend(SessionPrefs.SUSPEND_AUTO, away = false, manualPauseRequested = false))
        assertFalse(SessionSuspend.shouldSuspend(SessionPrefs.SUSPEND_NEVER, away = true, manualPauseRequested = true))
    }
}
