package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Test

class GameThreadHintsTest {
    @Test fun `a thread joins above 60 percent and leaves under 30`() {
        assertEquals(emptySet<Int>(), GameThreadHints.pick(emptySet(), mapOf(1 to 0.55)))
        assertEquals(setOf(1), GameThreadHints.pick(emptySet(), mapOf(1 to 0.68)))
        // Faster where it was moved, it stays boosted at 45%...
        assertEquals(setOf(1), GameThreadHints.pick(setOf(1), mapOf(1 to 0.45)))
        // ...and is let go once it idles.
        assertEquals(emptySet<Int>(), GameThreadHints.pick(setOf(1), mapOf(1 to 0.2)))
    }

    @Test fun `at most three threads, the boosted ones kept before busier newcomers`() {
        val busy = mapOf(1 to 0.4, 2 to 0.9, 3 to 0.8, 4 to 0.7, 5 to 0.95)
        assertEquals(setOf(1, 5, 2), GameThreadHints.pick(setOf(1), busy))
    }

    @Test fun `a thread gone from proc is dropped`() {
        assertEquals(setOf(2), GameThreadHints.pick(setOf(1, 2), mapOf(2 to 0.7)))
    }
}
