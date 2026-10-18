package com.droiddeck.launcher.glasses

import android.app.Application
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.SteamVr
import com.droiddeck.launcher.session.LoadingState
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
@LooperMode(LooperMode.Mode.PAUSED)
class GlassesSteamVrTest {
    private val commands = mutableListOf<Int>()
    private val handler = Handler(Looper.getMainLooper())
    private lateinit var restore: () -> Unit
    private fun field(name: String) = Glasses::class.java.getDeclaredField(name).apply { isAccessible = true }
    private val spatialGate get() = field("steamVrSpatialGate").get(null) as SteamVrSpatialGate

    @Before fun prepare() {
        spatialGate.cancel()
        val fields = listOf("handler", "app", "model").associateWith { field(it).get(null) }
        @Suppress("UNCHECKED_CAST")
        val flows = listOf("_state", "_settings").map { field(it).get(null) as MutableStateFlow<Any> }
        val values = flows.map { it.value }
        val mode = SessionState.mode
        restore = {
            fields.forEach { (name, value) -> field(name).set(null, value) }
            flows.zip(values).forEach { (flow, value) -> flow.value = value }
            SessionState.mode = mode
        }
        field("handler").set(null, handler)
        field("app").set(null, RuntimeEnvironment.getApplication())
        field("model").set(null, VitureProtocol.model(0x1019, null))
        field("link").set(null, object : GlassesLink {
            override val hasImu = false
            override fun send(msgId: Int, payload: ByteArray): Boolean {
                if (msgId == VitureProtocol.MSG_DISPLAY) commands.add(payload[0].toInt() and 255)
                return true
            }
            override fun close() {}
        })
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = GlassesState(present = true, connected = true, controllable = true,
            displayId = 2, width = 1920, height = 1080)
    }

    @After fun cleanUp() {
        handler.removeCallbacksAndMessages(null)
        spatialGate.cancel()
        field("link").set(null, null)
        (field("modeState").get(null) as GlassesModeState).apply { manual(); sent(false, 0) }
        SessionState.steamVr = false
        restore()
    }

    @Test fun defaultLaunchAndRunningStatusKeepTheDisplayIn2d() = withLaunch(auto3d = false) { activity ->
        status(activity, SteamVr.Status.READY)
        status(activity, SteamVr.Status.RUNNING)
        status(activity, SteamVr.Status.READY)
        assertEquals(emptyList<Int>(), commands)
        assertFalse(Glasses.state.value.switching3d)
    }

    @Test fun optedInLaunchWaitsForRunningAndRestoresOnlyAfterStopping() = withLaunch(auto3d = true) { activity ->
        status(activity, SteamVr.Status.READY)
        status(activity, SteamVr.Status.READY)
        assertEquals("Startup must not change the display mode", emptyList<Int>(), commands)
        status(activity, SteamVr.Status.RUNNING)
        status(activity, SteamVr.Status.RUNNING)
        assertEquals(listOf(0x32), commands)
        assertTrue(Glasses.state.value.switching3d)
        status(activity, SteamVr.Status.UNAVAILABLE)
        assertEquals("A failed status poll is not a stop", listOf(0x32), commands)
        status(activity, SteamVr.Status.READY)
        assertEquals(listOf(0x32, 0x34), commands)
        assertFalse(Glasses.state.value.switching3d)
    }

