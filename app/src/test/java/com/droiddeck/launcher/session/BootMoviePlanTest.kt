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
        assertEquals(listOf(Step.REGAIN, Step.REMUX_SILENT, Step.TRANSCODE_SILENT), plan.steps)
        assertFalse(plan.passthrough)
        assertFalse(plan.sourceOnFailure)
    }
}
