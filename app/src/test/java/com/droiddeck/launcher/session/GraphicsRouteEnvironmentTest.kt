package com.droiddeck.launcher.session

import com.droiddeck.launcher.core.DeviceSupport
import com.droiddeck.launcher.gpu.LinuxVulkanDriver
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GraphicsRouteEnvironmentTest {
    @Test fun venusRouteDoesNotResolveAnotherVulkanProvider() {
        val environment = mutableListOf<String>()
        configureGraphicsRouteEnvironment(
            DeviceSupport.GraphicsRoute.VENUS,
            environment,
            addSystemVulkan = { error("System Vulkan must not be prepared for a Venus session") },
            linuxIcd = { error("A Linux ICD must not replace Venus") },
        )

        assertTrue(environment.isEmpty())
    }

    @Test fun bridgeRouteHasOnlyBridgeDriverEnvironment() {
        val environment = mutableListOf<String>()
        configureGraphicsRouteEnvironment(
            DeviceSupport.GraphicsRoute.VK_BRIDGE,
            environment,
            addSystemVulkan = { error("System Vulkan must not be prepared for a bridge session") },
            linuxIcd = { error("A Linux ICD must not replace the bridge ICD") },
        )
        addBridgeGuestEnvironment(
            DeviceSupport.GraphicsRoute.VK_BRIDGE,
            environment,
            "/socket",
            "/client.log",
            "safe",
            "/dev/dri/renderD128",
            "226:128",
        )

        assertTrue("BL_VKBRIDGE=1" in environment)
        assertTrue("VKBRIDGE_SOCKET=/socket" in environment)
        assertFalse(environment.any { it.startsWith("DROIDDECK_SYSTEM_VULKAN_") })
        assertFalse(environment.any { it.startsWith(LinuxVulkanDriver.ENV + "=") })
    }

    @Test fun systemRouteHasOnlySystemDriverEnvironment() {
        val environment = mutableListOf<String>()
        configureGraphicsRouteEnvironment(
            DeviceSupport.GraphicsRoute.SYSTEM_VULKAN,
            environment,
            addSystemVulkan = {
                it += "DROIDDECK_SYSTEM_VULKAN_GPU=powervr"
                "/system/icd.json"
            },
            linuxIcd = { error("The runtime ICD must not replace System Vulkan") },
        )
        addBridgeGuestEnvironment(
            DeviceSupport.GraphicsRoute.SYSTEM_VULKAN,
            environment,
            "/socket",
            "/client.log",
            "safe",
            null,
            null,
        )

        assertEquals(1, environment.count { it == "DROIDDECK_SYSTEM_VULKAN_GPU=powervr" })
        assertEquals(1, environment.count { it == LinuxVulkanDriver.ENV + "=/system/icd.json" })
        assertFalse(environment.any { it.startsWith("BL_VKBRIDGE=") })
    }

    @Test fun manualLinuxRouteHasOnlyItsSelectedIcd() {
        val environment = mutableListOf<String>()
        configureGraphicsRouteEnvironment(
            DeviceSupport.GraphicsRoute.LINUX_VULKAN,
            environment,
            addSystemVulkan = { error("System Vulkan must not be prepared for a manual Linux route") },
            linuxIcd = { "/linux/icd.json" },
        )

        assertEquals(listOf(LinuxVulkanDriver.ENV + "=/linux/icd.json"), environment)
    }
}
