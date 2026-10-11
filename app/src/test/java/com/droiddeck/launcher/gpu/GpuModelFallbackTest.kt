package com.droiddeck.launcher.gpu

import org.junit.Assert.assertEquals
import org.junit.Test

class GpuModelFallbackTest {
    @Test fun aPlatformCodeNameGivesItsAdreno() {
        // AYANEO's Pocket FIT: KGSL says "Adreno33v2", the platform says pineapple.
        assertEquals(750, GpuInfo.platformModel("pineapple"))
        assertEquals(740, GpuInfo.platformModel("KALAMA"))
        assertEquals(830, GpuInfo.platformModel("sun"))
        assertEquals(741, GpuInfo.platformModel("hamoa"))
        assertEquals(741, GpuInfo.platformModel("sc8380xp"))
        assertEquals(741, GpuInfo.platformModel("x1e80100"))
        assertEquals(741, GpuInfo.platformModel("purwa"))
        assertEquals(0, GpuInfo.platformModel("somethingelse"))
        assertEquals(GpuInfo.Family.A7XX, GpuInfo.familyOf(true, GpuInfo.platformModel("pineapple")))
        assertEquals(GpuInfo.Family.A7XX, GpuInfo.familyOf(true, GpuInfo.platformModel("hamoa")))
    }

    @Test fun alphanumericModelNamesAreParsed() {
        assertEquals(741, GpuInfo.parseModel("Adreno X1-85"))
        assertEquals(741, GpuInfo.parseModel("Adreno(TM) X1-85"))
        assertEquals(741, GpuInfo.parseModel("X1-45"))
        assertEquals(741, GpuInfo.parseModel("Adreno 741"))
        assertEquals(750, GpuInfo.parseModel("Adreno750"))
        assertEquals(0, GpuInfo.parseModel("unknown"))
    }
}
