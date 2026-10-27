package com.droiddeck.launcher.session

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class PerfHudGraphTest {
    @Test fun theGraphShowsOnlyFramesFromThisHudRun() {
        val samples = floatArrayOf(9f, 9f, 9f, 9f, 9f, 16.7f, 33.4f, 16.6f)
        assertArrayEquals(floatArrayOf(16.7f, 33.4f, 16.6f), PerfHud.recent(samples, 105, 102), 0f)
        assertEquals(0, PerfHud.recent(samples, 103, 102).size)
    }

    @Test fun anEarlySessionShowsWhatWasShownSoFar() {
        val samples = floatArrayOf(16.6f, 16.7f, 120f, 0f, 0f, 0f)
        assertArrayEquals(floatArrayOf(16.6f, 16.7f, 50f), PerfHud.recent(samples, 3, 0), 0f)
    }
}
