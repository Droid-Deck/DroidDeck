package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionVisibilityTest {
    @Test fun xrVisibilitySurvivesPanelHandoffAndPanelScreenState() {
        for (panelVisible in listOf(false, true)) {
            for (screenOn in listOf(false, true)) {
                assertTrue(sessionHasVisibleSurface(panelVisible, screenOn, true))
            }
        }
    }

    @Test fun normalPanelStillRequiresAnInteractiveScreen() {
        assertTrue(sessionHasVisibleSurface(true, true, false))
        assertFalse(sessionHasVisibleSurface(false, true, false))
        assertFalse(sessionHasVisibleSurface(true, false, false))
        assertFalse(sessionHasVisibleSurface(false, false, false))
    }

    @Test fun losingXrVisibilityReturnsToNormalSuspendRules() {
        assertTrue(sessionHasVisibleSurface(false, false, true))
        assertFalse(sessionHasVisibleSurface(false, false, false))
        assertTrue(sessionHasVisibleSurface(true, true, false))
    }
}
