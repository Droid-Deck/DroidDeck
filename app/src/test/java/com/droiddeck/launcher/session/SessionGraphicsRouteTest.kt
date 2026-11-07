package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.core.DeviceSupport
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class SessionGraphicsRouteTest {
    private val context: Context get() = RuntimeEnvironment.getApplication()

    @Before fun reset() {
        context.getSharedPreferences("session", Context.MODE_PRIVATE).edit().clear().commit()
        SessionState.running = false
        SessionState.graphicsRoute = null
    }

    @After fun finish() {
        SessionState.running = false
        SessionState.graphicsRoute = null
    }

    @Test fun aRunningSessionKeepsItsFrozenRouteWhenPreferencesChange() {
        SessionState.running = true
        SessionState.graphicsRoute = DeviceSupport.GraphicsRoute.VK_BRIDGE
        SessionPrefs.setVkBridge(context, false)
        SessionPrefs.setLinuxDriver(context, "manual-linux")

        assertEquals(DeviceSupport.GraphicsRoute.VK_BRIDGE, SessionState.effectiveGraphicsRoute(context))
    }

    @Test fun anIdleSessionUsesTheCurrentPreferences() {
        SessionPrefs.setVkBridge(context, false)
        SessionPrefs.setLinuxDriver(context, "manual-linux")

        assertEquals(DeviceSupport.GraphicsRoute.LINUX_VULKAN, SessionState.effectiveGraphicsRoute(context))
    }

    @Test fun componentManagementUsesTheFrozenSessionRoute() {
        SessionState.running = true
        for ((route, expected) in listOf(
            DeviceSupport.GraphicsRoute.VK_BRIDGE to false,
            DeviceSupport.GraphicsRoute.SYSTEM_VULKAN to true,
            DeviceSupport.GraphicsRoute.LINUX_VULKAN to false,
        )) {
            SessionState.graphicsRoute = route
            assertEquals(expected, ComponentsManager.runtimeManaged(context, "dxvk", systemManagesDxvk = true))
        }
    }
}
