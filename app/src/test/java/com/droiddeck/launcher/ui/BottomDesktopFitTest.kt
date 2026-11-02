package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class BottomDesktopFitTest {
    @Test fun aDesktopOfThePanelsShapeFillsThePanel() {
        assertEquals(1280 to 870, fitDesktop(1280, 870, 1280, 870))
        assertEquals(1280 to 870, fitDesktop(1280, 870, 1278, 868))
    }

    @Test fun aRunningBottomDesktopKeepsItsShapeOnTheWiderTopScreen() {
        val (w, h) = fitDesktop(1920, 1016, 1280, 870)
        assertEquals(1016, h)
        assertEquals(1494, w)
    }

    @Test fun nothingToFitLeavesThePanelSize() {
        assertEquals(1920 to 1016, fitDesktop(1920, 1016, 0, 0))
    }
}
