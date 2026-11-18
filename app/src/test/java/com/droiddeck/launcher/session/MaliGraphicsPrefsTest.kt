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
        assertTrue(DeviceSupport.mali())
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

    @Implements(value = GpuInfo.Companion::class, isInAndroidSdk = false)
    class DetectedGpu {
        @Implementation
        fun detect(): GpuInfo = GpuInfo("Mali-G720", 720, GpuInfo.Family.MALI_G720, "", false)
    }
}
