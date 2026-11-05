package com.droiddeck.launcher.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class DirectAudioTimingTest {
    @Test
    fun lowLatencyOutputKeepsTheDriverDefaults() {
        assertTrue(DirectAudioTiming.environment(lowLatencyOutput = true).isEmpty())
    }

    @Test
    fun outputWithoutLowLatencyGetsALongerPeriodAndBuffer() {
        assertEquals(listOf("AUDIO_DIRECT_PERIOD_MS=20", "AUDIO_DIRECT_MS=60"), DirectAudioTiming.environment(lowLatencyOutput = false))
    }
}
