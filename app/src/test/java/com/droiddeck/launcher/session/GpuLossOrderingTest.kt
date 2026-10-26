package com.droiddeck.launcher.session

import android.content.Context
import android.content.Intent
import android.os.Looper
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.AutoDriverTrial
import com.droiddeck.launcher.gpu.GpuDeviceState
import com.droiddeck.launcher.input.FakeInputWriter
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.CompositorDeviceLossTest
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.util.concurrent.TimeUnit
import java.util.concurrent.AbstractExecutorService
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
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
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28, 33], manifest = Config.NONE, shadows = [CompositorDeviceLossTest.NoNativeLoad::class, GpuLossOrderingTest.NoInputRings::class])
@LooperMode(LooperMode.Mode.PAUSED)
class GpuLossOrderingTest {
    @Implements(FakeInputWriter::class)
    class NoInputRings {
        companion object {
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun releaseAllRingSlots() {}
        }
    }

    private val context: Context get() = RuntimeEnvironment.getApplication()
    private lateinit var previousDriver: File
    private lateinit var unconfirmed: File

    @Before fun setup() {
        ReflectionHelpers.setStaticField(GpuDeviceState::class.java, "lost", false)
        WaylandCompositor.setDeviceLostListener(null)
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("gpu_auto_trial", Context.MODE_PRIVATE).edit().clear().commit()
        SessionState.running = true
        SessionState.mode = SessionService.MODE_STEAM
        SessionState.phase = SessionPhase.STARTING_GUEST
        SessionState.guestPid = 42
        SessionState.firstFrameSeen = false
        SessionState.suspended = false
        SessionState.failureCode = null
        SessionState.failureMessage = null
        SessionState.failureStatus = null
        SessionState.logDirectory = null
        SessionState.eventsFile = null
        SessionState.endListener = null
        SessionPrefs.setAndroidDriver(context, "trial-display")
        SessionPrefs.setLinuxDriver(context, "trial-linux")
        SessionPrefs.setGpuAutoInstalled(context, setOf("trial-display", "trial-linux", "previous-display", "previous-linux"))
        AutoDriverTrial.begin(context, "trial@1", "Trial", "trial-display", "trial-linux", "previous-display", "previous-linux")
        AutoDriverTrial.sessionStarting(context, "trial-display", "trial-linux")
        previousDriver = File(context.filesDir, "graphics_driver/previous-display/payload")
        previousDriver.parentFile!!.mkdirs()
        previousDriver.writeText("preserve until a live session confirms the trial")
        val prefix = if (SessionPrefs.activeSteamClientArch(context) == "x86_64") ".droiddeck-x64/" else ""
        unconfirmed = File(LinuxRuntime.rootDir(context), "root/${prefix}.local/share/Steam/package/droiddeck-unconfirmed")
        unconfirmed.parentFile!!.mkdirs()
        unconfirmed.writeText("unconfirmed")
    }

    private fun loseDevice() {
        ReflectionHelpers.callStaticMethod<Void>(WaylandCompositor::class.java, "onDeviceLost")
        assertTrue(WaylandCompositor.hasDeviceLost())
    }

    private fun awaitTrialWork() {
        Thread.getAllStackTraces().keys.filter { it.name == "gpu-driver-trial" }.forEach {
            it.join(5000)
            assertFalse("driver confirmation did not finish", it.isAlive)
        }
    }

