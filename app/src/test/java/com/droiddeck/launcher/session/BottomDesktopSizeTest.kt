package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomDesktopSizeTest {
    @Test fun theDuosBottomPanelKeepsItsPixels() {
        assertEquals(1280 to 848, desktopOutputSize(1280, 848, false, 720f))
    }

    @Test fun aLargerPanelRendersBelowItsResolution() {
        assertEquals(826 to 646, desktopOutputSize(1240, 970, false, 720f))
        assertEquals(1280 to 644, desktopOutputSize(1920, 968, false, 720f))
    }

    @Test fun theTopScreenAfterASwapGetsItsOwnPixelsAndTheWideShape() {
        assertEquals(1920 to 968, desktopOutputSize(1920, 968, true, 720f))
        assertEquals(1920 to 968, desktopOutputSize(1920, 969, true, 720f))
    }

    @Test fun noPanelNoSize() {
        assertEquals(0 to 0, desktopOutputSize(0, 848, true, 720f))
        assertEquals(0 to 0, desktopOutputSize(1280, 0, false, 720f))
    }
}
