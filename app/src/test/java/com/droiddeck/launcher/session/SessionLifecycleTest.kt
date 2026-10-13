package com.droiddeck.launcher.session

import android.app.Service
import android.content.Intent
import android.os.Looper
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.MainActivity
import com.droiddeck.launcher.R
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.runtime.LinuxRuntime
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
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
@LooperMode(LooperMode.Mode.PAUSED)
class SessionLifecycleTest {
    @Before @After fun reset() {
        OrphanReaper.closeSessionWork()
        OrphanReaper.clearCleanupRequired(LinuxRuntime.rootDir(RuntimeEnvironment.getApplication()))
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

    @Test fun teardownClosesAndDrainsInFlightSessionWork() {
        assertTrue(SessionState.tryStart())
        assertTrue(OrphanReaper.openSessionWork())
        val entered = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val worker = Thread {
            OrphanReaper.withSessionWork {
                entered.countDown()
                release.await()
            }
        }
        worker.start()
        try {
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS))
            OrphanReaper.closeSessionWork()
            assertNull(OrphanReaper.withSessionWork { fail("closed session work must not start") })
            val draining = java.util.concurrent.CountDownLatch(1)
            val drain = Thread {
                draining.countDown()
                OrphanReaper.awaitSessionWork()
            }
            drain.start()
            assertTrue(draining.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (drain.state != Thread.State.BLOCKED && System.nanoTime() < deadline) Thread.yield()
            assertEquals(Thread.State.BLOCKED, drain.state)
            assertTrue(drain.isAlive)
            release.countDown()
            worker.join(5000)
            drain.join(5000)
            assertFalse(worker.isAlive)
            assertFalse(drain.isAlive)
        } finally {
            release.countDown()
            worker.join(5000)
            SessionState.beginStop()
            SessionState.finishStop()
        }
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
        val context = RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(context)
        OrphanReaper.markCleanupRequired(root)
        SessionEvents.fail("SESSION_CLEANUP_FAILED", "Session processes are still running", -1)
        val service = Robolectric.buildService(SessionService::class.java).create()
        try {
            SessionService::class.java.getDeclaredMethod(
                "finishSessionStop", Int::class.javaPrimitiveType, Int::class.javaPrimitiveType,
            ).apply { isAccessible = true }.invoke(service.get(), -1, 0)
            shadowOf(Looper.getMainLooper()).idle()
            assertFalse(SessionState.stopping)
            assertTrue(OrphanReaper.hasSessionProcesses(context))
            assertTrue(java.io.File(context.filesDir, "session-cleanup-required").isFile)
            assertNull(LinuxRuntimeInstaller.beginUninstall(context))
            assertNull(LinuxRuntimeInstaller.beginMaintenance(context, "Removing desktop"))
            assertFalse(LinuxRuntimeInstaller.isBusy())

            OrphanReaper.updateCleanupMarker(root, ProcessCleanup.Result(0, emptyList()))
            assertFalse(OrphanReaper.hasSessionProcesses(context))
            val maintenance = LinuxRuntimeInstaller.beginMaintenance(context, "Removing desktop")
            assertNotNull(maintenance)
            assertTrue(maintenance.run(null) { null })
        } finally {
            service.destroy()
        }
    }

    @Test fun pendingCleanupExplainsWhyRuntimeChangesAreBlockedUntilVerified() {
        val context = RuntimeEnvironment.getApplication()
        val root = LinuxRuntime.rootDir(context)
        OrphanReaper.markCleanupRequired(root)
        val activity = Robolectric.buildActivity(MainActivity::class.java).get()
        val guard = MainActivity::class.java.getDeclaredMethod("runtimeCleanupBlocksChanges")
            .apply { isAccessible = true }

        assertEquals(true, guard.invoke(activity))
        assertEquals(context.getString(R.string.session_cleanup_required), ShadowToast.getTextOfLatestToast())
        assertTrue(OrphanReaper.hasSessionProcesses(context))

        OrphanReaper.updateCleanupMarker(root, ProcessCleanup.Result(0, emptyList()))
        assertEquals(false, guard.invoke(activity))
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
