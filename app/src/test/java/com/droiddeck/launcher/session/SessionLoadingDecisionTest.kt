package com.droiddeck.launcher.session

import android.content.Intent
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class SessionLoadingDecisionTest {
    private var previousSessionId: String? = null
    private var previousRelaunch: Intent? = null

    @Before
    fun setUp() {
        previousSessionId = SessionState.sessionId
        previousRelaunch = SessionState.relaunch
        SessionState.clearRelaunch()
    }

    @After
    fun tearDown() {
        SessionState.sessionId = previousSessionId
        SessionState.relaunch = previousRelaunch
        SessionState.clearRelaunch()
        if (previousRelaunch != null) SessionState.queueRelaunch(previousRelaunch!!)
    }

    @Test
    fun loadingTimesOutOnlyWhenNoSessionStarted() {
        assertEquals(false, SessionLoadingDecision.shouldReturnToLauncher(100L, 10_099L, false, SessionPhase.IDLE))
        assertEquals(true, SessionLoadingDecision.shouldReturnToLauncher(100L, 10_100L, false, SessionPhase.IDLE))
        assertEquals(false, SessionLoadingDecision.shouldReturnToLauncher(100L, 10_100L, true, SessionPhase.IDLE))
        assertEquals(false, SessionLoadingDecision.shouldReturnToLauncher(100L, 10_100L, false, SessionPhase.PREPARING))
    }

    @Test
    fun relaunchRequiresTheCurrentSessionToHaveRequestedTheStop() {
        SessionState.sessionId = "session-a"
        val next = Intent().apply { action = "next" }
        SessionState.queueRelaunch(next)
        assertNull(SessionState.takeRelaunch())
        SessionState.queueRelaunch(next)
        SessionState.confirmRelaunchStop()
        assertEquals(next, SessionState.takeRelaunch())
        assertNull(SessionState.takeRelaunch())

        SessionState.queueRelaunch(next)
        SessionState.confirmRelaunchStop()
        SessionState.sessionId = "session-b"
        assertNull(SessionState.takeRelaunch())
    }
}
