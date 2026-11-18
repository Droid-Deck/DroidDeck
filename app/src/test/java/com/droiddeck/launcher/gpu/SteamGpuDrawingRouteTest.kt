package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.session.SessionPrefs
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34], application = android.app.Application::class)
class SteamGpuDrawingRouteTest {
    private val context get() = RuntimeEnvironment.getApplication()

    @Before fun clear() {
        context.getSharedPreferences("session", 0).edit().clear().commit()
    }

    @Test fun selectingTheSystemDriverFlipsTheUntouchedRowToSoftware() {
        assertFalse(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(true, "")))
    }

    @Test fun selectingAnOpenDriverFlipsTheUntouchedRowToGpu() {
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(true, "custom-driver")))
    }

    @Test fun otherGpusKeepTheGpuRowWhateverDriverIsSelected() {
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(false, "")))
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(false, "custom-driver")))
    }

    @Test fun savedChoiceSurvivesDriverRouteChanges() {
        SessionPrefs.setSteamGpuCompositing(context, false)
        assertFalse(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(true, "")))
        assertFalse(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(true, "custom-driver")))
        SessionPrefs.setSteamGpuCompositing(context, true)
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(true, "")))
        assertTrue(SessionPrefs.steamGpuDrawingShown(context, SystemVulkanDriver.usesDefault(true, "custom-driver")))
    }
}
