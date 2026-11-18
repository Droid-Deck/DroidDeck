package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], manifest = Config.NONE,
    instrumentedPackages = ["com.droiddeck.launcher.gpu.GpuInfo"],
    shadows = [MaliGraphicsPrefsTest.DetectedGpu::class],
)
class MaliGraphicsPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun reset() {
        prefs.edit().clear().commit()
    }

    @Test fun fourChoicesRoundTripAsOnePreferenceAndRemoveLegacySources() {
        assertEquals(setOf("auto", "panvk", "bridge", "venus"), MaliGraphicsMode.entries.map { it.id }.toSet())
        for (choice in MaliGraphicsMode.entries) {
            prefs.edit().putBoolean("vkBridge", true).putBoolean("venus", true)
                .putString("maliFallbackByAuto", "bridge").commit()
            SessionPrefs.setMaliGraphicsMode(context, choice)
            assertEquals(mapOf("maliGraphicsMode" to choice.id), prefs.all)
            assertEquals(choice, SessionPrefs.maliGraphicsMode(context))
        }
    }

    @Test fun legacyAutoValuesAreMigratedOnceAndCannotBecomeExplicitLater() {
        prefs.edit().putBoolean("vkBridge", false).putBoolean("venus", true)
            .putString("maliFallbackByAuto", "venus").commit()
        assertEquals(MaliGraphicsMode.AUTO, SessionPrefs.maliGraphicsMode(context))
        assertEquals(mapOf("maliGraphicsMode" to "auto"), prefs.all)
        prefs.edit().putBoolean("venus", true).remove("maliFallbackByAuto").commit()
        assertEquals(MaliGraphicsMode.AUTO, SessionPrefs.maliGraphicsMode(context))
    }

    @Test fun explicitAndAmbiguousOldDataKeepTheirEffectivePath() {
        prefs.edit().putBoolean("vkBridge", true).putBoolean("venus", false).commit()
        assertEquals(MaliGraphicsMode.BRIDGE, SessionPrefs.maliGraphicsMode(context))
        assertEquals(mapOf("maliGraphicsMode" to "bridge"), prefs.all)
    }

    @Test fun absentBridgeKeyRetainsItsOldMaliDefault() {
        prefs.edit().putBoolean("venus", false).commit()
        assertEquals(MaliGraphicsMode.BRIDGE, SessionPrefs.maliGraphicsMode(context))
    }

    @Test fun historicalBridgeFailureUsesAutoOnlyWithAnAvailableSelectedPanvk() {
        val dir = LinuxVulkanDriverManager(context).getDriverDir("fallback-panvk").apply { mkdirs() }
        File(dir, "libvulkan_panfrost.so").writeText("fixture")
        File(dir, "icd.json").writeText("{}")
        for (reason in listOf("memory", "draw", "other")) {
            prefs.edit().clear().putBoolean("vkBridge", true).putBoolean("bridgeFellBack", true)
                .putString("bridgeFallbackReason", reason).putString("linuxDriver", "fallback-panvk").commit()
            assertEquals(if (reason == "other") MaliGraphicsMode.BRIDGE else MaliGraphicsMode.AUTO,
                SessionPrefs.maliGraphicsMode(context))
        }
        prefs.edit().clear().putBoolean("vkBridge", true).putBoolean("bridgeFellBack", true)
            .putString("bridgeFallbackReason", "memory").commit()
        assertEquals(MaliGraphicsMode.BRIDGE, SessionPrefs.maliGraphicsMode(context))
        prefs.edit().clear().putBoolean("vkBridge", true).putBoolean("venus", true)
            .putBoolean("bridgeFellBack", true).putString("bridgeFallbackReason", "draw")
            .putString("linuxDriver", "fallback-panvk").commit()
        assertEquals(MaliGraphicsMode.VENUS, SessionPrefs.maliGraphicsMode(context))
    }

    @Test fun reportKeepsTheCapturedLaunchAfterPreferencesChange() {
        SessionPrefs.setMaliGraphicsMode(context, MaliGraphicsMode.PANVK)
        SessionPrefs.setLinuxDriver(context, "missing-panvk")
        val launch = MaliGraphics.capture(context)
        SessionPrefs.setMaliGraphicsMode(context, MaliGraphicsMode.VENUS)
        SessionPrefs.setLinuxDriver(context, "later-runtime")
        val folder = File(context.cacheDir, "captured-report").apply { mkdirs() }
        GraphicsReport.begin(context, folder, emptyList(), launch)
        val report = File(folder, GraphicsReport.FILE).readText()
        assertTrue(report.contains("Mali graphics choice: panvk"))
        assertTrue(report.contains("runtime driver: missing-panvk"))
        assertTrue(report.contains("Mali graphics unavailable: ${launch.problem}"))
        assertFalse(report.contains("later-runtime"))
        GraphicsReport.begin(context, folder, listOf("DD_MALI_GRAPHICS_PATH=bridge"), launch)
        assertTrue(File(folder, GraphicsReport.FILE).readText().contains("Mali graphics resolved at launch: bridge"))
    }

    @Test fun autoResolutionDoesNotWriteTheResolvedPathOrOtherSettings() {
        SessionPrefs.setMaliGraphicsMode(context, MaliGraphicsMode.AUTO)
        val before = prefs.all
        assertNull(MaliGraphics.resolve(context))
        val drivers = LinuxVulkanDriverManager(context)
        val dir = drivers.getDriverDir("test-panvk").apply { mkdirs() }
        File(dir, "libvulkan_panfrost.so").writeText("fixture")
        File(dir, "icd.json").writeText("{}")
        SessionPrefs.setLinuxDriver(context, "test-panvk")
        val withDriver = prefs.all
        assertEquals(MaliGraphicsMode.PANVK, MaliGraphics.resolve(context))
        assertEquals(withDriver, prefs.all)
        SessionPrefs.setLinuxDriver(context, "")
        assertNull(MaliGraphics.resolve(context))
        assertEquals(before + ("linuxDriver" to ""), prefs.all)
    }

    @Test fun missingFixedComponentsReportFailureAndKeepTheChoice() {
        for (choice in listOf(MaliGraphicsMode.PANVK, MaliGraphicsMode.BRIDGE, MaliGraphicsMode.VENUS)) {
            SessionPrefs.setMaliGraphicsMode(context, choice)
            assertEquals(choice, MaliGraphics.resolve(context))
            assertNotNull(MaliGraphics.problem(context))
            assertEquals(choice, SessionPrefs.maliGraphicsMode(context))
        }
    }

    @Test fun maliSearchTargetsOverview() {
        assertTrue(DeviceSupport.mali())
        for (query in listOf("PanVK", "MaliBridge", "Venus")) {
            val entry = com.droiddeck.launcher.ui.SettingsIndex.search(context, query).single()
            assertEquals(com.droiddeck.launcher.ui.SettingsSection.OVERVIEW, entry.section)
        }
    }

    @Implements(value = GpuInfo.Companion::class, isInAndroidSdk = false)
    class DetectedGpu {
        @Implementation
        fun detect(): GpuInfo = GpuInfo("test GPU", 720, GpuInfo.Family.MALI_G720, "", false)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(
    sdk = [28], manifest = Config.NONE,
    instrumentedPackages = ["com.droiddeck.launcher.core.DeviceSupport"],
    shadows = [MaliGraphicsSearchAdrenoTest.AdrenoDevice::class],
)
class MaliGraphicsSearchAdrenoTest {
    @Test fun maliSearchIsHiddenOnAdreno() {
        val context = RuntimeEnvironment.getApplication()
        assertFalse(DeviceSupport.mali())
        for (query in listOf("PanVK", "MaliBridge", "Venus")) {
            assertTrue(com.droiddeck.launcher.ui.SettingsIndex.search(context, query).isEmpty())
        }
    }

    @Implements(value = DeviceSupport::class, isInAndroidSdk = false)
    class AdrenoDevice {
        @Implementation
        fun mali(): Boolean = false
    }
}
