package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class HandTrackingMetricsTest {
    private val missing = HandObservationDiagnostic(HandObservationOutcome.NO_MATCHING_DETECTION)
    private fun accepted(trigger: Float = 0f, grip: Boolean = false) = HandObservationDiagnostic(
        HandObservationOutcome.ACCEPTED, listOf(0,5,9,13,17), trigger, true, grip, true)

    @Test fun cameraSequencesCountOnceAndSkippedPairsAreNotInvented() {
        val metrics = HandTrackingMetrics()
        metrics.observe(0, true, accepted(), accepted())
        metrics.observe(10, true, accepted(), missing)
        metrics.observe(10, true, missing, accepted())
        metrics.observe(9, true, missing, accepted())
        metrics.observe(15, true, accepted(), missing)
        val result = metrics.snapshot()
        assertEquals(2L, result.processedPairs)
        assertEquals(2L, result.inferencePairs)
        assertEquals(2L, result.left.accepted)
        assertEquals(0L, result.right.accepted)
        assertEquals(2L, result.right.observations)
        assertEquals(0L, result.left.trackingLosses)
    }

    @Test fun setupAndUnavailableFramesRemainDistinctFromInference() {
        val metrics = HandTrackingMetrics()
        val setup = HandObservationDiagnostic(HandObservationOutcome.CALIBRATION_REQUIRED)
        val unavailable = HandObservationDiagnostic(HandObservationOutcome.INFERENCE_UNAVAILABLE)
        metrics.observe(1, false, setup, setup)
        metrics.observe(2, false, unavailable, unavailable)
        metrics.observe(3, true, accepted(), missing)
        val result = metrics.snapshot()
        assertEquals(3L, result.processedPairs)
        assertEquals(1L, result.inferencePairs)
        assertEquals(3L, result.left.observations)
        assertEquals(1L, result.left.accepted)
        assertEquals(1L, result.left.outcomes[HandObservationOutcome.CALIBRATION_REQUIRED])
        assertEquals(1L, result.left.outcomes[HandObservationOutcome.INFERENCE_UNAVAILABLE])
        assertEquals(3L, result.right.maximumConsecutiveRejected)
    }

    @Test fun countsGestureLossesIndependentlyFromPalmLossAndOtherHand() {
        val metrics = HandTrackingMetrics()
        metrics.observe(1, true, accepted(), accepted())
        metrics.observe(2, true, accepted().copy(triggerGeometryValid = false), accepted())
        metrics.observe(3, true, accepted().copy(gripGeometryValid = false), accepted())
        metrics.observe(4, true, missing, accepted())
        metrics.observe(5, true, missing, accepted())
        val result = metrics.snapshot()
        assertEquals(3L, result.left.accepted)
        assertEquals(1L, result.left.trackingLosses)
        assertEquals(2L, result.left.triggerLosses)
        assertEquals(1L, result.left.gripLosses)
        assertEquals(2L, result.left.maximumConsecutiveRejected)
        assertEquals(0L, result.right.trackingLosses)
        assertEquals(0L, result.right.triggerLosses)
        assertEquals(0L, result.right.gripLosses)
    }

    @Test fun rejectedObservationRetainsRawEstimatesButCannotCountUsableInputs() {
        val metrics = HandTrackingMetrics()
        val stale = accepted(1f, true).copy(outcome = HandObservationOutcome.STALE_OBSERVATION)
        assertEquals(1f, stale.triggerEstimate, 0f)
        assertTrue(stale.gripEstimate)
        assertTrue(stale.triggerGeometryValid)
        assertFalse(stale.accepted)
        assertFalse(stale.triggerValid)
        assertFalse(stale.gripValid)
        metrics.observe(1, true, stale, stale)
        val side = metrics.snapshot().left
        assertEquals(0L, side.accepted)
        assertEquals(0L, side.triggerValid)
        assertEquals(0L, side.gripValid)
        assertEquals(0L, side.triggerAboveClickThreshold)
        assertEquals(0L, side.gripPressed)
    }

    @Test fun thresholdCountsDescribeValidEstimatesWithoutClaimingDriverPresses() {
        val metrics = HandTrackingMetrics()
        for ((index, trigger) in listOf(0f, .25f, .65f, 1f).withIndex())
            metrics.observe(index + 1L, true, accepted(trigger, index == 3), missing)
        metrics.observe(5, true, accepted(1f, true).copy(triggerGeometryValid = false, gripGeometryValid = false), missing)
        val side = metrics.snapshot().left
        assertEquals(5L, side.accepted)
        assertEquals(4L, side.triggerValid)
        assertEquals(4L, side.gripValid)
        assertEquals(1L, side.triggerAboveClickThreshold)
        assertEquals(1L, side.triggerReleased)
        assertEquals(1L, side.gripPressed)
    }

    @Test fun snapshotsRetainTheirCountsWhenLaterFramesArrive() {
        val metrics = HandTrackingMetrics()
        metrics.observe(1, true, accepted(), missing)
        val first = metrics.snapshot()
        metrics.observe(2, true, missing, accepted())
        assertEquals(1L, first.processedPairs)
        assertEquals(mapOf(HandObservationOutcome.ACCEPTED to 1L), first.left.outcomes)
        assertEquals(0L, first.left.trackingLosses)
        assertEquals(2L, metrics.snapshot().processedPairs)
        assertEquals(1L, metrics.snapshot().left.trackingLosses)
    }

    @Test fun aNewTrackingConnectionStartsCountsAndSequenceFromZero() {
        val old = HandTrackingMetrics()
        old.observe(500, true, accepted(), missing)
        old.observe(501, true, missing, missing)
        val current = HandTrackingMetrics()
        assertEquals(0L, current.snapshot().processedPairs)
        current.observe(1, true, accepted(), accepted())
        val result = current.snapshot()
        assertEquals(1L, result.processedPairs)
        assertEquals(1L, result.left.accepted)
        assertEquals(0L, result.left.trackingLosses)
        assertEquals(0L, result.right.maximumConsecutiveRejected)
    }
}
