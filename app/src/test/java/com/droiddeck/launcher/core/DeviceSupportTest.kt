package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportTest {
    @Test fun runtimeDownloadAcceptsOnlyRecognizedGraphicsBackends() {
        assertTrue(DeviceSupport.graphicsAvailable(adreno = true, systemVulkanReady = false))
        assertTrue(DeviceSupport.graphicsAvailable(adreno = false, systemVulkanReady = true))
        // A PowerVR HAL without usable DRM access also reaches this unavailable case.
        assertFalse(DeviceSupport.graphicsAvailable(adreno = false, systemVulkanReady = false))
    }

    @Test fun eachSessionUsesExactlyOneGraphicsRoute() {
        val bridge = DeviceSupport.graphicsRoute(bridge = true, systemVulkan = true)
        assertEquals(DeviceSupport.GraphicsRoute.VK_BRIDGE, bridge)
        assertTrue(bridge.bridge)
        assertFalse(bridge.systemVulkan)
        assertFalse(bridge.linuxVulkan)

        val system = DeviceSupport.graphicsRoute(bridge = false, systemVulkan = true)
        assertEquals(DeviceSupport.GraphicsRoute.SYSTEM_VULKAN, system)
        assertFalse(system.bridge)
        assertTrue(system.systemVulkan)
        assertFalse(system.linuxVulkan)

        val linux = DeviceSupport.graphicsRoute(bridge = false, systemVulkan = false)
        assertEquals(DeviceSupport.GraphicsRoute.LINUX_VULKAN, linux)
        assertFalse(linux.bridge)
        assertFalse(linux.systemVulkan)
        assertTrue(linux.linuxVulkan)
    }

    @Test fun onlyTheSystemRouteRequiresItsComponentBeforeSessionStart() {
        assertTrue(DeviceSupport.needsSystemVulkanComponent(
            sessionRunning = false,
            route = DeviceSupport.GraphicsRoute.SYSTEM_VULKAN,
            available = true,
            ready = false,
        ))
        for (route in listOf(DeviceSupport.GraphicsRoute.VK_BRIDGE, DeviceSupport.GraphicsRoute.LINUX_VULKAN)) {
            assertFalse(DeviceSupport.needsSystemVulkanComponent(false, route, available = true, ready = false))
        }
        assertFalse(DeviceSupport.needsSystemVulkanComponent(
            sessionRunning = true,
            route = DeviceSupport.GraphicsRoute.SYSTEM_VULKAN,
            available = true,
            ready = false,
        ))
        assertFalse(DeviceSupport.needsSystemVulkanComponent(
            sessionRunning = false,
            route = DeviceSupport.GraphicsRoute.SYSTEM_VULKAN,
            available = true,
            ready = true,
        ))
    }
}