    private fun frameThatRacesNativeLoss() {
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val listener = ReflectionHelpers.getField<Runnable>(activity, "firstFrameListener")
        val failure = AtomicReference<Throwable?>()
        val frame = Thread {
            try { listener.run() } catch (error: Throwable) { failure.set(error) }
        }
        synchronized(SessionState) {
            frame.start()
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (frame.state != Thread.State.BLOCKED && frame.isAlive && System.nanoTime() < deadline) Thread.sleep(1)
            assertEquals("frame must reach the session-state lock", Thread.State.BLOCKED, frame.state)
            assertTrue(frame.stackTrace.any { it.className == SessionEvents::class.java.name })
            loseDevice()
            assertNull("service has not processed loss yet", SessionState.failureCode)
        }
        frame.join(5000)
        assertFalse(frame.isAlive)
        failure.get()?.let { throw AssertionError("first-frame callback failed", it) }
        awaitTrialWork()
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test fun nativeLossBeforeFailureHandlingPreventsFirstFrameReadiness() {
        loseDevice()
        SessionEvents.firstFrame()
        awaitTrialWork()
        assertFalse(SessionState.firstFrameSeen)
        assertNotEquals(SessionPhase.READY, SessionState.phase)
    }

    @Test fun nativeLossBeforeFailureHandlingPreventsGuestReadiness() {
        SessionState.firstFrameSeen = true
        loseDevice()
        SessionEvents.guestStarted(43)
        awaitTrialWork()
        assertNotEquals(SessionPhase.READY, SessionState.phase)
        assertEquals(43, SessionState.guestPid)
        assertEquals("trial@1", AutoDriverTrial.current(context)?.release)
    }

    @Test fun nativeLossBeforeFailureHandlingPreventsResumeReadiness() {
        SessionState.firstFrameSeen = true
        loseDevice()
        assertEquals(SessionPhase.FAILED, SessionEvents.resumePhase())
    }

    @Test fun aFrameThatPassedTheEntryCheckCannotMarkALostDeviceReady() {
        frameThatRacesNativeLoss()
        assertFalse(SessionState.firstFrameSeen)
        assertNotEquals(SessionPhase.READY, SessionState.phase)
    }

    @Test fun aFrameThatPassedTheEntryCheckCannotConfirmTheTrial() {
        frameThatRacesNativeLoss()
        assertEquals("trial@1", AutoDriverTrial.current(context)?.release)
        assertTrue(previousDriver.isFile)
    }

    @Test fun aFrameThatPassedTheEntryCheckCannotDeleteTheRepairMarker() {
        frameThatRacesNativeLoss()
        assertTrue(unconfirmed.isFile)
    }

    @Test fun aTrialConfirmationExecutedAfterLossKeepsTheRollbackFiles() {
        loseDevice()
        AutoDriverTrial.sessionReady()
        assertEquals("trial@1", AutoDriverTrial.current(context)?.release)
        assertTrue(previousDriver.isFile)
        assertTrue("previous-display" in SessionPrefs.gpuAutoInstalled(context))
    }

    @Test fun aLateClientConfirmationKeepsTheRepairMarker() {
        loseDevice()
        SteamRepair.clientShown(context)
        assertTrue(unconfirmed.isFile)
    }

    @Test fun aHealthyTrialStillConfirmsAndPrunesThePreviousDriver() {
        AutoDriverTrial.sessionReady()
        assertNull(AutoDriverTrial.current(context))
        assertFalse(previousDriver.exists())
        SteamRepair.clientShown(context)
        assertFalse(unconfirmed.exists())
    }

    @Test fun repeatedServiceLossReportsNotifyOnlyOnce() {
        SessionState.running = false
        val service = Robolectric.buildService(SessionService::class.java).get()
        val notifications = AtomicInteger()
        SessionState.endListener = { notifications.incrementAndGet() }
        repeat(3) { ReflectionHelpers.callInstanceMethod<Void>(service, "gpuDeviceLost") }
        assertEquals(1, notifications.get())
    }

    @Test fun queuedListenerAndStartCommandShareTheSameLossClaim() {
        SessionState.running = false
        val service = Robolectric.buildService(SessionService::class.java).get()
        val notifications = AtomicInteger()
        SessionState.endListener = { notifications.incrementAndGet() }
        WaylandCompositor.setDeviceLostListener(ReflectionHelpers.getField(service, "deviceLostListener"))
        loseDevice()
        service.onStartCommand(Intent(), 0, 1)
        assertEquals(1, notifications.get())
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(1, notifications.get())
    }

    @Test fun aReplacementServiceCanHandleTheStickyLossOnce() {
        SessionState.running = false
        val notifications = AtomicInteger()
        SessionState.endListener = { notifications.incrementAndGet() }
        repeat(2) {
            val service = Robolectric.buildService(SessionService::class.java).get()
            repeat(2) { ReflectionHelpers.callInstanceMethod<Void>(service, "gpuDeviceLost") }
        }
        assertEquals(2, notifications.get())
    }

    @Test fun repeatedLossWaitsForTheOriginalTeardownBeforeNotifying() {
        SessionState.mode = SessionService.MODE_RUN
        val service = Robolectric.buildService(SessionService::class.java).get()
        val notifications = AtomicInteger()
        SessionState.endListener = { notifications.incrementAndGet() }
        val work = ArrayDeque<Runnable>()
        val executor = object : AbstractExecutorService() {
            override fun execute(command: Runnable) { work.addLast(command) }
            override fun shutdown() {}
            override fun shutdownNow(): MutableList<Runnable> = mutableListOf()
            override fun isShutdown() = false
            override fun isTerminated() = false
            override fun awaitTermination(timeout: Long, unit: TimeUnit) = false
        }
        ReflectionHelpers.setField(service, "suspendController", SessionSuspendController(
            sessionRoot = { -1 }, helperRoots = { emptyList() },
            suspendAudio = {}, resumeAudio = {}, executor = executor,
        ))
        try {
            ReflectionHelpers.callInstanceMethod<Void>(service, "gpuDeviceLost")
            assertFalse(SessionState.running)
            assertEquals(1, work.size)
            assertEquals(0, notifications.get())
            ReflectionHelpers.callInstanceMethod<Void>(service, "gpuDeviceLost")
            assertEquals("teardown is still queued", 0, notifications.get())
        } finally {
            while (work.isNotEmpty()) work.removeFirst().run()
            Thread.getAllStackTraces().keys.filter { it.name == "gog-session-stop" }.forEach {
                it.join(5000)
                assertFalse(it.isAlive)
            }
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertEquals(1, notifications.get())
    }

    @After fun cleanup() {
        awaitTrialWork()
        SessionState.running = false
        SessionState.endListener = null
        SessionState.firstFrameSeen = false
        SessionState.failureCode = null
        SessionState.failureMessage = null
        SessionState.failureStatus = null
        SessionState.guestPid = -1
        SessionState.phase = SessionPhase.IDLE
        WaylandCompositor.setDeviceLostListener(null)
        ReflectionHelpers.setStaticField(GpuDeviceState::class.java, "lost", false)
        ReflectionHelpers.setStaticField(AutoDriverTrial::class.java, "watching", null)
        shadowOf(Looper.getMainLooper()).idle()
    }
}
