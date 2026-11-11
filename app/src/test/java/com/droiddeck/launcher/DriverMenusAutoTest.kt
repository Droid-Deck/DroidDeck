package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.gpu.TpUbwcHint
import com.droiddeck.launcher.gpu.TurnipDriver
import com.droiddeck.launcher.gpu.TurnipReleases
import com.droiddeck.launcher.gpu.VulkanInfo
import com.droiddeck.launcher.gpu.SystemVulkanDriver
import com.droiddeck.launcher.wayland.CompositorHost
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowBuild
import org.robolectric.shadows.ShadowLooper
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [DriverMenusAutoTest.XclipseDriver::class])
class DriverMenusAutoTest {
    @Implements(SystemVulkanDriver::class)
    class XclipseDriver {
        companion object {
            var enabled = false
            @JvmStatic @Implementation fun isPowerVr() = enabled
            @JvmStatic @Implementation fun isXclipse() = enabled
            @JvmStatic @Implementation fun gpuName() = if (enabled) "Xclipse" else "PowerVR"
        }
    }

    @Before fun reset() {
        XclipseDriver.enabled = false
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "resolved", false)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "cached", null)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "activeMode", null)
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowBuild.setModel("Quest 3")
        ShadowBuild.setDevice("eureka")
    }

    @Test fun unsupportedXclipseFallsBackThroughAutoAndFlagsDisplayRestart() {
        XclipseDriver.enabled = true
        ShadowBuild.setModel("Generic phone")
        ShadowBuild.setDevice("generic")
        ReflectionHelpers.setStaticField(VulkanInfo::class.java, "cached", mapOf("device" to "Xclipse 540"))
        val originalFetch = TurnipReleases.fetch
        TurnipReleases.fetch = { _, _, _ -> "[]" }
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.setAndroidDriver(activity, "old-display")
        SessionPrefs.setLinuxDriver(activity, "old-linux")
        SessionPrefs.setGpuAutoInstalled(activity, setOf("old-display", "old-linux"))
        ReflectionHelpers.setStaticField(CompositorHost::class.java, "started", true)
        CompositorHost.displayChoice = "old-display"
        try {
            val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
            menus.ensureAuto(force = true)
            val deadline = System.currentTimeMillis() + 10000
            while (menus.releaseChecking && System.currentTimeMillis() < deadline) {
                Thread.sleep(10)
                ShadowLooper.idleMainLooper()
            }
            assertFalse(menus.releaseChecking)
            assertEquals(activity.getString(R.string.drivers_powervr_builtin), menus.autoStatus)
            assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
            assertEquals("", SessionPrefs.linuxDriver(activity))
            assertTrue(menus.restartPending)
        } finally {
            TurnipReleases.fetch = originalFetch
            ReflectionHelpers.setStaticField(VulkanInfo::class.java, "cached", null)
            ReflectionHelpers.setStaticField(CompositorHost::class.java, "started", false)
            CompositorHost.displayChoice = null
            XclipseDriver.enabled = false
        }
    }

    @Test fun pickingTheAutoDisplayHandsBothHalvesToAuto() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.selectDriver(TurnipDriver.AUTO, linux = false)
        assertEquals(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.gpuDriverMode(activity))
        assertEquals(QuestExperimental.DRIVER, SessionPrefs.androidDriver(activity))
        assertEquals("", SessionPrefs.linuxDriver(activity))
    }

    @Test fun pickingTheAutoDisplayWithNoPairForTheGpuStillPutsTheDisplayOnAuto() {
        ShadowBuild.setModel("Generic phone")
        ShadowBuild.setDevice("generic")
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MIX)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        DriverMenus(activity, Handler(Looper.getMainLooper())).selectDriver(TurnipDriver.AUTO, linux = false)
        assertEquals(SessionPrefs.GPU_DRIVERS_AUTO, SessionPrefs.gpuDriverMode(activity))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
        assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
    }

    @Test fun selectingAutoUsesTheQuestPairWithoutStartingADownload() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.setMode(true)
        assertEquals(QuestExperimental.DRIVER, SessionPrefs.androidDriver(activity))
        assertEquals("", SessionPrefs.linuxDriver(activity))
        assertEquals(activity.getString(R.string.drivers_using_quest), menus.autoStatus)
        assertFalse(menus.releaseChecking)
        assertNull(menus.pairBusy)
        menus.ensureAuto(force = true)
        assertFalse(menus.releaseChecking)
    }

    @Test fun openingManualDriverSettingsRetainsTheSelectedPair() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.setAndroidDriver(activity, "chosen-display")
        SessionPrefs.setLinuxDriver(activity, "chosen-linux")
        DriverMenus(activity, Handler(Looper.getMainLooper())).ensureAuto(force = true)
        assertEquals("chosen-display", SessionPrefs.androidDriver(activity))
        assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
    }

    @Test fun driverMenusCanBeConstructedBeforeActivityContextIsAttached() {
        val menus = DriverMenus(Activity(), Handler(Looper.getMainLooper()))
        assertEquals(SessionPrefs.TP_UBWC_AUTO, menus.tpUbwcMode)
    }

    @Test fun refreshingDriversLoadsTheSavedUbwcModeAfterContextAttachment() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        assertEquals(SessionPrefs.TP_UBWC_ON, menus.tpUbwcMode)
        assertFalse(menus.tpUbwcRestartRequired())
    }

    @Test fun changingUbwcBeforeTheFirstSessionDoesNotRequireRestart() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        menus.refreshDrivers()
        assertFalse(menus.tpUbwcRestartRequired())
    }

    @Test fun recreatingTheMenuKeepsTheActiveHintRestartWarning() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        TpUbwcHint.forThisDevice(activity)
        SessionPrefs.setTpUbwcMode(activity, SessionPrefs.TP_UBWC_ON)
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        menus.refreshDrivers()
        assertTrue(menus.tpUbwcRestartRequired())
    }
}
