package com.droiddeck.launcher.wayland

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.Surface
import android.view.SurfaceView
import com.droiddeck.launcher.SessionActivity
import com.droiddeck.launcher.gpu.TpUbwcHint
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.session.SessionEvents
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
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
        Native.renderOnCommit.clear()
        Native.runtimes.clear()
        Native.drivers.clear()
        Native.starts.clear()
        Native.surfaces.clear()
        Native.sizes.clear()
        Driver.installs = 0
        Driver.beforeInstall = {}
        SessionState.running = true
        SessionState.firstFrameSeen = false
        SessionState.guestPid = -1
        WaylandCompositor.setFirstFrameListener { SessionEvents.firstFrame() }
    }

    @After fun tearDown() {
        CompositorHost.detach(null)
        WaylandCompositor.setFirstFrameListener(null)
        SessionState.running = false
        SessionState.firstFrameSeen = false
    }

    @Test fun backgroundPreparationStartsWithoutAWindowAndPlayAttachesToTheSameCompositor() {
        assertTrue(CompositorHost.startOrAttach(app, null, 1280, 720, 60f, 0))
        assertEquals(listOf<Surface?>(null), Native.starts)
        assertFalse(ReflectionHelpers.getStaticField(CompositorHost::class.java, "vsyncRunning"))
        assertFalse(SessionState.firstFrameSeen)

        val surface = Surface(android.graphics.SurfaceTexture(0))
        try {
            assertFalse(CompositorHost.startOrAttach(app, surface, 1280, 720, 120f, 0))
            assertEquals(1, Native.starts.size)
            assertEquals(1, Driver.installs)
            assertSame(surface, Native.surfaces.last())
            assertTrue(ReflectionHelpers.getStaticField(CompositorHost::class.java, "vsyncRunning"))
            assertEquals(120f, SessionState.refreshHz)
            assertFalse(SessionState.firstFrameSeen)

            ReflectionHelpers.callStaticMethod<Unit>(WaylandCompositor::class.java, "onFirstFramePresented")
            assertTrue(SessionState.firstFrameSeen)
        } finally {
            CompositorHost.detach(surface)
            surface.release()
        }
    }

    @Test fun nativeStreamingKeepsItsOwnRuntimeAndSystemDriver() {
        val surface = Surface(android.graphics.SurfaceTexture(0))
        val runtime = java.io.File(app.cacheDir, "xcloud-wayland").path
        try {
            assertTrue(CompositorHost.startOrAttach(app, surface, runtime, null, null,
                "/native", 1920, 1080, 60f, 0))
            assertEquals(listOf(runtime), Native.runtimes)
            assertEquals(listOf<String?>(null), Native.drivers)
            assertEquals(0, Driver.installs)
            assertFalse(CompositorHost.startOrAttach(app, surface, runtime, null, null,
                "/native", 1920, 1080, 120f, 0))
            assertEquals(1, Native.starts.size)
            assertSame(surface, Native.surfaces.last())
        } finally {
            CompositorHost.detach(surface)
            surface.release()
        }
    }

    @Test fun foregroundAndBackgroundStartsRespectTheCompositorTimingSettings() {
        val surface = Surface(android.graphics.SurfaceTexture(0))
        try {
            for (enabled in listOf(false, true)) {
                app.getSharedPreferences("session", Context.MODE_PRIVATE).edit()
                    .putBoolean("vsyncThread", enabled).putBoolean("renderOnCommit", enabled).commit()
                CompositorHost.startOrAttach(app, null, 1280, 720, 60f, 0)
                assertEquals(enabled, Native.renderOnCommit.last())
                assertEquals(enabled, ReflectionHelpers.getStaticField<Boolean>(CompositorHost::class.java, "ownVsyncThread"))
                CompositorHost.startOrAttach(app, surface, 1280, 720, 60f, 0)
                assertEquals(enabled, Native.renderOnCommit.last())
                assertEquals(enabled, ReflectionHelpers.getStaticField<Boolean>(CompositorHost::class.java, "ownVsyncThread"))
                CompositorHost.detach(surface)
            }
        } finally {
            CompositorHost.detach(surface)
            surface.release()
        }
    }

    @Test fun lateBackgroundPreparationCannotDetachOrReconfigureAVisibleSession() {
        val surface = Surface(android.graphics.SurfaceTexture(0))
        try {
            CompositorHost.startOrAttach(app, surface, 1280, 720, 120f, 0)
            assertFalse(CompositorHost.startOrAttach(app, null, 1920, 1080, 60f, 30))
            assertEquals(listOf(1280 to 720), Native.sizes)
            assertEquals(120f, SessionState.refreshHz)
            assertTrue(Native.surfaces.isEmpty())
            assertEquals(listOf(surface), Native.starts)
        } finally {
            CompositorHost.detach(surface)
            surface.release()
        }
    }

    @Test fun aRetainedCompositorCanPrepareAnotherSessionWithItsNewOutputSize() {
        CompositorHost.startOrAttach(app, null, 1280, 720, 60f, 0)
        CompositorHost.detach(null)
        CompositorHost.newSession(app)
        assertFalse(CompositorHost.startOrAttach(app, null, 1920, 1080, 60f, 30))
        assertEquals(listOf(1280 to 720, 1920 to 1080), Native.sizes)
        assertEquals(1, Native.starts.size)
        assertEquals(1, Driver.installs)
        assertFalse(ReflectionHelpers.getStaticField(CompositorHost::class.java, "vsyncRunning"))
    }

    @Test fun playAndSurfaceCallbacksStayResponsiveWhileTheBackgroundDriverIsPreparing() {
        val preparation = Job()
        ReflectionHelpers.setStaticField(SessionService::class.java, "compositorStart", preparation)
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        Driver.beforeInstall = {
            entered.countDown()
            check(release.await(5, TimeUnit.SECONDS))
        }
        val activity = Robolectric.buildActivity(SessionActivity::class.java).get()
        val view = SurfaceView(activity)
        ReflectionHelpers.setField(activity, "surfaceView", view)
        val scope = ReflectionHelpers.getField<CoroutineScope>(activity, "glassesScope")
        val startup = FutureTask {
            try { CompositorHost.startOrAttach(app, null, 1280, 720, 60f, 0) }
            finally { preparation.complete() }
        }
        Thread(startup, "test-compositor-prepare").apply { isDaemon = true; start() }
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            activity.surfaceCreated(view.holder)
            activity.surfaceChanged(view.holder, 0, 800, 600)
            activity.surfaceChanged(view.holder, 0, 1000, 600)
            activity.surfaceDestroyed(view.holder)
            val responsive = CountDownLatch(1)
            Handler(Looper.getMainLooper()).post { responsive.countDown() }
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(0L, responsive.count)
            assertFalse(preparation.isCompleted)
            assertTrue(scope.coroutineContext[Job]!!.children.any { it.isActive })

            ReflectionHelpers.setField(activity, "surfaceView", SurfaceView(activity))
            release.countDown()
            assertTrue(startup.get(5, TimeUnit.SECONDS))
            shadowOf(Looper.getMainLooper()).idle()
            assertTrue(scope.coroutineContext[Job]!!.children.none())
            assertTrue(Native.surfaces.isEmpty())
            assertFalse(SessionState.firstFrameSeen)
        } finally {
            release.countDown()
            startup.get(5, TimeUnit.SECONDS)
            scope.cancel()
            ReflectionHelpers.setStaticField(SessionService::class.java, "compositorStart", null)
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
            val renderOnCommit = mutableListOf<Boolean>()
            val runtimes = mutableListOf<String>()
            val drivers = mutableListOf<String?>()
            val starts = mutableListOf<Surface?>()
            val surfaces = mutableListOf<Surface?>()
            val sizes = mutableListOf<Pair<Int, Int>>()
            @JvmStatic @Implementation fun __staticInitializer__() = Unit
            @JvmStatic @Implementation fun nativeStartWithSurface(surface: Surface?, runtime: String,
                driver: String?, library: String?, nativeLibs: String?) { starts += surface; runtimes += runtime; drivers += driver }
            @JvmStatic @Implementation fun nativeSetSurface(surface: Surface?) { surfaces += surface }
            @JvmStatic @Implementation fun nativeSetOutputSize(width: Int, height: Int) { sizes += width to height }
            @JvmStatic @Implementation fun nativeSetRenderOnCommit(on: Boolean) { renderOnCommit += on }
            @JvmStatic @Implementation fun nativeSetFpsLimit(limit: Int) = Unit
            @JvmStatic @Implementation fun nativeSetLayerFrameRate(hz: Float) = Unit
            @JvmStatic @Implementation fun nativeSetOutputRefreshRate(hz: Float) = Unit
            @JvmStatic @Implementation fun nativeResetFirstFrame() = Unit
            @JvmStatic @Implementation fun nativeVsync(time: Long) = Unit
            @JvmStatic @Implementation fun nativeSetZeroCopy(on: Boolean) = Unit
            @JvmStatic @Implementation fun nativeSetHdrRequest(mode: Int, source: String, dxvk: Boolean, zeroCopy: Boolean) = Unit
            @JvmStatic @Implementation fun nativeSetHdrDisplay(id: Int, name: String, formats: String, hdr10: Boolean,
                max: Float, average: Float, min: Float, ratioAvailable: Boolean, ratio: Float, sdk: Int) = Unit
        }
    }
}
