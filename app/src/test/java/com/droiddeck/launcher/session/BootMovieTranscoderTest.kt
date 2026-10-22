package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class BootMovieTranscoderTest {
    @Test fun scaleQuietsAndCutsTheSoundAtTheSameTime() {
        val pcm = ShortArray(48_000 * 2) { 1000 }
        assertSame(pcm, BootMovieTranscoder.scale(pcm, 2, 1f, Long.MAX_VALUE))
        val quieter = BootMovieTranscoder.scale(pcm, 2, 0.5f, Long.MAX_VALUE)
        assertEquals(pcm.size, quieter.size)
        assertEquals(500.toShort(), quieter[0])
        val cut = BootMovieTranscoder.scale(pcm, 2, 1f, 500_000L)
        assertEquals(24_000 * 2, cut.size)
    }
}
