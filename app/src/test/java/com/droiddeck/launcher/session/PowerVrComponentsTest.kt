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
}
