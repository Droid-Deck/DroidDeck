package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.os.Looper
import android.os.PowerManager
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class SessionSleepRequestTest {
    private lateinit var service: SessionService
    private val work = ArrayDeque<Runnable>()
    private val executor = object : AbstractExecutorService() {
        override fun execute(command: Runnable) { work.addLast(command) }
        override fun shutdown() {}
        override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
        override fun isShutdown() = false
        override fun isTerminated() = false
        override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
    }

    @Before fun setUp() {
        service = Robolectric.buildService(SessionService::class.java).get()
        shadowOf(service.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(true)
        visibleWindows().clear()
        SessionState.running = true
        SessionState.suspended = false
        SessionState.suspending = false
        SessionState.resuming = false
        SessionState.resumeRequested = false
        SessionState.manualPause = false
        SessionState.mode = SessionService.MODE_RUN
        ReflectionHelpers.setField(service, "suspendPolicy", SessionPrefs.SUSPEND_NEVER)
        ReflectionHelpers.setField(service, "suspendController", SessionSuspendController(
            sessionRoot = { -1 }, helperRoots = { emptyList() },
            suspendAudio = {}, resumeAudio = {}, executor = executor,
        ))
    }

    @After fun tearDown() {
        visibleWindows().clear()
        SessionState.running = false
        SessionState.suspended = false
        SessionState.suspending = false
        SessionState.resuming = false
        SessionState.resumeRequested = false
        SessionState.manualPause = false
        SessionState.mode = SessionService.MODE_STEAM
        shadowOf(Looper.getMainLooper()).idle()
        work.clear()
    }

    private fun visibleWindows(): MutableSet<Int> = ReflectionHelpers.getField(LinuxWindows, "visible")

    private fun returnLinuxWindow() {
        visibleWindows().add(27)
        service.onStartCommand(Intent("com.droiddeck.launcher.LINUX_WINDOWS_VISIBLE"), 0, 1)
    }

    private fun backgroundPause() {
        ReflectionHelpers.setField(service, "activityVisible", false)
        ReflectionHelpers.setField(service, "suspendPolicy", SessionPrefs.SUSPEND_MANUAL)
        ReflectionHelpers.setField(service, "manualPauseRequested", true)
        SessionState.suspended = true
    }

    @Test fun returningLinuxWindowResumesBackgroundPauseWithMainActivityHidden() {
        backgroundPause()
        returnLinuxWindow()
        assertEquals(1, work.size)
        assertTrue(SessionState.resuming)
        assertFalse(ReflectionHelpers.getField(service, "activityVisible"))
        work.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(SessionState.suspended)
        assertFalse(SessionState.resumeRequested)
        assertEquals(0, work.size)
    }

    @Test fun returningLinuxWindowPreservesExplicitPause() {
        backgroundPause()
        ReflectionHelpers.setField(service, "manualHold", true)
        SessionState.manualPause = true
        returnLinuxWindow()
        assertTrue(SessionState.suspended)
        assertTrue(SessionState.manualPause)
        assertFalse(SessionState.resuming)
        assertEquals(0, work.size)
    }

    @Test fun returningLinuxWindowDoesNotResumeWhileScreenIsOff() {
        backgroundPause()
        shadowOf(service.getSystemService(Context.POWER_SERVICE) as PowerManager).setIsInteractive(false)
        returnLinuxWindow()
        assertTrue(SessionState.suspended)
        assertFalse(SessionState.resuming)
        assertEquals(0, work.size)
    }

    @Test fun hidingLinuxWindowsDoesNotResumeBackgroundPause() {
        backgroundPause()
        service.onStartCommand(Intent("com.droiddeck.launcher.LINUX_WINDOWS_VISIBLE"), 0, 1)
        assertTrue(SessionState.suspended)
        assertFalse(SessionState.resuming)
        assertEquals(0, work.size)
    }

    @Test fun returningLinuxWindowCancelsPendingBackgroundFreeze() {
        backgroundPause()
        SessionState.suspended = false
        ReflectionHelpers.callInstanceMethod<Void>(service, "updateSuspendPolicy")
        assertEquals(1, work.size)
        assertTrue(SessionState.suspending)
        returnLinuxWindow()
        work.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(SessionState.suspended)
        assertFalse(SessionState.suspending)
        assertFalse(ReflectionHelpers.getField(service, "suspendAttemptFailed"))
        assertEquals(0, work.size)
    }

    @Test fun cancelledFreezePreservesAndRetriesANewerSteamRequest() {
        ReflectionHelpers.setField(service, "steamSleepToken", "old-request")
        ReflectionHelpers.callInstanceMethod<Void>(service, "updateSuspendPolicy")
        assertEquals(1, work.size)
        ReflectionHelpers.setField(service, "freezeCancelled", true)
        ReflectionHelpers.setField(service, "steamSleepToken", "new-request")
        work.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("new-request", ReflectionHelpers.getField<String>(service, "steamSleepToken"))
        assertEquals(1, work.size)
        assertFalse(ReflectionHelpers.getField(service, "suspendAttemptFailed"))
    }

    @Test fun cancelledFreezeCompletesItsOwnRequestWithoutRetryingIt() {
        ReflectionHelpers.setField(service, "steamSleepToken", "old-request")
        ReflectionHelpers.callInstanceMethod<Void>(service, "updateSuspendPolicy")
        ReflectionHelpers.setField(service, "freezeCancelled", true)
        work.removeFirst().run()
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(null, ReflectionHelpers.getField<String?>(service, "steamSleepToken"))
        assertEquals(0, work.size)
        assertFalse(ReflectionHelpers.getField(service, "suspendAttemptFailed"))
    }
}
