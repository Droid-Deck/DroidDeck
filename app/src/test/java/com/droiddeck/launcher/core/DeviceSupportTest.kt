package com.droiddeck.launcher.core

import com.droiddeck.launcher.gpu.GpuInfo
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

    @Test fun maliDetectionAcceptsEitherKernelNodeOrDetectedMaliFamilyAndNeverAdreno() {
        assertTrue(DeviceSupport.isMali(adreno = false, maliNode = true, family = GpuInfo.Family.NOT_ADRENO))
        assertTrue(DeviceSupport.isMali(adreno = false, maliNode = false, family = GpuInfo.Family.MALI_G720))
        assertTrue(DeviceSupport.isMali(adreno = false, maliNode = false, family = GpuInfo.Family.MALI_OTHER))
        assertFalse(DeviceSupport.isMali(adreno = false, maliNode = false, family = GpuInfo.Family.POWERVR))
        assertFalse(DeviceSupport.isMali(adreno = false, maliNode = false, family = GpuInfo.Family.NOT_ADRENO))
        assertFalse(DeviceSupport.isMali(adreno = true, maliNode = true, family = GpuInfo.Family.MALI_G720))
    }
}
