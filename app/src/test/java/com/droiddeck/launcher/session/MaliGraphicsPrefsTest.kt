package com.droiddeck.launcher.session

import android.app.Application
import android.content.Context
import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.GpuInfo
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
        File(dir, "libvulkan_freedreno.so").writeText("fixture")
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
