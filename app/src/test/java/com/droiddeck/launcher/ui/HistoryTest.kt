package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class HistoryTest {
    @Test fun theSpanIsTheTimeTheKeptReadingsCover() {
        val h = History(3)
        h.add(1f, 1_000L)
        h.add(2f, 1_500L)
        assertEquals(1, h.spanSeconds())
        h.add(3f, 2_000L)
        h.add(4f, 4_000L)
        assertEquals(listOf(2f, 3f, 4f), h.snapshot())
        assertEquals(3, h.spanSeconds())
    }

    @Test fun aHistoryThatStopsBeingFedKeepsItsSpan() {
        val h = History(4)
        h.add(1f, 10_000L)
        h.add(2f, 20_000L)
        assertEquals(10, h.spanSeconds())
        assertEquals(10, h.spanSeconds())
    }

    @Test fun readingsWithoutATimeHaveNoSpan() {
        val h = History(2)
        h.add(1f)
        assertEquals(0, h.spanSeconds())
        assertEquals(0, History(2).spanSeconds())
        assertEquals(0, History(2).apply { add(1f, 5_000L) }.spanSeconds())
    }
}
