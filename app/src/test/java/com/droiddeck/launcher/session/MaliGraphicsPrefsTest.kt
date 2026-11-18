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

    @Test @Config(sdk = [30])
    fun debugAndAgentOverridesCannotReplaceCapturedMaliRouting() {
        val service = org.robolectric.Robolectric.buildService(SessionService::class.java).get()
        val file = requireNotNull(SessionService.envSwitchFile(service))
        val read = SessionService::class.java.getDeclaredMethod("extraEnv", MaliGraphics.Launch::class.java, List::class.java)
            .apply { isAccessible = true }
        val extra = listOf("DD_MALI_GRAPHICS_MODE=auto", "DD_MALI_GRAPHICS_PATH=bridge", "DD_VENUS=0",
            "DD_VKBRIDGE=1", "DD_VK_DRIVER=/other.json", "VK_DRIVER_FILES=/other.json", "VK_ICD_FILENAMES=/other.json",
            "GALLIUM_DRIVER=llvmpipe", "LIBGL_ALWAYS_SOFTWARE=1", "VN_DEBUG=", "VTEST_SOCKET_NAME=/other.sock",
            "DROIDDECK_GAME_VK_DRIVER_FILES=/other.json",
            "VK_LOADER_DEBUG=error,warn", "MESA_DEBUG=1", "ZINK_DEBUG=compact", "DD_VENUS_WAIT_MS=400")
        file.writeText(extra.dropLast(2).joinToString("\n"))
        val agent = listOf("DD_VENUS=0") + extra.takeLast(2)
        val diagnostics = extra.takeLast(4)
        for (choice in MaliGraphicsMode.entries) {
            val launch = MaliGraphics.Launch(choice, MaliGraphicsMode.VENUS, "", null, null, "auto", "")
            val guest = listOf("DD_MALI_GRAPHICS_MODE=${choice.id}", "DD_MALI_GRAPHICS_PATH=venus", "DD_VENUS=1") +
                (read.invoke(service, launch, agent) as List<*>)
            assertEquals("DD_MALI_GRAPHICS_MODE=${choice.id}", guest.last { it.toString().startsWith("DD_MALI_GRAPHICS_MODE=") })
            assertEquals("DD_VENUS=1", guest.last { it.toString().startsWith("DD_VENUS=") })
            assertEquals(diagnostics, guest.takeLast(4))
            assertFalse(guest.any { it.toString().contains("/other") || it == "GALLIUM_DRIVER=llvmpipe" })
        }
        val nonMali = MaliGraphics.Launch(MaliGraphicsMode.AUTO, null, "", null, null, "auto", "")
        assertEquals(extra, MaliGraphics.diagnosticOverrides(nonMali, extra))
        file.delete()
    }

    @Test fun panvkReportFindsRuntimeBundleWithSystemDisplayAndDoesNotInventStandalonePair() {
        val id = "report-panvk"
        val display = File(context.filesDir, "graphics_driver/$id").apply { mkdirs() }
        File(display, "meta.json").writeText("""{"libraryName":"libvulkan_panfrost.so"}""")
        File(display, "libvulkan_panfrost.so").writeText("fixture")
        val runtime = LinuxVulkanDriverManager(context).getDriverDir(id).apply { mkdirs() }
        File(runtime, "icd.json").writeText("{}")
        File(runtime, "libvulkan_panfrost.so").writeText("fixture")
        val bundles = context.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE)
        bundles.edit().putString("PanVK-G720", """{"name":"PanVK","version":"r7","android":"$id","linux":"$id"}""").commit()
        val launch = MaliGraphics.Launch(MaliGraphicsMode.AUTO, MaliGraphicsMode.PANVK, id,
            File(runtime, "icd.json").path, null, "auto", com.droiddeck.launcher.gpu.TurnipDriver.AUTO)
        val folder = File(context.cacheDir, "bundle-report").apply { mkdirs() }
        GraphicsReport.begin(context, folder, emptyList(), launch)
        val paired = File(folder, GraphicsReport.FILE).readText()
        assertTrue(paired.contains("runtime bundle: PanVK r7 (bundle PanVK-G720)"))
        assertTrue(paired.contains("path: PanVK\n"))
        assertTrue(paired.contains("display driver choice (compositor): auto"))
        bundles.edit().clear().commit()
        GraphicsReport.begin(context, folder, emptyList(), launch)
        val standalone = File(folder, GraphicsReport.FILE).readText()
        assertTrue(standalone.contains("path: PanVK\n"))
        assertFalse(standalone.contains("runtime bundle:"))
        assertFalse(standalone.contains("open driver pair"))
    }

    @Test fun refreshPublishesObservableSessionEndEvenWhenConfirmationIsUnchanged() {
        val activity = org.robolectric.Robolectric.buildActivity(com.droiddeck.launcher.FrontEndActivity::class.java).get()
        val refresh = activity.javaClass.getDeclaredMethod("refreshMaliGraphics").apply { isAccessible = true }
        val folder = SessionPaths.beginOrCurrent(activity, "visible")
        File(folder, MaliGraphics.PATH_FILE).writeText("venus")
        SessionState.running = true
        SessionState.phase = SessionPhase.READY
        refresh.invoke(activity)
        val busy = org.robolectric.util.ReflectionHelpers.getField<androidx.compose.runtime.MutableState<Boolean>>(activity, "maliSessionBusy\$delegate")
        assertTrue(busy.value)
        val before = SessionPaths.graphicsConfirmation(activity)
        SessionState.running = false
        SessionState.phase = SessionPhase.STOPPING
        refresh.invoke(activity)
        assertTrue(busy.value)
        SessionPaths.take()
        SessionState.phase = SessionPhase.IDLE
        val writes = mutableSetOf<Any>()
        androidx.compose.runtime.snapshots.Snapshot.observe(null, { writes.add(it) }) { refresh.invoke(activity) }
        assertEquals(before, SessionPaths.graphicsConfirmation(activity))
        assertFalse(busy.value)
        assertTrue("the state consumed by Setup must invalidate", busy in writes)
        SessionPaths.release(activity, folder)
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
