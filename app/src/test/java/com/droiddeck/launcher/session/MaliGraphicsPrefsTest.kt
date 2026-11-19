package com.droiddeck.launcher.session

import android.app.Application
import android.content.Context
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.GpuInfo
import com.droiddeck.launcher.gpu.DriverBundle
import com.droiddeck.launcher.gpu.LinuxVulkanDriverManager
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = Application::class,
    instrumentedPackages = ["com.droiddeck.launcher.gpu.GpuInfo"], shadows = [MaliGraphicsPrefsTest.DetectedGpu::class])
class MaliGraphicsPrefsTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val prefs get() = context.getSharedPreferences("session", Context.MODE_PRIVATE)

    @Before fun reset() { prefs.edit().clear().commit() }

    @Test fun migrationIsStoredAndResetCannotResurrectLegacySwitches() {
        prefs.edit().putBoolean("venus", true).putBoolean("vkBridge", false).commit()
        assertEquals(MaliGraphicsMode.VENUS, SessionPrefs.maliGraphicsMode(context))
        assertFalse(prefs.contains("venus"))
        assertFalse(prefs.contains("vkBridge"))
        assertEquals("venus", prefs.getString("maliGraphicsMode", null))
        prefs.edit().apply { prefs.all.keys.filter(SettingsReset::isSessionSetting).forEach { remove(it) } }.commit()
        assertEquals(MaliGraphicsMode.AUTO, SessionPrefs.maliGraphicsMode(context))
    }

    @Test fun selectedRuntimeDoesNotOverrideFixedComponentsOrRewriteAuto() {
        val dir = File(context.filesDir, "${LinuxVulkanDriverManager.DIR_NAME}/selected").apply { mkdirs() }
        File(dir, "icd.json").writeText("{}")
        File(dir, "libvulkan_panfrost.so").writeText("fixture")
        try {
            SessionPrefs.setLinuxDriver(context, "selected")
            assertTrue(LinuxVulkanDriverManager(context).isInstalled("selected"))
            assertEquals(MaliGraphicsMode.AUTO, MaliGraphics.resolve(context))
            assertEquals(MaliGraphicsMode.AUTO, SessionPrefs.maliGraphicsMode(context))
            SessionPrefs.setMaliGraphicsMode(context, MaliGraphicsMode.BRIDGE)
            assertTrue(DeviceSupport.vkBridge(context))
            assertFalse(VenusComponent.wanted(context))
            SessionPrefs.setMaliGraphicsMode(context, MaliGraphicsMode.VENUS)
            assertFalse(DeviceSupport.vkBridge(context))
            assertTrue(VenusComponent.wanted(context))
            assertEquals("selected", SessionPrefs.linuxDriver(context))
        } finally { dir.deleteRecursively() }
    }

    @Test fun incompatibleSelectedRuntimeDoesNotSuppressComponents() = withRuntime("libvulkan_freedreno.so") {
        assertTrue(LinuxVulkanDriverManager(context).isInstalled("selected"))
        assertEquals(MaliGraphicsMode.BRIDGE, MaliGraphics.resolve(context))
        assertEquals(MaliGraphicsMode.AUTO, SessionPrefs.maliGraphicsMode(context))
        assertFalse(DeviceSupport.openDriverActive(context))
        assertFalse(launchEnvironment().containsKey("DD_OPEN_DRIVER"))
    }

    @Test fun compatibleLibraryWithoutInstalledIcdDoesNotSuppressComponents() = withRuntime("libvulkan_panfrost.so") { dir ->
        File(dir, "icd.json").delete()
        assertEquals(MaliGraphicsMode.BRIDGE, MaliGraphics.resolve(context))
        assertFalse(DeviceSupport.openDriverActive(context))
        assertEquals(MaliGraphicsMode.AUTO, SessionPrefs.maliGraphicsMode(context))
    }

    @Test fun legacyBridgeFallbackWithCompatibleRuntimeMigratesOnlyOnce() = withRuntime("libvulkan_panfrost.so") {
        for (reason in listOf("memory", "draw")) {
            prefs.edit().remove("maliGraphicsMode").putBoolean("vkBridge", true)
                .putBoolean("venus", false).putBoolean("bridgeFellBack", true).putString("bridgeFallbackReason", reason).commit()
            assertEquals(MaliGraphicsMode.AUTO, SessionPrefs.maliGraphicsMode(context))
            assertEquals(MaliGraphicsMode.AUTO, MaliGraphics.resolve(context))
            for (choice in listOf(MaliGraphicsMode.BRIDGE, MaliGraphicsMode.VENUS)) {
                SessionPrefs.setMaliGraphicsMode(context, choice)
                assertEquals(choice, SessionPrefs.maliGraphicsMode(context))
                assertEquals(choice, MaliGraphics.resolve(context))
            }
        }
    }

    @Test fun fallbackEvidenceDoesNotOverrideVenusOrInventAnAvailableRuntime() = withRuntime("libvulkan_panfrost.so") { dir ->
        fun migrate(fellBack: Boolean, reason: String, venus: Boolean = false): MaliGraphicsMode {
            prefs.edit().remove("maliGraphicsMode").putBoolean("vkBridge", true).putBoolean("venus", venus)
                .putBoolean("bridgeFellBack", fellBack).putString("bridgeFallbackReason", reason).commit()
            return SessionPrefs.maliGraphicsMode(context)
        }
        assertEquals(MaliGraphicsMode.VENUS, migrate(true, "memory", venus = true))
        assertEquals(MaliGraphicsMode.BRIDGE, migrate(false, "memory"))
        assertEquals(MaliGraphicsMode.BRIDGE, migrate(true, ""))
        File(dir, "libvulkan_panfrost.so").delete()
        File(dir, "libvulkan_freedreno.so").writeText("fixture")
        assertEquals(MaliGraphicsMode.BRIDGE, migrate(true, "draw"))
        File(dir, "icd.json").delete()
        assertEquals(MaliGraphicsMode.BRIDGE, migrate(true, "memory"))
    }

    @Test fun standaloneAndPairedRuntimeShareOpenDriverEnvironmentButFixedRoutesExcludeIt() = withRuntime("libvulkan_panfrost.so") { dir ->
        assertNull(DriverBundle.containing(context, "selected", true))
        assertTrue(DeviceSupport.openDriverActive(context))
        val standalone = launchEnvironment()
        assertEquals("1", standalone["DD_OPEN_DRIVER"])
        assertEquals("1", standalone["DD_STEAM_SOFT_COMPOSITING"])
        assertEquals(File(dir, "icd.json").path, standalone["DD_VK_DRIVER"])

        val display = File(context.filesDir, "graphics_driver/paired-display").apply { mkdirs() }
        val registry = context.getSharedPreferences("driver_bundles", Context.MODE_PRIVATE)
        try {
            File(display, "meta.json").writeText("""{"libraryName":"libvulkan_panfrost.so"}""")
            File(display, "libvulkan_panfrost.so").writeText("fixture")
            registry.edit().putString("PanVK-Mali-G715", """{"name":"compatible bundle","android":"paired-display","linux":"selected"}""").commit()
            assertNotNull(DriverBundle.containing(context, "selected", true))
            assertTrue(DeviceSupport.openDriverActive(context))
            val paired = launchEnvironment()
            for (key in listOf("DD_OPEN_DRIVER", "DD_STEAM_SOFT_COMPOSITING", "DD_VK_DRIVER", "DD_DRM_STANDIN", "GAMESCOPE_DISABLE_EXPLICIT_SYNC")) {
                assertEquals(key, standalone[key], paired[key])
            }
            for (choice in listOf(MaliGraphicsMode.BRIDGE, MaliGraphicsMode.VENUS)) {
                SessionPrefs.setMaliGraphicsMode(context, choice)
                assertFalse(DeviceSupport.openDriverActive(context))
                assertFalse(launchEnvironment().containsKey("DD_OPEN_DRIVER"))
            }
        } finally {
            registry.edit().clear().commit()
            display.deleteRecursively()
        }
    }

    private fun withRuntime(library: String, block: (File) -> Unit) {
        val dir = File(context.filesDir, "${LinuxVulkanDriverManager.DIR_NAME}/selected").apply { mkdirs() }
        File(dir, "icd.json").writeText("{}")
        File(dir, library).writeText("fixture")
        SessionPrefs.setLinuxDriver(context, "selected")
        try { block(dir) } finally { dir.deleteRecursively() }
    }

    private fun launchEnvironment(): Map<String, String> {
        val service = Robolectric.buildService(SessionService::class.java).get()
        val guest = mutableListOf<String>()
        val choice = SessionPrefs.maliGraphicsMode(context)
        SessionService::class.java.getDeclaredMethod("addClientEnvironment", MutableList::class.java,
            Boolean::class.javaPrimitiveType, List::class.java, MaliGraphicsMode::class.java, MaliGraphicsMode::class.java)
            .apply { isAccessible = true }.invoke(service, guest, false, emptyList<String>(), choice, MaliGraphics.resolve(context, choice))
        return guest.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
    }

    @Test fun launchEnvironmentKeepsFixedVenusDespitePrivateAndAgentOverrides() {
        val service = Robolectric.buildService(SessionService::class.java).get()
        val override = SessionService.envSwitchFile(service)!!
        override.parentFile!!.mkdirs()
        override.writeText("DD_MALI_GRAPHICS_MODE=auto\nDD_INSIDE=1\nVK_LOADER_DEBUG=all\n")
        try {
            SessionPrefs.setLinuxDriver(context, "other-runtime")
            val guest = mutableListOf<String>()
            SessionService::class.java.getDeclaredMethod("addClientEnvironment", MutableList::class.java,
                Boolean::class.javaPrimitiveType, List::class.java, MaliGraphicsMode::class.java, MaliGraphicsMode::class.java)
                .apply { isAccessible = true }.invoke(service, guest, false,
                    listOf("DD_VENUS=0", "GALLIUM_DRIVER=llvmpipe"), MaliGraphicsMode.VENUS, MaliGraphicsMode.VENUS)
            val env = guest.filter { '=' in it }.associate { it.substringBefore('=') to it.substringAfter('=') }
            assertEquals("venus", env["DD_MALI_GRAPHICS_MODE"])
            assertEquals("1", env["DD_VENUS"])
            assertEquals("zink", env["GALLIUM_DRIVER"])
            assertEquals("all", env["VK_LOADER_DEBUG"])
            assertFalse(env.containsKey("DD_INSIDE"))
            assertFalse(env.containsKey("DD_VK_DRIVER"))
            assertEquals(VenusComponent.socket(context).path, env["VTEST_SOCKET_NAME"])
        } finally { override.delete() }
    }

    @Test fun automaticFallbackStatusDoesNotTakeOwnershipOfTheChoice() {
        val activity = Robolectric.buildActivity(android.app.Activity::class.java).setup().get()
        val menus = com.droiddeck.launcher.DriverMenus(activity, android.os.Handler(android.os.Looper.getMainLooper()))
        val fallback = menus.javaClass.getDeclaredMethod("useMaliFallback", String::class.java).apply { isAccessible = true }
        for (choice in MaliGraphicsMode.entries) {
            SessionPrefs.setMaliGraphicsMode(context, choice)
            fallback.invoke(menus, "missing-pair")
            assertEquals(choice, SessionPrefs.maliGraphicsMode(context))
            assertFalse(prefs.contains("maliFallbackByAuto"))
        }
        assertTrue(com.droiddeck.launcher.ui.SettingsIndex.search(context, "MaliBridge")
            .any { it.title == com.droiddeck.launcher.R.string.mali_path_choice })
    }

    @Implements(value = GpuInfo.Companion::class, isInAndroidSdk = false)
    class DetectedGpu {
        @Implementation fun detect() = GpuInfo("Mali-G715", 0, GpuInfo.Family.MALI_LATEST, "", false)
    }
}
