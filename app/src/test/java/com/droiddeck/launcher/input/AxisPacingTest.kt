package com.droiddeck.launcher.input

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AxisPacingTest {
    @Test fun theFirstReportIsWrittenAtOnce() {
        assertEquals(100L, AxisPacing(4).dueAt(100, true))
    }

    @Test fun stickMotionInsideTheIntervalWaitsForItsEnd() {
        val pacing = AxisPacing(4)
        pacing.wrote(100)
        assertEquals(104L, pacing.dueAt(101, true))
        assertEquals(104L, pacing.dueAt(103, true))
        assertEquals(104L, pacing.dueAt(104, true))
        assertEquals(110L, pacing.dueAt(110, true))
    }

    @Test fun buttonsAndRestTransitionsAreNeverHeldBack() {
        val pacing = AxisPacing(4)
        pacing.wrote(100)
        assertEquals(101L, pacing.dueAt(101, false))
    }

    @Test fun onlyMotionThatStaysOffRestIsPaced() {
        assertTrue(AxisPacing.keepsMoving(0, 0))
        assertTrue(AxisPacing.keepsMoving(1200, 1300))
        assertTrue(AxisPacing.keepsMoving(-40, 25))
        assertFalse(AxisPacing.keepsMoving(0, 900))
        assertFalse(AxisPacing.keepsMoving(900, 0))
    }

    @Test fun aThousandHertzPadReachesTheGuestAtTheIntervalRate() {
        val writes = feed(AxisPacing(4), sampleMs = 1, durationMs = 1000)
        assertTrue("$writes writes", writes in 249..251)
    }

    @Test fun aPadSlowerThanTheIntervalIsNeverDelayed() {
        val pacing = AxisPacing(4)
        for (now in 0L until 1000L step 5) {
            assertEquals(now, pacing.dueAt(now, true))
            pacing.wrote(now)
        }
    }

    private fun feed(pacing: AxisPacing, sampleMs: Long, durationMs: Long): Int {
        var writes = 0
        var pendingAt = -1L
        for (now in 0L until durationMs step sampleMs) {
            if (pendingAt in 0..now) {
                pacing.wrote(pendingAt)
                writes++
                pendingAt = -1
            }
            val due = pacing.dueAt(now, true)
            if (due <= now) {
                pacing.wrote(now)
                writes++
            } else if (pendingAt < 0) {
                pendingAt = due
            }
        }
        return writes
    }
}
