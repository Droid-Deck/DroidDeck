package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class OutputPacingTest {
    @Test fun phoneOutputIsUnpaced() {
        assertEquals(SessionPrefs.FPS_UNPACED, SessionPrefs.compositorFpsLimit(0))
    }

    @Test fun mirroringKeepsTheGlassesRate() {
        assertEquals(60, SessionPrefs.compositorFpsLimit(60))
    }
}
