package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPacingTest {
    @Test fun phoneOutputIsUnpaced() {
        assertEquals(SessionPrefs.FPS_UNPACED, SessionPrefs.compositorFpsLimit(0))
    }

    @Test fun sessionLimitPacesThePhone() {
        assertEquals(45, SessionPrefs.compositorFpsLimit(0, 45))
    }

    @Test fun sessionLimitNeverExceedsTheGlassesRate() {
        assertEquals(60, SessionPrefs.compositorFpsLimit(60, 90))
        assertEquals(30, SessionPrefs.compositorFpsLimit(60, 30))
    }

    @Test fun mirroringKeepsTheGlassesRate() {
        assertEquals(60, SessionPrefs.compositorFpsLimit(60))
    }
}
