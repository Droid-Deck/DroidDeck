package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Before
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionFailureTest {
    @Before fun setup() {
        SessionState.running = true
        SessionState.mode = SessionService.MODE_RUN
        SessionState.guestPid = 42
        SessionState.firstFrameSeen = true
        SessionState.suspended = false
        SessionState.logDirectory = null
        SessionEvents.fail("GPU_DEVICE_LOST", "Restart required", 1)
    }

    @Test fun failureInvalidatesTheOldFirstFrame() {
        assertFalse(SessionState.firstFrameSeen)
    }

    @Test fun lateFirstFrameCannotReviveAFailedSession() {
        SessionEvents.firstFrame()
        assertFalse(SessionState.firstFrameSeen)
        assertEquals(SessionPhase.FAILED, SessionState.phase)
    }

    @Test fun lateSteamStartupCannotReviveAFailedSession() {
        SessionState.mode = SessionService.MODE_STEAM
        SessionEvents.guestStarted(43)
        assertEquals(SessionPhase.FAILED, SessionState.phase)
    }

    @Test fun readinessCheckCannotReviveAFailedSession() {
        SessionEvents.markReadyIfPossible()
        assertEquals(SessionPhase.FAILED, SessionState.phase)
    }

    @Test fun resumeCannotReportTheFailedSessionAsReady() {
        assertEquals(SessionPhase.FAILED, SessionEvents.resumePhase())
    }

    @Test fun failureWinsConcurrentReadinessEvents() {
        repeat(50) {
            SessionState.failureCode = null
            SessionState.firstFrameSeen = false
            SessionState.phase = SessionPhase.STARTING_GUEST
            val start = CountDownLatch(1)
            val frame = Thread { start.await(); SessionEvents.firstFrame(); SessionEvents.guestStarted(44) }
            val failure = Thread { start.await(); SessionEvents.fail("GPU_DEVICE_LOST", "Restart required", 1) }
            frame.start(); failure.start(); start.countDown()
            frame.join(5000); failure.join(5000)
            assertFalse(frame.isAlive)
            assertFalse(failure.isAlive)
            assertEquals(SessionPhase.FAILED, SessionState.phase)
            assertFalse(SessionState.firstFrameSeen)
        }
    }

    @Test fun aNewSessionClearsThePreviousFailureButStillWaitsForItsOwnFrame() {
        SessionEvents.begin(RuntimeEnvironment.getApplication(), SessionService.MODE_RUN)
        SessionEvents.guestStarted(45)
        assertNull(SessionState.failureCode)
        assertFalse(SessionState.firstFrameSeen)
        assertNotEquals(SessionPhase.READY, SessionState.phase)
        SessionEvents.firstFrame()
        assertEquals(SessionPhase.READY, SessionState.phase)
    }

    @After fun cleanup() {
        SessionState.running = false
        SessionState.failureCode = null
        SessionState.failureMessage = null
        SessionState.failureStatus = null
        SessionState.firstFrameSeen = false
        SessionState.guestPid = -1
        SessionState.phase = SessionPhase.IDLE
    }
}
