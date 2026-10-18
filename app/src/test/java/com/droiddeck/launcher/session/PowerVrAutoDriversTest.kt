package com.droiddeck.launcher.session

import android.content.Context
import org.robolectric.RuntimeEnvironment
import com.droiddeck.launcher.gpu.TurnipDriver
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class PowerVrAutoDriversTest {
    private val context: Context = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        SessionPrefs.setLinuxDriver(context, "imported-linux")
        SessionPrefs.setAndroidDriver(context, "imported-display")
    }

    @Test fun autoRepairsStaleAdrenoSelectionsOnSystemVulkan() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensureSystemVulkanAutoDrivers(context, systemVulkan = true)
        assertEquals("", SessionPrefs.linuxDriver(context))
        assertEquals(TurnipDriver.AUTO, SessionPrefs.androidDriver(context))
    }

    @Test fun explicitManualChoicesRemainOnSystemVulkan() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_MANUAL)
        SessionPrefs.ensureSystemVulkanAutoDrivers(context, systemVulkan = true)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }

    @Test fun adrenoAutoChoicesRemain() {
        SessionPrefs.setGpuDriverMode(context, SessionPrefs.GPU_DRIVERS_AUTO)
        SessionPrefs.ensureSystemVulkanAutoDrivers(context, systemVulkan = false)
        assertEquals("imported-linux", SessionPrefs.linuxDriver(context))
        assertEquals("imported-display", SessionPrefs.androidDriver(context))
    }
}
