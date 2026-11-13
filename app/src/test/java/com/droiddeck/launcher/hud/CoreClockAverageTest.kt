package com.droiddeck.launcher.hud

import org.junit.Assert.assertEquals
import org.junit.Test

class CoreClockAverageTest {
    private val freqs = longArrayOf(902_400L, 1_459_200L, 2_803_200L)

    @Test fun aCoreShowsTheClockItRanAtThroughTheSample() {
        val before = arrayOf(freqs, longArrayOf(100L, 50L, 10L))
        val after = arrayOf(freqs, longArrayOf(125L, 50L, 35L))
        assertEquals(1_852_800L, HudMetrics.averageKhz(before, after))
    }

    @Test fun theFirstSampleOrAChangedTableFallsBack() {
        val after = arrayOf(freqs, longArrayOf(1L, 2L, 3L))
        assertEquals(-1L, HudMetrics.averageKhz(null, after))
        assertEquals(-1L, HudMetrics.averageKhz(arrayOf(longArrayOf(1L), longArrayOf(0L)), after))
        assertEquals(-1L, HudMetrics.averageKhz(after, after))
    }

    @Test fun theCpuClockIsTheFastestCore() {
        assertEquals(2_803, HudMetrics.fastestCoreMhz(intArrayOf(902, 2_803, 1_459)))
        assertEquals(null, HudMetrics.fastestCoreMhz(intArrayOf(0, 0)))
    }
}
