package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerVrComponentsTest {
    @Test fun onlyDxvkIsManagedWithTheSystemPowerVrRuntime() {
        assertTrue(ComponentsManager.runtimeManaged("dxvk", runtimeManagedDxvk = true))
        assertFalse(ComponentsManager.runtimeManaged("dxvk", runtimeManagedDxvk = false))
        assertFalse(ComponentsManager.runtimeManaged("fex", runtimeManagedDxvk = true))
        assertFalse(ComponentsManager.runtimeManaged("vkd3d", runtimeManagedDxvk = true))
    }

    @Test fun systemDxvkManagementAppliesOnlyToTheEffectiveSystemRoute() {
        assertTrue(ComponentsManager.runtimeManaged(
            "dxvk",
            com.droiddeck.launcher.core.DeviceSupport.GraphicsRoute.SYSTEM_VULKAN,
            systemManagesDxvk = true,
        ))
        assertFalse(ComponentsManager.runtimeManaged(
            "dxvk",
            com.droiddeck.launcher.core.DeviceSupport.GraphicsRoute.VK_BRIDGE,
            systemManagesDxvk = true,
        ))
        assertFalse(ComponentsManager.runtimeManaged(
            "dxvk",
            com.droiddeck.launcher.core.DeviceSupport.GraphicsRoute.LINUX_VULKAN,
            systemManagesDxvk = true,
        ))
    }
}
