package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    @Test fun theSpanIsTheTimeSinceTheOldestKeptReading() {
        val h = History(3)
        h.add(1f, 1_000L)
        h.add(2f, 1_500L)
        assertEquals(2, h.spanSeconds(3_200L))
        h.add(3f, 2_000L)
        h.add(4f, 2_500L)
        assertEquals(listOf(2f, 3f, 4f), h.snapshot())
        assertEquals(2, h.spanSeconds(3_600L))
    }

    @Test fun readingsWithoutATimeHaveNoSpan() {
        val h = History(2)
        h.add(1f)
        assertEquals(0, h.spanSeconds(10_000L))
        assertEquals(0, History(2).spanSeconds(10_000L))
    }
}
