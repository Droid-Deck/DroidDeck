package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class LayerStepTest {
    @Test fun theStackRunsFromTheProtonDownToTheGpuDrivers() {
        assertEquals(listOf(PROTON_TAB, "dxvk", "vkd3d", "fex", GPU_TAB), LAYERS)
    }

    @Test fun rbStepsDownAndWraps() {
        assertEquals("dxvk", layerStep(PROTON_TAB, 1))
        assertEquals(PROTON_TAB, layerStep(GPU_TAB, 1))
    }

    @Test fun lbStepsUpAndWraps() {
        assertEquals("fex", layerStep(GPU_TAB, -1))
        assertEquals(GPU_TAB, layerStep(PROTON_TAB, -1))
    }

    @Test fun anUnknownLayerStartsFromTheTop() {
        assertEquals("dxvk", layerStep("tab-from-an-older-build", 1))
    }
}
