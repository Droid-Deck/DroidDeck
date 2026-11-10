package com.droiddeck.launcher.audio

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PulseAudioComponentTest {
    @Test
    fun restartsOnlyForTheCurrentUnexpectedHost() {
        assertTrue(PulseAudioComponent.shouldRestart(false, 31463, 31463))
        assertFalse(PulseAudioComponent.shouldRestart(true, 31463, 31463))
        assertFalse(PulseAudioComponent.shouldRestart(false, 31463, 31464))
        assertFalse(PulseAudioComponent.shouldRestart(false, -1, 31463))
    }
}
