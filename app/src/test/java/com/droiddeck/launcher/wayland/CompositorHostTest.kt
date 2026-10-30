package com.droiddeck.launcher.wayland

import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.Display
import com.droiddeck.launcher.session.SessionPrefs
import android.view.SurfaceView
import android.view.SurfaceHolder
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.TpUbwcHint
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.session.CompositorPreparation
import com.droiddeck.launcher.session.SessionPhase
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.CancellationException
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [CompositorHostTest.Native::class,
    CompositorHostTest.Driver::class, CompositorHostTest.GpuHint::class])
@LooperMode(LooperMode.Mode.PAUSED)
class CompositorHostTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Before fun setUp() {
        ReflectionHelpers.setStaticField(CompositorHost::class.java, "started", false)
        ReflectionHelpers.setStaticField(CompositorHost::class.java, "attached", null)
        ReflectionHelpers.setStaticField(CompositorHost::class.java, "vsyncRunning", false)
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        listOf(Native.fpsLimits, Native.startHdr, Native.hdrRequests, Native.displayIds,
            Native.runtimes, Native.drivers, Native.starts, Native.surfaces, Native.sizes).forEach { it.clear() }
        Driver.installs = 0
        Driver.beforeInstall = {}
        SessionState.sessionId = "test-session"
        SessionState.phase = SessionPhase.PREPARING
        SessionState.stopRequested = false
        SessionState.hdr = false
        SessionState.running = true
        SessionState.firstFrameSeen = false
        SessionState.guestPid = -1
        WaylandCompositor.setFirstFrameListener { SessionEvents.firstFrame() }
    }

    @After fun tearDown() {
        CompositorHost.detach(null)
        ReflectionHelpers.setStaticField(SessionService::class.java, "compositorStart", null)
        WaylandCompositor.setFirstFrameListener(null)
        SessionState.running = false
        SessionState.firstFrameSeen = false
    }

    @Test fun backgroundPreparationStartsWithoutAWindowAndPlayAttachesToTheSameCompositor() {
        CompositorHost.prepare(app, 1280, 720, 60f, 0)
        assertEquals(listOf<Surface?>(null), Native.starts)
        assertFalse(ReflectionHelpers.getStaticField(CompositorHost::class.java, "vsyncRunning"))
        assertFalse(SessionState.firstFrameSeen)

        val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        withSurface { surface ->
            CompositorHost.attach(app, surface, 1280, 720, 120f, 0, display)
            assertEquals(id, Native.displayIds.last())
            assertEquals(1, Native.starts.size)
            assertEquals(1, Driver.installs)
            assertSame(surface, Native.surfaces.last())
            assertTrue(ReflectionHelpers.getStaticField(CompositorHost::class.java, "vsyncRunning"))
            assertEquals(120f, SessionState.refreshHz)
            assertFalse(SessionState.firstFrameSeen)

            ReflectionHelpers.callStaticMethod<Unit>(WaylandCompositor::class.java, "onFirstFramePresented")
            assertTrue(SessionState.firstFrameSeen)
        }
    }

    @Test fun nativeStreamingKeepsItsOwnRuntimeAndSystemDriver() {
        val runtime = java.io.File(app.cacheDir, "xcloud-wayland").path
        withSurface { surface ->
            assertTrue(CompositorHost.startOrAttach(app, surface, runtime, null, null,
                "/native", 1920, 1080, 60f, 0))
            assertEquals(listOf(runtime), Native.runtimes)
            assertEquals(listOf<String?>(null), Native.drivers)
            assertEquals(0, Driver.installs)
            assertFalse(CompositorHost.startOrAttach(app, surface, runtime, null, null,
                "/native", 1920, 1080, 120f, 0))
            assertEquals(1, Native.starts.size)
            assertSame(surface, Native.surfaces.last())
        }
    }

    @Test fun preparedOutputKeepsTheSteamUiLimitAndReleasesItForGames() {
        CompositorHost.prepare(app, 1280, 720, 120f, SessionPrefs.FPS_UNPACED)
        withSurface { surface ->
            CompositorHost.attach(app, surface, 1280, 720, 120f, SessionPrefs.FPS_UNPACED,
                uiLimit = SessionPrefs.steamUiFpsLimit(120f))
            assertEquals(60, Native.fpsLimits.last())
            CompositorHost.updatePacing(120f, SessionPrefs.FPS_UNPACED)
            assertEquals(SessionPrefs.FPS_UNPACED, Native.fpsLimits.last())
            assertEquals(120f, SessionState.refreshHz)
        }
    }

    @Test fun lateBackgroundPreparationCannotDetachOrReconfigureAVisibleSession() {
        withSurface { surface ->
            CompositorHost.prepare(app, 1280, 720, 120f, 0)
            CompositorHost.attach(app, surface, 1280, 720, 120f, 0)
            CompositorHost.prepare(app, 1920, 1080, 60f, 30)
            assertEquals(listOf(1280 to 720), Native.sizes)
            assertEquals(120f, SessionState.refreshHz)
            assertEquals(listOf(surface), Native.surfaces)
            assertEquals(listOf<Surface?>(null), Native.starts)
        }
    }

    @Test fun aRetainedCompositorCanPrepareAnotherSessionWithItsNewOutputSize() {
        CompositorHost.prepare(app, 1280, 720, 60f, 0)
        CompositorHost.detach(null)
        CompositorHost.newSession(app)
        CompositorHost.prepare(app, 1920, 1080, 60f, 30)
        assertEquals(listOf(1280 to 720, 1920 to 1080), Native.sizes)
        assertEquals(1, Native.starts.size)
        assertEquals(1, Driver.installs)
        assertFalse(ReflectionHelpers.getStaticField(CompositorHost::class.java, "vsyncRunning"))
    }

    @Test @Config(sdk = [33])
    fun backgroundHdrIsConfiguredBeforeBootAndPreservedWhenStartingAGame() {
        app.getSharedPreferences("session", Context.MODE_PRIVATE).edit().putBoolean("vsyncThread", false).commit()
        val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        val display = app.getSystemService(DisplayManager::class.java).getDisplay(id)
        shadowOf(display).setDisplayHdrCapabilities(id, 1000f, 500f, 0.1f, Display.HdrCapabilities.HDR_TYPE_HDR10)
        SessionPrefs.setHdr(app, SessionService.MODE_STEAM, true)
        SessionState.mode = SessionService.MODE_STEAM
        val service = Robolectric.buildService(SessionService::class.java).get()
        ReflectionHelpers.setField(service, "sessionDisplayId", id)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "prepareCompositor",
            ReflectionHelpers.ClassParameter.from(CompositorPreparation::class.java, CompositorPreparation()))
        assertEquals(listOf(true), Native.startHdr)
        assertEquals(listOf(WaylandCompositor.HDR_MODE_ON), Native.hdrRequests)
        assertEquals(listOf<Surface?>(null), Native.starts)
        assertEquals(listOf(id), Native.displayIds)
        withSurface { surface ->
            CompositorHost.attach(app, surface, 1920, 1080, 60f, 0, display)
            assertTrue(SessionService.launchGame(app, "570"))
            val request = requireNotNull(shadowOf(app).nextStartedService)
            assertEquals("570", request.getStringExtra(SessionService.EXTRA_GAME_ID))
            service.onStartCommand(request, 0, 1)
            assertTrue(SessionState.hdr)
            assertEquals(1, Native.starts.size)
            assertEquals(listOf(WaylandCompositor.HDR_MODE_ON), Native.hdrRequests)
        }
    }

    @Test @Config(sdk = [33])
    fun anSdrSelectedDisplayDoesNotInheritHdrFromThePhone() {
        val displays = app.getSystemService(DisplayManager::class.java)
        val phone = displays.getDisplay(Display.DEFAULT_DISPLAY)
        shadowOf(phone).setDisplayHdrCapabilities(phone.displayId, 1000f, 500f, 0.1f, Display.HdrCapabilities.HDR_TYPE_HDR10)
        val id = org.robolectric.shadows.ShadowDisplayManager.addDisplay("w1920dp-h1080dp")
        SessionPrefs.setHdr(app, SessionService.MODE_STEAM, true)
        val service = Robolectric.buildService(SessionService::class.java).get()
        ReflectionHelpers.setField(service, "sessionDisplayId", id)
        ReflectionHelpers.callInstanceMethod<Unit>(service, "prepareCompositor",
            ReflectionHelpers.ClassParameter.from(CompositorPreparation::class.java, CompositorPreparation()))
        assertEquals(listOf(id), Native.displayIds)
        assertEquals(listOf(WaylandCompositor.HDR_MODE_OFF), Native.hdrRequests)
        assertEquals(listOf(false), Native.startHdr)
    }

    @Test fun preparationFailureIsRetainedForPlayInsteadOfReportedAsReady() {
        val failure = IllegalStateException("driver preparation failed")
        Driver.beforeInstall = { throw failure }
        val preparation = CompositorPreparation()
        ReflectionHelpers.setStaticField(SessionService::class.java, "compositorStart", preparation)
        val error = runCatching { CompositorHost.prepare(app, 1280, 720, 60f, 0, checkCurrent = preparation::checkCurrent) }.exceptionOrNull()!!
        preparation.result.completeExceptionally(error)
        assertSame(preparation, SessionService.compositorPreparation)
        val delivered = runCatching { runBlocking { preparation.result.await() } }.exceptionOrNull()
        assertEquals(failure.message, delivered?.message)
        assertFalse(CompositorHost.isStarted)
        assertTrue(Native.starts.isEmpty())
    }

    @Test fun stoppingDuringDriverPreparationPreventsNativeStartup() = blockedPreparation { preparation ->
        preparation.result.cancel()
    }

    @Test fun aLatePreparationCannotInitializeTheNextSession() = blockedPreparation {
        SessionState.sessionId = "next-session"
        assertNull(SessionService.compositorPreparation)
    }

    private fun blockedPreparation(invalidate: (CompositorPreparation) -> Unit) {
        val preparation = CompositorPreparation()
        ReflectionHelpers.setStaticField(SessionService::class.java, "compositorStart", preparation)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Driver.beforeInstall = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val startup = FutureTask {
            runCatching { CompositorHost.prepare(app, 1280, 720, 60f, 0, checkCurrent = preparation::checkCurrent) }
        }
        Thread(startup, "test-cancel-preparation").apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            invalidate(preparation)
            release.countDown()
            assertTrue(startup.get(5, TimeUnit.SECONDS).exceptionOrNull() is CancellationException)
            assertTrue(Native.starts.isEmpty())
            assertFalse(CompositorHost.isStarted)
        } finally {
            release.countDown()
            startup.get(5, TimeUnit.SECONDS)
        }
    }

    @Test fun foregroundPreparationAlsoKeepsSurfaceCallbacksResponsive() = responsivePreparation(background = false)

    @Test fun recreatingTheSameHolderKeepsOnlyTheLatestPreparationWaiter() {
        val preparation = CompositorPreparation()
        ReflectionHelpers.setStaticField(SessionService::class.java, "compositorStart", preparation)
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val view = SurfaceView(activity)
        ReflectionHelpers.setField(activity, "surfaceView", view)
        val scope = ReflectionHelpers.getField<CoroutineScope>(activity, "glassesScope")
        try {
            activity.surfaceCreated(view.holder)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, scope.coroutineContext[Job]!!.children.count { it.isActive })
            activity.surfaceDestroyed(view.holder)
            activity.surfaceCreated(view.holder)
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(1, scope.coroutineContext[Job]!!.children.count { it.isActive })
            activity.surfaceDestroyed(view.holder)
            preparation.result.complete(Unit)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(Native.surfaces.isEmpty())
        } finally {
            scope.cancel()
        }
    }

    @Test fun playAndSurfaceCallbacksStayResponsiveWhileTheBackgroundDriverIsPreparing() = responsivePreparation(background = true)

    private fun responsivePreparation(background: Boolean) {
        SessionState.running = background
        val preparation = CompositorPreparation()
        if (background) ReflectionHelpers.setStaticField(SessionService::class.java, "compositorStart", preparation)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Driver.beforeInstall = { entered.countDown(); check(release.await(5, TimeUnit.SECONDS)) }
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val view = object : SurfaceView(activity) {
            private val testHolder = object : SurfaceHolder by super.getHolder() {
                override fun getSurfaceFrame() = android.graphics.Rect(0, 0, 1280, 720)
            }
            override fun getHolder(): SurfaceHolder = testHolder
        }
        ReflectionHelpers.setField(activity, "surfaceView", view)
        val scope = ReflectionHelpers.getField<CoroutineScope>(activity, "glassesScope")
        val startup = if (background) FutureTask {
            try { CompositorHost.prepare(app, 1280, 720, 60f, 0) }
            finally { preparation.result.complete(Unit) }
        }.also { Thread(it, "test-prepare").apply { isDaemon = true; start() } } else null
        try {
            activity.surfaceCreated(view.holder)
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            activity.surfaceChanged(view.holder, 0, 800, 600)
            activity.surfaceChanged(view.holder, 0, 1000, 600)
            activity.surfaceDestroyed(view.holder)
            val responsive = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { responsive.countDown() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0L, responsive.count)
            assertTrue(scope.coroutineContext[Job]!!.children.none { it.isActive })
            release.countDown()
            startup?.get(5, TimeUnit.SECONDS)
            CompositorHost.detach(null)
            assertFalse(SessionState.firstFrameSeen)
            if (background) assertTrue(Native.starts.single() == null) else assertTrue(Native.starts.isEmpty())
        } finally {
            release.countDown()
            startup?.get(5, TimeUnit.SECONDS)
            scope.cancel()
            CompositorHost.detach(null)
            shadowOf(Looper.getMainLooper()).idle()
        }
    }

    private fun withSurface(block: (Surface) -> Unit) {
        val surface = Surface(android.graphics.SurfaceTexture(0))
        try { block(surface) } finally {
            CompositorHost.detach(surface)
            surface.release()
        }
    }

    @Implements(value = TurnipDriver::class, isInAndroidSdk = false)
    class Driver {
        @Implementation fun install(): String? { installs++; beforeInstall(); return null }
        companion object {
            var installs = 0
            var beforeInstall: () -> Unit = {}
        }
    }

    @Implements(value = TpUbwcHint::class, isInAndroidSdk = false)
    class GpuHint {
        @Implementation fun applyToProcess(context: Context) = Unit
    }

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class Native {
        companion object {
            val fpsLimits = mutableListOf<Int>()
            val startHdr = mutableListOf<Boolean>()
            val hdrRequests = mutableListOf<Int>()
            val displayIds = mutableListOf<Int>()
            val runtimes = mutableListOf<String>()
            val drivers = mutableListOf<String?>()
            val starts = mutableListOf<Surface?>()
            val surfaces = mutableListOf<Surface?>()
            val sizes = mutableListOf<Pair<Int, Int>>()
            @JvmStatic @Implementation fun __staticInitializer__() = Unit
            @JvmStatic @Implementation fun nativeStartWithSurface(surface: Surface?, runtime: String,
                driver: String?, library: String?, nativeLibs: String?) {
                startHdr += SessionState.hdr
                starts += surface; runtimes += runtime; drivers += driver
            }
            @JvmStatic @Implementation fun nativeSetSurface(surface: Surface?) { surfaces += surface }
            @JvmStatic @Implementation fun nativeSetFpsLimit(limit: Int) { fpsLimits += limit }
            @JvmStatic @Implementation fun nativeSetOutputSize(width: Int, height: Int) { sizes += width to height }
            @JvmStatic @Implementation fun nativeSetHdrRequest(mode: Int, source: String, dxvk: Boolean, zeroCopy: Boolean) { hdrRequests += mode }
            @JvmStatic @Implementation fun nativeSetHdrDisplay(id: Int, name: String, formats: String, hdr10: Boolean,
                max: Float, average: Float, min: Float, ratioAvailable: Boolean, ratio: Float, sdk: Int) { displayIds += id }
        }
    }
}
