package com.droiddeck.launcher.glasses

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcStreamHealthTest {
    private fun sample(frames: Long = 901, lastMs: Long = 30990, endMs: Long = 31000,
                       maximumGapMs: Long = 34) = LongArray(14).apply {
        this[6]=lastMs*1_000_000
        this[7]=1_000_000_000
        this[8]=maximumGapMs*1_000_000
        this[9]=frames
        this[12]=endMs*1_000_000
        this[13]=(endMs-lastMs)*1_000_000
    }

    @Test fun sustainedStreamMeasuresEntireWindow() {
        val health=UvcStreamHealth.assess(sample(),30,30000)
        assertTrue(health.healthy)
        assertEquals(30000.0,health.observationMs,0.001)
        assertEquals(30.0,health.observedFps,0.001)
    }

    @Test fun earlyFramesCannotHideLongFinalStall() {
        val health=UvcStreamHealth.assess(sample(frames=31,lastMs=2000),30,30000)
        assertFalse(health.healthy)
        assertEquals(1.0,health.observedFps,0.001)
        assertEquals(29000.0,health.maximumGapMs,0.001)
        assertFalse(UvcStreamHealth.assess(sample(frames=1,lastMs=1000),30,30000).healthy)
    }

    @Test fun frameRateCannotHideInterFrameOrFinalGaps() {
        assertFalse(UvcStreamHealth.assess(sample(maximumGapMs=500),30,30000).healthy)
        assertFalse(UvcStreamHealth.assess(sample(lastMs=30500),30,30000).healthy)
    }

    @Test fun shortObservationAndLowFrameRateAreUnhealthy() {
        assertFalse(UvcStreamHealth.assess(sample(frames=31,lastMs=1990,endMs=2000),30,30000).healthy)
        assertFalse(UvcStreamHealth.assess(sample(frames=601),30,30000).healthy)
        assertFalse(UvcStreamHealth.assess(sample().apply { this[4]=-1 },30,30000).healthy)
    }

    @Test fun lowFrameRateAllowsThreeFramePeriods() {
        assertTrue(UvcStreamHealth.assess(sample(frames=151,lastMs=30600,maximumGapMs=400),5,30000).healthy)
        assertFalse(UvcStreamHealth.assess(sample(frames=151,lastMs=30300,maximumGapMs=700),5,30000).healthy)
    }
}
