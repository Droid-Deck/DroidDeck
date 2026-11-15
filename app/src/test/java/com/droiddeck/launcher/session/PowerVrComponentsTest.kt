package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PowerVrComponentsTest {
    @Test fun onlyDxvkIsManagedWithTheSystemPowerVrRuntime() {
        assertTrue(ComponentsManager.runtimeManaged("dxvk", systemPowerVr = true))
        assertFalse(ComponentsManager.runtimeManaged("dxvk", systemPowerVr = false))
        assertFalse(ComponentsManager.runtimeManaged("fex", systemPowerVr = true))
        assertFalse(ComponentsManager.runtimeManaged("vkd3d", systemPowerVr = true))
    }
}
