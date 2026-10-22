package com.droiddeck.launcher

import android.content.Intent
import com.droiddeck.launcher.session.SessionService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionLaunchGateTest {
    @Test fun newModeIntentChecksBusyStateDuringRunningSession() {
        val intent = Intent().putExtra(SessionService.EXTRA_MODE, SessionService.MODE_RUN)

        assertTrue(SessionLaunchGate.shouldCheckBusyState(intent, sessionRunning = true))
    }

    @Test fun explicitResumeAndBareResumeSkipNewSessionBusyGate() {
        val explicitResume = Intent().setAction(SessionService.ACTION_RESUME)

        assertFalse(SessionLaunchGate.shouldCheckBusyState(explicitResume, sessionRunning = true))
        assertFalse(SessionLaunchGate.shouldCheckBusyState(Intent(), sessionRunning = true))
    }

    @Test fun newSessionStillChecksBusyStateWhenNoSessionIsRunning() {
        assertTrue(SessionLaunchGate.shouldCheckBusyState(Intent(), sessionRunning = false))
    }
}
