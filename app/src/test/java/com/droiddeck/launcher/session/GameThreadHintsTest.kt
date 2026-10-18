package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GameThreadHintsTest {
    @Test fun `a thread joins above 60 percent and leaves under 15`() {
        assertEquals(emptySet<Int>(), GameThreadHints.pick(emptySet(), mapOf(1 to 0.55)))
        assertEquals(setOf(1), GameThreadHints.pick(emptySet(), mapOf(1 to 0.68)))
        // Faster where it was moved, or capped by the display, it stays boosted at 25%...
        assertEquals(setOf(1), GameThreadHints.pick(setOf(1), mapOf(1 to 0.25)))
        // ...and is let go once it idles.
        assertEquals(emptySet<Int>(), GameThreadHints.pick(setOf(1), mapOf(1 to 0.1)))
    }

    @Test fun `at most two threads, the boosted ones kept before busier newcomers`() {
        val busy = mapOf(1 to 0.2, 2 to 0.9, 3 to 0.8, 4 to 0.7, 5 to 0.95)
        assertEquals(setOf(1, 5), GameThreadHints.pick(setOf(1), busy))
    }

    @Test fun `a thread gone from proc is dropped`() {
        assertEquals(setOf(2), GameThreadHints.pick(setOf(1, 2), mapOf(2 to 0.7)))
    }

    @Test fun `a game at the panel's rate or a fraction of it holds pace`() {
        assertTrue(GameThreadHints.holdsPace(119.0, 120.0))
        assertTrue(GameThreadHints.holdsPace(60.5, 120.0))
        assertTrue(GameThreadHints.holdsPace(30.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(92.0, 120.0))   // falling behind: boost
        assertFalse(GameThreadHints.holdsPace(105.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(3.0, 120.0))    // headless or loading: boost
    }
}