    @Test fun ignoredManualSwitchKeepsThePending2dRestore() {
        val mode = field("modeState").get(null) as GlassesModeState
        mode.startVr()
        mode.stopVr()
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = state.value.copy(width = 640, height = 480, noSignal = true)
        Glasses.set3d(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(emptyList<Int>(), commands)
        assertTrue(mode.restorePending)
        state.value = state.value.copy(width = 1920, height = 1080, noSignal = false)
        Glasses.set3d(false)
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(listOf(0x34), commands)
        assertFalse(mode.restorePending)
    }

    @Test fun absentPreferenceDefaultsTo2dAndAnExplicitOptInSurvivesReload() {
        val prefs = RuntimeEnvironment.getApplication().getSharedPreferences("glasses", Context.MODE_PRIVATE)
        prefs.edit().clear().commit()
        fun load() = Glasses::class.java.getDeclaredMethod("load").apply { isAccessible = true }
            .invoke(Glasses) as GlassesSettings
        assertFalse(load().auto3d)
        prefs.edit().putBoolean("auto3d", true).commit()
        assertTrue(load().auto3d)
        prefs.edit().putBoolean("auto3d", false).commit()
        assertFalse(load().auto3d)
    }

    @Test fun manualStartOnDisplayOnlyGlassesRequestsPollingWithoutDuplicatingLaunch() {
        @Suppress("UNCHECKED_CAST")
        val state = field("_state").get(null) as MutableStateFlow<GlassesState>
        state.value = GlassesState(present = true, displayId = 2, width = 1920, height = 1080)
        withLaunch(auto3d = false) { activity ->
            SessionState.steamVr = false
            fun action(name: String) = SessionActivity::class.java.getDeclaredMethod("steamVrAction", String::class.java)
                .apply { isAccessible = true }.invoke(activity, name)
            action("start")
            assertTrue("A manual start must keep status polling alive", SessionState.steamVr)
            shadowOf(Looper.getMainLooper()).idle()
            val worker = SessionActivity::class.java.getDeclaredField("steamVrWorker")
                .apply { isAccessible = true }.get(null) as ThreadPoolExecutor
            assertEquals(1, worker.queue.size)
            status(activity, SteamVr.Status.READY)
            assertEquals("Polling must not launch a duplicate instance", 1, worker.queue.size)
            status(activity, SteamVr.Status.RUNNING)
            status(activity, SteamVr.Status.UNAVAILABLE)
            assertTrue(SessionState.steamVr)
            action("stop")
            assertFalse(SessionState.steamVr)
        }
    }

    @Test fun preparationCallbackRunsAfterSuspensionAndCancelWorksBeforeRunning() {
        @Suppress("UNCHECKED_CAST")
        val settings = field("_settings").get(null) as MutableStateFlow<GlassesSettings>
        settings.value = GlassesSettings(positionalTracking = true, handControllers = true)
        val saved = settings.value
        var prepared = 0
        repeat(2) {
            Glasses.prepareSteamVrStart {
                assertFalse(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
                prepared++
            }
        }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals(2, prepared)
        Glasses.setVrActive(false)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        Glasses.cancelSteamVrStart()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        assertEquals(saved, settings.value)
    }

    @Test fun rapidRestartDoesNotLaunchTheCancelledPreparedRequest() = withLaunch(auto3d = false) { activity ->
        SessionState.steamVr = false
        val action = SessionActivity::class.java.getDeclaredMethod("steamVrAction", String::class.java)
            .apply { isAccessible = true }
        action.invoke(activity, "start")
        action.invoke(activity, "stop")
        action.invoke(activity, "start")
        shadowOf(Looper.getMainLooper()).idle()
        val worker = SessionActivity::class.java.getDeclaredField("steamVrWorker")
            .apply { isAccessible = true }.get(null) as ThreadPoolExecutor
        assertTrue(SessionState.steamVr)
        assertEquals("Only stop and the latest start should reach the worker", 2, worker.queue.size)
    }

    @Test fun duplicateRunningKeepsDeadlineAndStoppingCancelsThePendingResume() {
        Glasses.prepareSteamVrStart()
        Glasses.setVrActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        val deadline = spatialGate.deadline!!
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
        var prepared = false
        Glasses.prepareSteamVrStart { prepared = true }
        Glasses.setVrActive(true)
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(prepared)
        assertEquals(deadline, spatialGate.deadline)
        assertFalse(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        Glasses.setVrActive(false)
        Glasses.prepareSteamVrStart()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertFalse(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        assertNull(spatialGate.deadline)
    }

    @Test fun restartWhileDisplayStillReportsRunningGetsANewTrackingDeadline() {
        Glasses.setVrActive(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        assertTrue(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        Glasses.cancelSteamVrStart()
        Glasses.prepareSteamVrStart()
        shadowOf(Looper.getMainLooper()).idle()
        assertTrue(Glasses.state.value.vrActive)
        assertFalse(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        Glasses.setVrActive(true)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1_999))
        assertFalse(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(1))
        assertTrue(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
    }

    @Test fun endedSessionIgnoresLateRunningStatus() = withLaunch(auto3d = true) { activity ->
        SessionActivity::class.java.getDeclaredField("ended").apply { isAccessible = true }.set(activity, true)
        status(activity, SteamVr.Status.RUNNING)
        assertFalse(Glasses.state.value.vrActive)
        assertNull(spatialGate.deadline)
        assertTrue(spatialGate.spatialEnabled(true, SystemClock.uptimeMillis()))
        assertTrue(commands.isEmpty())
    }

    private fun status(activity: SessionActivity, status: SteamVr.Status) {
        SessionActivity::class.java.getDeclaredMethod("onSteamVrStatus", SteamVr.Status::class.java)
            .apply { isAccessible = true }.invoke(activity, status)
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun withLaunch(auto3d: Boolean, check: (SessionActivity) -> Unit) {
        @Suppress("UNCHECKED_CAST")
        val settings = field("_settings").get(null) as MutableStateFlow<GlassesSettings>
        settings.value = GlassesSettings(auto3d = auto3d)
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        SessionActivity::class.java.getDeclaredField("loading").apply { isAccessible = true }
            .set(activity, LoadingState(activity).apply { visible = false })
        SessionState.steamVr = true
        SessionState.mode = SessionService.MODE_STEAM
        // Hold the external worker so status transitions can be exercised
        // without launching a Linux runtime from the host test process.
        val worker = SessionActivity::class.java.getDeclaredField("steamVrWorker")
            .apply { isAccessible = true }.get(null) as ThreadPoolExecutor
        val blocked = CountDownLatch(1)
        val release = CountDownLatch(1)
        worker.execute { blocked.countDown(); release.await(30, TimeUnit.SECONDS) }
        try {
            assertTrue(blocked.await(5, TimeUnit.SECONDS))
            check(activity)
        } finally {
            worker.queue.clear()
            release.countDown()
        }
    }
}
