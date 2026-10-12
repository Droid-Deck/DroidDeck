package com.droiddeck.launcher.session

import android.app.Service
import android.content.Intent
import android.os.Looper
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SessionLifecycleTest {
    @Before @After fun reset() {
        SessionState.running = false
        SessionState.finishStop()
        SessionState.stopRequested = false
        SessionState.phase = SessionPhase.IDLE
        SessionState.endListener = null
        SessionState.relaunch = null
        SessionState.failureCode = null
        SessionState.failureMessage = null
        SessionState.failureStatus = null
        SessionState.logDirectory = null
        SessionState.eventsFile = null
    }

    private fun beginTeardown() {
        assertTrue(SessionState.tryStart())
        assertTrue(SessionState.beginStop())
        SessionState.phase = SessionPhase.STOPPING
    }

    @Test fun startsAreExcludedUntilTeardownFinishes() {
        assertTrue(SessionState.tryStart())
        assertFalse(SessionState.tryStart())
        assertTrue(SessionState.beginStop())
        assertFalse(SessionState.running)
        assertTrue(SessionState.stopping)
        assertFalse(SessionState.tryStart())
        assertFalse(SessionState.beginStop())
        assertTrue(SessionState.stopping)
        SessionState.finishStop()
        assertTrue(SessionState.tryStart())
    }

    @Test fun activityRecreatedDuringStopReattachesAndReceivesCompletion() {
        beginTeardown()
        val original = Robolectric.buildActivity(SessionActivity::class.java).create()
        val originalListener = SessionState.endListener
        assertNotNull(originalListener)
        assertFalse(original.get().isFinishing)
        original.destroy()
        assertNull(SessionState.endListener)

        val replacement = Robolectric.buildActivity(SessionActivity::class.java).create()
        assertFalse(replacement.get().isFinishing)
        assertNotNull(SessionState.endListener)
        assertNotSame(originalListener, SessionState.endListener)
        assertTrue(SessionState.stopping)
        assertFalse(SessionState.running)
        assertEquals(SessionPhase.STOPPING, SessionState.phase)

        SessionState.finishStop()
        SessionState.phase = SessionPhase.IDLE
        SessionState.notifyEnded(0)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(replacement.get().isFinishing)
        assertFalse(SessionState.running)
        replacement.destroy()
    }

    @Test fun directActivityLaunchDuringTeardownAttachesWithoutStartingAGuest() {
        beginTeardown()
        val activity = Robolectric.buildActivity(SessionActivity::class.java).create()
        assertFalse(activity.get().isFinishing)
        assertNotNull(SessionState.endListener)
        assertEquals(SessionPhase.STOPPING, SessionState.phase)
        assertFalse(SessionState.running)
        activity.start().resume().pause().stop().destroy()
        assertNull(SessionState.endListener)
        assertTrue(SessionState.stopping)
    }

    @Test fun waitingActivityConsumesTouchWithoutSessionViews() {
        beginTeardown()
        val activity = Robolectric.buildActivity(SessionActivity::class.java).create()
        val event = android.view.MotionEvent.obtain(
            0L, 0L, android.view.MotionEvent.ACTION_DOWN, 10f, 10f, 0,
        ).apply {
            source = android.view.InputDevice.SOURCE_TOUCHSCREEN
        }
        try {
            assertTrue(activity.get().dispatchTouchEvent(event))
            assertTrue(activity.get().onTouchEvent(event))
        } finally {
            event.recycle()
            activity.destroy()
        }
    }

    @Test fun failedStopClearsPendingHandoffBeforeNotifyingAndAllowsRetry() {
        val service = Robolectric.buildService(SessionService::class.java).create()
        beginTeardown()
        SessionState.relaunch = Intent().putExtra(SessionService.EXTRA_MODE, SessionService.MODE_DESKTOP)
        var notified = false
        var notifiedStatus: Int? = null
        var handoffAtNotification: Intent? = null
        SessionState.endListener = { status ->
            notified = true
            notifiedStatus = status
            handoffAtNotification = SessionState.relaunch
        }
        try {
            SessionService::class.java.getDeclaredMethod(
                "finishSessionStop", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service.get(), -1, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(notified)
            assertEquals(-1, notifiedStatus)
            assertNull(handoffAtNotification)
            assertNull(SessionState.relaunch)
            assertTrue(SessionState.tryStart())
        } finally {
            SessionState.running = false
            service.destroy()
        }
    }

    @Test fun serviceRejectsDirectStartsDuringTeardown() {
        beginTeardown()
        val service = Robolectric.buildService(SessionService::class.java).create()
        try {
            assertEquals(Service.START_NOT_STICKY, service.get().onStartCommand(Intent(), 0, 1))
            assertTrue(SessionState.stopping)
            assertFalse(SessionState.running)
        } finally {
            service.destroy()
        }
    }

    @Test fun repeatedStopDoesNotCancelTheNextLaunch() {
        beginTeardown()
        SessionService.stop(RuntimeEnvironment.getApplication())
        assertFalse(SessionState.stopRequested)
        assertTrue(SessionState.stopping)
    }

    @Test fun failedCleanupStillBlocksRuntimeRemovalUntilTeardownFinishes() {
        beginTeardown()
        SessionState.phase = SessionPhase.FAILED
        val context = RuntimeEnvironment.getApplication()
        assertNull(LinuxRuntimeInstaller.beginUninstall(context))
        assertNull(LinuxRuntimeInstaller.beginMaintenance(context, "Removing desktop"))
        assertFalse(LinuxRuntimeInstaller.isBusy())
    }

    @Test fun survivingSessionSandboxBlocksRuntimeRemoval() {
        val context = RuntimeEnvironment.getApplication()
        OrphanReaper.keepSession(ProcessCleanup.Identity(12345, 10))
        try {
            assertNull(LinuxRuntimeInstaller.beginUninstall(context))
            assertNull(LinuxRuntimeInstaller.beginMaintenance(context, "Removing desktop"))
            assertFalse(LinuxRuntimeInstaller.isBusy())
        } finally {
            OrphanReaper.release(12345)
        }
    }

    @Test fun completionReleasesTheGateBeforeNotifyingTheActivity() {
        val service = Robolectric.buildService(SessionService::class.java).create()
        beginTeardown()
        var notified = false
        SessionState.endListener = { status ->
            assertEquals(0, status)
            assertFalse(SessionState.stopping)
            notified = true
        }
        try {
            SessionService::class.java.getDeclaredMethod(
                "finishSessionStop", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service.get(), 0, 0)
            assertTrue(SessionState.stopping)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(notified)
            assertEquals(SessionPhase.IDLE, SessionState.phase)
        } finally {
            service.destroy()
        }
    }

    @Test fun staleCompletionCannotReleaseAnotherSessionsTeardownGate() {
        val service = Robolectric.buildService(SessionService::class.java).create()
        beginTeardown()
        var notified = false
        SessionState.endListener = { notified = true }
        try {
            SessionService::class.java.getDeclaredField("sessionGen")
                .apply { isAccessible = true }.setInt(service.get(), 1)
            SessionService::class.java.getDeclaredMethod(
                "finishSessionStop", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service.get(), 0, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(SessionState.stopping)
            assertFalse(notified)
            assertFalse(SessionState.tryStart())
        } finally {
            service.destroy()
        }
    }
}
