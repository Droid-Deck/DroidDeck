package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuModelFallbackTest {
    @Test fun aPlatformCodeNameGivesItsAdreno() {
        // AYANEO's Pocket FIT: KGSL says "Adreno33v2", the platform says pineapple.
        assertEquals(750, GpuInfo.platformModel("pineapple"))
        assertEquals(740, GpuInfo.platformModel("KALAMA"))
        assertEquals(830, GpuInfo.platformModel("sun"))
        assertEquals(0, GpuInfo.platformModel("somethingelse"))
        assertEquals(GpuInfo.Family.A7XX, GpuInfo.familyOf(true, GpuInfo.platformModel("pineapple")))
    }
}
