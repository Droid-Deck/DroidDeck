package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {
    @Test fun systemDriverLabelsNameEachVendorForRuntimeAndDisplay() {
        val profiles = mapOf(
            SystemVulkanDriver.Profile.POWERVR to "System PowerVR",
            SystemVulkanDriver.Profile.XCLIPSE to "System Xclipse",
            SystemVulkanDriver.Profile.MALI to "System Mali",
        )
        for ((profile, expected) in profiles) {
            assertEquals(expected, DeviceInfo.builtinDriverLabel(profile, runtime = true))
            assertEquals(expected, DeviceInfo.builtinDriverLabel(profile, runtime = false))
        }
    }

    @Test fun supportMessagesUseTheSameVendorAsTheDriverProfile() {
        val families = mapOf(
            SystemVulkanDriver.Profile.POWERVR to GpuInfo.Family.POWERVR,
            SystemVulkanDriver.Profile.XCLIPSE to GpuInfo.Family.XCLIPSE,
            SystemVulkanDriver.Profile.MALI to GpuInfo.Family.MALI,
        )
        for ((profile, family) in families) {
            val gpu = GpuInfo(profile.gpuName, 0, family, "", false)
            val prefix = if (profile == SystemVulkanDriver.Profile.POWERVR) "System" else "Experimental system"
            assertEquals("$prefix ${profile.gpuName} driver: compatibility varies by device", gpu.supportText)
            assertEquals(profile, family.systemProfile)
        }
    }

    @Test fun nonSystemGpuKeepsRuntimeAndDisplayDefaults() {
        assertEquals("the runtime's own Turnip", DeviceInfo.builtinDriverLabel(null, runtime = true))
        assertEquals("System Vulkan", DeviceInfo.builtinDriverLabel(null, runtime = false))
    }
}
