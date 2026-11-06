package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class DeviceInfoTest {
    @Test fun builtinLabelsDistinguishPowerVrFromTurnipAndGenericSystemVulkan() {
        assertEquals("System PowerVR", DeviceInfo.builtinDriverLabel(SystemVulkanDriver.Profile.POWERVR, runtime = true))
        assertEquals("System Xclipse", DeviceInfo.builtinDriverLabel(SystemVulkanDriver.Profile.XCLIPSE, runtime = false))
        assertEquals("the runtime's own Turnip", DeviceInfo.builtinDriverLabel(null, runtime = true))
        assertEquals("System Vulkan", DeviceInfo.builtinDriverLabel(null, runtime = false))
    }
}
