package com.droiddeck.launcher.ui

import com.droiddeck.launcher.gpu.FrameGen
import org.junit.Assert.assertEquals
import org.junit.Test

class BottomQuickPageTest {
    @Test
    fun theFrameLimitTileStepsThroughCommonCapsAndBackToOff() {
        assertEquals(30, QuickTiles.nextFps(0))
        assertEquals(40, QuickTiles.nextFps(30))
        assertEquals(60, QuickTiles.nextFps(45))
        assertEquals(0, QuickTiles.nextFps(60))
        assertEquals(0, QuickTiles.nextFps(90))
        assertEquals(40, QuickTiles.nextFps(36))
    }

    @Test
    fun theFrameGenerationTileTogglesTheBuiltInEngine() {
        assertEquals(FrameGen.ENGINE_DDFG, QuickTiles.nextFrameGen(FrameGen.Mode.OFF).engine)
        assertEquals(FrameGen.Mode.OFF, QuickTiles.nextFrameGen(FrameGen.Mode(FrameGen.ENGINE_LSFG, 3)))
    }
}
