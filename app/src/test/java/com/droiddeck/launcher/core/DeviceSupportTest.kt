package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportTest {
    @Test fun eachSessionUsesOneGraphicsRoute() {
        assertEquals(DeviceSupport.GraphicsRoute.VENUS,
            DeviceSupport.graphicsRoute(venus = true, bridge = true, systemVulkan = true))
        assertEquals(DeviceSupport.GraphicsRoute.SYSTEM_VULKAN,
            DeviceSupport.graphicsRoute(venus = false, bridge = true, systemVulkan = true))
        assertEquals(DeviceSupport.GraphicsRoute.VK_BRIDGE,
            DeviceSupport.graphicsRoute(venus = false, bridge = true, systemVulkan = false))
        assertEquals(DeviceSupport.GraphicsRoute.LINUX_VULKAN,
            DeviceSupport.graphicsRoute(venus = false, bridge = false, systemVulkan = false))
    }

    @Test fun systemComponentIsNeededOnlyBeforeASystemSession() {
        assertTrue(DeviceSupport.needsSystemVulkanComponent(false, DeviceSupport.GraphicsRoute.SYSTEM_VULKAN, true, false))
        assertFalse(DeviceSupport.needsSystemVulkanComponent(true, DeviceSupport.GraphicsRoute.SYSTEM_VULKAN, true, false))
        assertFalse(DeviceSupport.needsSystemVulkanComponent(false, DeviceSupport.GraphicsRoute.VK_BRIDGE, true, false))
        assertFalse(DeviceSupport.needsSystemVulkanComponent(false, DeviceSupport.GraphicsRoute.SYSTEM_VULKAN, false, false))
        assertFalse(DeviceSupport.needsSystemVulkanComponent(false, DeviceSupport.GraphicsRoute.SYSTEM_VULKAN, true, true))
    }

    @Test fun runtimeDownloadAcceptsOnlyRecognizedGraphicsBackends() {
        assertTrue(DeviceSupport.graphicsAvailable(adreno = true, powerVrReady = false))
        assertTrue(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = true))
        // A PowerVR HAL without usable DRM access also reaches this unavailable case.
        assertFalse(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = false))
    }
}
