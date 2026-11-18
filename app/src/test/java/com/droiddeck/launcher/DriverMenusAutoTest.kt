package com.droiddeck.launcher

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import com.droiddeck.launcher.session.QuestExperimental
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.session.MaliGraphicsMode
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import com.droiddeck.launcher.ui.GPU_MODE_AUTO
import com.droiddeck.launcher.ui.GPU_MODE_PAIR
import com.droiddeck.launcher.ui.GPU_MODE_MIX
import com.droiddeck.launcher.gpu.TpUbwcHint
import com.droiddeck.launcher.gpu.TurnipDriver
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
import org.robolectric.util.ReflectionHelpers
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class DriverMenusAutoTest {
    @Before fun reset() {
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "resolved", false)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "cached", null)
        ReflectionHelpers.setStaticField(TpUbwcHint::class.java, "activeMode", null)
        RuntimeEnvironment.getApplication().getSharedPreferences("session", Context.MODE_PRIVATE)
            .edit().clear().commit()
        ShadowBuild.setModel("Quest 3")
        ShadowBuild.setDevice("eureka")
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

    @Test fun fixedMaliPathsSurviveEveryOverallModeAndDisplayAuto() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        ReflectionHelpers.setField(menus, "gpu", GpuInfo("Mali-G720", 720, GpuInfo.Family.MALI_G720, "", false))
        for (choice in listOf(MaliGraphicsMode.PANVK, MaliGraphicsMode.BRIDGE, MaliGraphicsMode.VENUS)) {
            SessionPrefs.setMaliGraphicsMode(activity, choice)
            SessionPrefs.setLinuxDriver(activity, "chosen-linux")
            for (overall in listOf(GPU_MODE_AUTO, GPU_MODE_PAIR, GPU_MODE_MIX)) {
                menus.chooseMode(overall)
                menus.ensureAuto(force = true)
                assertEquals(choice, SessionPrefs.maliGraphicsMode(activity))
                assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
                menus.selectDriver(TurnipDriver.AUTO, linux = false)
                assertEquals(choice, SessionPrefs.maliGraphicsMode(activity))
                assertEquals("chosen-linux", SessionPrefs.linuxDriver(activity))
                assertEquals(SessionPrefs.GPU_DRIVERS_MIX, SessionPrefs.gpuDriverMode(activity))
            }
        }
    }

    @Test fun maliDisplayAutoKeepsTheRuntimeHalfOfAnActiveBundle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val display = File(activity.filesDir, "graphics_driver/test-panvk").apply { mkdirs() }
        File(display, "meta.json").writeText("""{"libraryName":"libvulkan_panfrost.so"}""")
        File(display, "libvulkan_panfrost.so").writeText("fixture")
        val runtime = LinuxVulkanDriverManager(activity).getDriverDir("test-panvk").apply { mkdirs() }
        File(runtime, "icd.json").writeText("{}")
        File(runtime, "libvulkan_panfrost.so").writeText("fixture")
        activity.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE).edit()
            .putString("PanVK-G720-test", """{"name":"PanVK","android":"test-panvk","linux":"test-panvk"}""").commit()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        ReflectionHelpers.setField(menus, "gpu", GpuInfo("Mali-G720", 720, GpuInfo.Family.MALI_G720, "", false))
        for (choice in MaliGraphicsMode.entries) {
            SessionPrefs.setMaliGraphicsMode(activity, choice)
            SessionPrefs.setAndroidDriver(activity, "test-panvk")
            SessionPrefs.setLinuxDriver(activity, "test-panvk")
            assertEquals("PanVK-G720-test", DriverBundle.active(activity)?.id)
            menus.selectDriver(TurnipDriver.AUTO, linux = false)
            assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
            assertEquals("test-panvk", SessionPrefs.linuxDriver(activity))
            assertEquals(choice, SessionPrefs.maliGraphicsMode(activity))
        }
    }

    @Test fun driverMenusCanBeConstructedBeforeActivityContextIsAttached() {
        val menus = DriverMenus(Activity(), Handler(Looper.getMainLooper()))
        assertEquals(SessionPrefs.TP_UBWC_AUTO, menus.tpUbwcMode)
    }

    @Test fun fixedHostRoutesStillRepairTheAutoDisplayOnlyWhileIdle() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        ReflectionHelpers.setField(menus, "gpu", GpuInfo("Mali-G720", 720, GpuInfo.Family.MALI_G720, "", false))
        for (choice in listOf(MaliGraphicsMode.BRIDGE, MaliGraphicsMode.VENUS)) {
            SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
            SessionPrefs.setMaliGraphicsMode(activity, choice)
            SessionPrefs.setAndroidDriver(activity, "imported-panvk")
            SessionPrefs.setLinuxDriver(activity, "keep-runtime")
            com.droiddeck.launcher.session.SessionState.running = true
            menus.ensureAuto(true)
            assertEquals("imported-panvk", SessionPrefs.androidDriver(activity))
            com.droiddeck.launcher.session.SessionState.running = false
            for (phase in listOf(com.droiddeck.launcher.session.SessionPhase.STARTING_COMPOSITOR,
                com.droiddeck.launcher.session.SessionPhase.STARTING_GUEST)) {
                com.droiddeck.launcher.session.SessionState.phase = phase
                menus.ensureAuto(true)
                assertEquals("imported-panvk", SessionPrefs.androidDriver(activity))
            }
            com.droiddeck.launcher.session.SessionState.phase = com.droiddeck.launcher.session.SessionPhase.IDLE
            menus.ensureAuto(true)
            assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
            assertEquals("keep-runtime", SessionPrefs.linuxDriver(activity))
            assertEquals(choice, SessionPrefs.maliGraphicsMode(activity))
        }
    }

    @Test fun fixedPanvkRollsBackDueTrialWithoutRestoringAnotherRouteOrOverwritingLaterChoice() {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val menus = DriverMenus(activity, Handler(Looper.getMainLooper()))
        ReflectionHelpers.setField(menus, "gpu", GpuInfo("Mali-G720", 720, GpuInfo.Family.MALI_G720, "", false))
        val trials = com.droiddeck.launcher.gpu.AutoDriverTrial
        for (later in listOf(false, true)) {
            SessionPrefs.setGpuDriverMode(activity, SessionPrefs.GPU_DRIVERS_AUTO)
            SessionPrefs.setMaliGraphicsMode(activity, MaliGraphicsMode.PANVK)
            SessionPrefs.setAndroidDriver(activity, TurnipDriver.AUTO)
            SessionPrefs.setLinuxDriver(activity, "trial-panvk")
            trials.begin(activity, "panvk@bad", "PanVK", TurnipDriver.AUTO, "trial-panvk", "unsafe-display", "non-panvk")
            repeat(2) { trials.sessionStarting(activity, TurnipDriver.AUTO, "trial-panvk") }
            if (later) SessionPrefs.setLinuxDriver(activity, "later-panvk")
            menus.ensureAuto(true)
            assertNull(trials.current(activity))
            assertTrue("panvk@bad" in trials.bad(activity))
            assertEquals(if (later) "later-panvk" else "", SessionPrefs.linuxDriver(activity))
            assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(activity))
            assertEquals(MaliGraphicsMode.PANVK, SessionPrefs.maliGraphicsMode(activity))
        }
        val previous = com.droiddeck.launcher.gpu.LinuxVulkanDriverManager(activity).getDriverDir("previous-panvk").apply { mkdirs() }
        java.io.File(previous, "libvulkan_panfrost.so").writeText("fixture")
        java.io.File(previous, "icd.json").writeText("{}")
        SessionPrefs.setLinuxDriver(activity, "trial-panvk")
        trials.begin(activity, "panvk@bad", "PanVK", TurnipDriver.AUTO, "trial-panvk", TurnipDriver.AUTO, "previous-panvk")
        repeat(2) { trials.sessionStarting(activity, TurnipDriver.AUTO, "trial-panvk") }
        menus.ensureAuto(true)
        assertEquals("previous-panvk", SessionPrefs.linuxDriver(activity))
        assertEquals(MaliGraphicsMode.PANVK, SessionPrefs.maliGraphicsMode(activity))
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
