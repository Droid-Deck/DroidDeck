package com.droiddeck.launcher.session

import android.app.Service
import android.content.Intent
import android.os.Looper
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.core.SessionPart
import com.droiddeck.launcher.input.FakeInputWriter
import com.droiddeck.launcher.runtime.LinuxRuntimeInstaller
import com.droiddeck.launcher.wayland.WaylandCompositor
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [
    SessionLifecycleTest.ShadowInputWriter::class, SessionLifecycleTest.ShadowCompositor::class,
])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionLifecycleTest {
    @Implements(value = FakeInputWriter::class, isInAndroidSdk = false, callThroughByDefault = false)
    class ShadowInputWriter {
        companion object {
            @Implementation @JvmStatic fun __staticInitializer__() {}
        }
    }

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false, callThroughByDefault = false)
    class ShadowCompositor {
        companion object {
            @Implementation @JvmStatic fun __staticInitializer__() {}
        }
    }

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

    @Test fun runtimeWorkAndSessionStartupExcludeEachOther() {
        assertTrue(SessionState.tryBeginRuntimeWork())
        try {
            assertFalse(SessionState.tryStart())
            assertFalse(SessionState.tryBeginRuntimeWork())
        } finally {
            SessionState.finishRuntimeWork()
        }
        assertTrue(SessionState.tryStart())
        assertFalse(SessionState.tryBeginRuntimeWork())
        assertTrue(SessionState.beginStop())
        assertFalse(SessionState.tryBeginRuntimeWork())
        SessionState.finishStop()
        assertTrue(SessionState.tryBeginRuntimeWork())
        SessionState.finishRuntimeWork()
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

    @Test fun activityCannotStartAnotherGuestWhileFailedCleanupIsFinishing() {
        beginTeardown()
        SessionEvents.fail("SESSION_CLEANUP_FAILED", "Session processes are still running", -1)
        val activity = Robolectric.buildActivity(SessionActivity::class.java).create()
        assertFalse(activity.get().isFinishing)
        assertNotNull(SessionState.endListener)
        assertEquals(SessionPhase.FAILED, SessionState.phase)
        assertFalse(SessionState.running)
        activity.get().surfaceCreated(android.view.SurfaceView(activity.get()).holder)
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedService)
        activity.start().resume().pause().stop().destroy()
        assertNull(SessionState.endListener)
        assertTrue(SessionState.stopping)
    }

    @Test fun stopWaitsForStartupToReleaseItsResourcesBeforeFinishing() {
        val service = Robolectric.buildService(SessionService::class.java).create()
        assertTrue(SessionState.tryStart())
        val lock = SessionService::class.java.getDeclaredField("sessionWorkLock")
            .apply { isAccessible = true }.get(service.get())
        @Suppress("UNCHECKED_CAST")
        val parts = SessionService::class.java.getDeclaredField("components")
            .apply { isAccessible = true }.get(service.get()) as MutableList<SessionPart>
        val stopped = java.util.concurrent.CountDownLatch(1)
        var notified = false
        SessionState.endListener = {
            assertEquals(0L, stopped.count)
            assertFalse(SessionState.stopping)
            notified = true
        }
        try {
            synchronized(lock) {
                service.get().onStartCommand(Intent(SessionService.ACTION_STOP), 0, 1)
                assertTrue(SessionState.stopping)
                service.get().onStartCommand(Intent(), 0, 2)
                assertFalse(SessionState.running)
                assertFalse(notified)
                val teardown = Thread.getAllStackTraces().keys.single { it.name == "session-teardown" }
                val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
                while (teardown.isAlive && teardown.state != Thread.State.BLOCKED && System.nanoTime() < deadline) {
                    Thread.yield()
                }
                assertEquals(Thread.State.BLOCKED, teardown.state)
                parts.add(object : SessionPart() {
                    override fun start() {}
                    override fun stop() { stopped.countDown() }
                })
            }
            assertTrue(stopped.await(5, java.util.concurrent.TimeUnit.SECONDS))
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (!notified && System.nanoTime() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(10)
            }
            assertTrue(notified)
            assertTrue(parts.isEmpty())
            assertTrue(SessionState.tryStart())
        } finally {
            SessionState.running = false
            service.destroy()
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

    @Test fun cleanupFailureCannotAllowRuntimeRemovalDuringTeardown() {
        beginTeardown()
        val context = RuntimeEnvironment.getApplication()
        SessionEvents.fail("SESSION_CLEANUP_FAILED", "Session processes are still running", -1)
        assertNull(LinuxRuntimeInstaller.beginUninstall(context))
        assertNull(LinuxRuntimeInstaller.beginMaintenance(context, "Removing desktop"))
        assertFalse(LinuxRuntimeInstaller.isBusy())
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
