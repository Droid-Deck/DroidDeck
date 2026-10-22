package com.droiddeck.launcher.session

import com.droiddeck.launcher.session.BootMovie.Step
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootMoviePlanTest {
    @Test
    fun fullVolumeWithoutLimitPassesTheSourceThrough() {
        val plan = BootMovie.plan(100, 0)
        assertTrue(plan.passthrough)
        assertTrue(plan.steps.isEmpty())
    }

    @Test
    fun fullVolumeWithLimitKeepsSoundAndFallsBackToTheSource() {
        val plan = BootMovie.plan(100, 5)
        assertFalse(plan.passthrough)
        assertEquals(listOf(Step.REMUX_AUDIO, Step.TRANSCODE_AUDIO), plan.steps)
        assertTrue(plan.sourceOnFailure)
    }

    @Test
    fun silentGoesWithoutSoundAndNeverFallsBackToTheSource() {
        val plan = BootMovie.plan(0, 0)
        assertEquals(listOf(Step.REMUX_SILENT, Step.TRANSCODE_SILENT), plan.steps)
        assertFalse(plan.sourceOnFailure)
    }

    @Test
    fun partialVolumeDegradesToSilenceNotToFullVolume() {
        val plan = BootMovie.plan(40, 0)
        assertEquals(listOf(Step.REGAIN, Step.REMUX_SILENT, Step.TRANSCODE_AUDIO), plan.steps)
        assertFalse(plan.passthrough)
        assertFalse(plan.sourceOnFailure)
    }

    @Test
    fun framesBeyondFullHdAreOversizedInEitherOrientation() {
        assertTrue(BootMovie.oversized(3840, 2160))
        assertTrue(BootMovie.oversized(2160, 3840))
        assertFalse(BootMovie.oversized(1920, 1080))
        assertFalse(BootMovie.oversized(1280, 800))
        assertFalse(BootMovie.oversized(1080, 1920))
    }

    @Test
    fun anOversizedMovieIsAlwaysConvertedAndNeverFallsBackToTheSource() {
        for (volume in listOf(0, 40, 100)) {
            val plan = BootMovie.oversizedPlan(volume)
            assertFalse(plan.passthrough)
            assertFalse(plan.sourceOnFailure)
        }
        assertEquals(listOf(Step.TRANSCODE_SILENT), BootMovie.oversizedPlan(0).steps)
        assertEquals(listOf(Step.TRANSCODE_AUDIO), BootMovie.oversizedPlan(60).steps)
    }
}
