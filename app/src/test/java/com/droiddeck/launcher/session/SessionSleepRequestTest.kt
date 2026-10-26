package com.droiddeck.launcher.session

import android.os.Looper
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
        SessionState.running = true
        SessionState.suspended = false
        SessionState.mode = SessionService.MODE_RUN
        ReflectionHelpers.setField(service, "suspendPolicy", SessionPrefs.SUSPEND_NEVER)
        ReflectionHelpers.setField(service, "suspendController", SessionSuspendController(
            sessionRoot = { -1 }, helperRoots = { emptyList() },
            suspendAudio = {}, resumeAudio = {}, executor = executor,
        ))
    }

    @After fun tearDown() {
        SessionState.running = false
        SessionState.suspended = false
        SessionState.mode = SessionService.MODE_STEAM
        shadowOf(Looper.getMainLooper()).idle()
        work.clear()
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
