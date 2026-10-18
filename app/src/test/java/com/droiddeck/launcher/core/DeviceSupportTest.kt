package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportTest {
    @Test fun runtimeDownloadAcceptsOnlyRecognizedGraphicsBackends() {
        assertTrue(DeviceSupport.graphicsAvailable(adreno = true, systemVulkanReady = false))
        assertTrue(DeviceSupport.graphicsAvailable(adreno = false, systemVulkanReady = true))
        // A PowerVR HAL without usable DRM access also reaches this unavailable case.
        assertFalse(DeviceSupport.graphicsAvailable(adreno = false, systemVulkanReady = false))
    }
}
