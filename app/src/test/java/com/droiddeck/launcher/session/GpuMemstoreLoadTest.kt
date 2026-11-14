package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GpuMemstoreLoadTest {
    private var busy = 0L
    private var samples = 0L
    private var moves = 0L
    private var available = true
    private val load = GpuStatsComponent.MemstoreLoad { if (available) longArrayOf(busy, samples, moves) else null }

    private fun tick(newSamples: Long, newBusy: Long, newMoves: Long = 1) {
        samples += newSamples
        busy += newBusy
        moves += newMoves
    }

    @Test fun notTrustedUntilNewWorkIsSeen() {
        assertNull(load.percent())
        tick(500, 400, newMoves = 0)
        assertNull(load.percent())
        assertFalse(load.working)
        tick(500, 300)
        assertEquals(70L, load.percent())
        assertTrue(load.working)
    }

    @Test fun givesUpWhenTheTimestampsNeverMove() {
        load.percent()
        repeat(10) {
            tick(500, 250, newMoves = 0)
            assertNull(load.percent())
        }
        assertTrue(load.failed)
        tick(500, 250)
        assertNull(load.percent())
        assertFalse(load.working)
    }

    @Test fun averagesOverTheLastTwoReads() {
        load.percent()
        tick(500, 500)
        assertEquals(100L, load.percent())
        tick(500, 0)
        assertEquals(50L, load.percent())
        tick(500, 0, newMoves = 0)
        assertEquals(0L, load.percent())
    }

    @Test fun pausingStartsANewWindow() {
        load.percent()
        tick(500, 500)
        load.percent()
        load.pause()
        tick(0, 0, newMoves = 0)
        assertNull(load.percent())
        tick(500, 100)
        assertEquals(20L, load.percent())
    }

    @Test fun noReadingWithoutNewSamplesOrCounts() {
        load.percent()
        tick(500, 100)
        load.percent()
        assertNull(load.percent())
        available = false
        tick(500, 100)
        assertNull(load.percent())
    }
}
