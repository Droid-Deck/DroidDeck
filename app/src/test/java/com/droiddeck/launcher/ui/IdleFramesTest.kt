package com.droiddeck.launcher.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class IdleFramesTest {
    @Test
    fun idleLoopsDrawThirtyFramesASecondAndShareEachOne() {
        val start = 5_000_000_000L
        val frame = 8_333_333L
        val drawn = (0 until 120).count { IdleFrames.due(start + it * frame) }
        assertEquals(30, drawn)

        val later = start + 1_000_000_000L
        assertEquals(listOf(true, true, true), List(3) { IdleFrames.due(later) })
        assertEquals(listOf(false, false), List(2) { IdleFrames.due(later + frame) })
        assertEquals(listOf(true, true), List(2) { IdleFrames.due(later + 4 * frame) })
    }
}
