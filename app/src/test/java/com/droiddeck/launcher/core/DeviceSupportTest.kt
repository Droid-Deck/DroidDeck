package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeviceSupportTest {
    @Test fun runtimeDownloadAcceptsOnlyRecognizedGraphicsBackends() {
        assertTrue(DeviceSupport.graphicsAvailable(adreno = true, powerVrReady = false))
        assertTrue(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = true))
        // A PowerVR HAL without usable DRM access also reaches this unavailable case.
        assertFalse(DeviceSupport.graphicsAvailable(adreno = false, powerVrReady = false))
    }

    @Test fun bridgeServesEveryNonAdrenoWithoutASystemDriverRoute() {
        assertTrue(DeviceSupport.bridgeUsable(adreno = false, systemDriverReady = false))
        assertFalse(DeviceSupport.bridgeUsable(adreno = true, systemDriverReady = false))
        assertFalse(DeviceSupport.bridgeUsable(adreno = false, systemDriverReady = true))
    }
}
