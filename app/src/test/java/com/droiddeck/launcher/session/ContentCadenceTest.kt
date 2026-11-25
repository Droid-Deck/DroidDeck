package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ContentCadenceTest {
    private val rates = intArrayOf(60, 90, 120, 144)

    @Test
    fun aMeasuredRateSnapsToAStepAPanelRateDividesInto() {
        assertEquals(45, ContentCadence.snap(44, rates))
        assertEquals(48, ContentCadence.snap(49, rates))
        assertEquals(72, ContentCadence.snap(72, rates))
        assertEquals(60, ContentCadence.snap(61, rates))
        assertEquals(0, ContentCadence.snap(53, rates))
        assertEquals(0, ContentCadence.snap(12, rates))
        assertEquals(0, ContentCadence.snap(45, intArrayOf(60)))
    }

    @Test
    fun aSteadyRateEngagesOnlyAfterItHolds() {
        val cadence = ContentCadence()
        assertFalse(cadence.note(45, rates))
        assertFalse(cadence.note(44, rates))
        assertEquals(0, cadence.rate)
        assertTrue(cadence.note(45, rates))
        assertEquals(45, cadence.rate)
    }

    @Test
    fun aWanderingRateNeverEngages() {
        val cadence = ContentCadence()
        for (fps in intArrayOf(45, 53, 60, 71, 48, 45, 66, 45, 45, 58)) assertFalse(cadence.note(fps, rates))
        assertEquals(0, cadence.rate)
    }

    @Test
    fun oneOffSampleDoesNotReleaseAnEngagedRate() {
        val cadence = ContentCadence()
        repeat(3) { cadence.note(72, rates) }
        assertFalse(cadence.note(55, rates))
        assertFalse(cadence.note(72, rates))
        assertFalse(cadence.note(51, rates))
        assertFalse(cadence.note(53, rates))
        assertEquals(72, cadence.rate)
        assertTrue(cadence.note(55, rates))
        assertEquals(0, cadence.rate)
    }

    @Test
    fun aRateThatWasReleasedMustHoldLongerToEngageAgain() {
        val cadence = ContentCadence()
        repeat(3) { cadence.note(45, rates) }
        repeat(3) { cadence.note(57, rates) }
        assertEquals(0, cadence.rate)
        repeat(5) { assertFalse(cadence.note(45, rates)) }
        assertTrue(cadence.note(45, rates))
        assertEquals(45, cadence.rate)
    }

    @Test
    fun aReleasedRateDoesNotDelayARateThatWasNeverReleased() {
        val cadence = ContentCadence()
        repeat(3) { cadence.note(45, rates) }
        repeat(3) { cadence.note(57, rates) }
        assertEquals(0, cadence.rate)
        repeat(2) { assertFalse(cadence.note(60, rates)) }
        assertTrue(cadence.note(60, rates))
        assertEquals(60, cadence.rate)
    }

    @Test
    fun aResetDropsTheEngagedRateAndReportsTheChangeOnce() {
        val cadence = ContentCadence()
        repeat(3) { cadence.note(60, rates) }
        assertTrue(cadence.reset())
        assertEquals(0, cadence.rate)
        assertFalse(cadence.reset())
        repeat(2) { assertFalse(cadence.note(60, rates)) }
        assertTrue(cadence.note(60, rates))
    }
}
