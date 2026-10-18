package com.droiddeck.launcher.glasses

internal enum class HandObservationOutcome {
    ACCEPTED, DISABLED, CALIBRATION_REQUIRED, CLOCK_NOT_READY, HEAD_TRACKING_INVALID,
    CAMERA_NOT_SYNCHRONIZED, STALE_OBSERVATION, INFERENCE_UNAVAILABLE,
    NO_MATCHING_DETECTION, AMBIGUOUS_HANDEDNESS, ONE_CAMERA_ONLY, STEREO_REJECTED,
    LANDMARK_COUNT_INVALID, PALM_LANDMARK_MISSING, PALM_WIDTH_INVALID, PALM_BASIS_INVALID,
    PALM_MOTION_REJECTED, WRIST_LANDMARK_MISSING, WRIST_MOTION_REJECTED
}

internal data class HandObservationDiagnostic(
    val outcome: HandObservationOutcome,
    val reconstructedPalmLandmarks: List<Int> = emptyList(),
    val triggerEstimate: Float = 0f,
    val triggerGeometryValid: Boolean = false,
    val gripEstimate: Boolean = false,
    val gripGeometryValid: Boolean = false,
    val positionSource: String = "none"
) {
    val accepted get() = outcome == HandObservationOutcome.ACCEPTED
    val triggerValid get() = accepted && triggerGeometryValid
    val gripValid get() = accepted && gripGeometryValid
}

internal data class HandSideMetrics(
    val observations: Long,
    val accepted: Long,
    val triggerValid: Long,
    val gripValid: Long,
    val triggerAboveClickThreshold: Long,
    val triggerReleased: Long,
    val gripPressed: Long,
    val trackingLosses: Long,
    val triggerLosses: Long,
    val gripLosses: Long,
    val maximumConsecutiveRejected: Long,
    val outcomes: Map<HandObservationOutcome, Long>
)

internal data class HandTrackingMetricSnapshot(
    val processedPairs: Long,
    val inferencePairs: Long,
    val left: HandSideMetrics,
    val right: HandSideMetrics
)

internal class HandTrackingMetrics {
    private class Side {
        val outcomes = LongArray(HandObservationOutcome.entries.size)
        var triggerValid = 0L
        var gripValid = 0L
        var triggerAboveClickThreshold = 0L
        var triggerReleased = 0L
        var gripPressed = 0L
        var trackingLosses = 0L
        var triggerLosses = 0L
        var gripLosses = 0L
        var consecutiveRejected = 0L
        var maximumConsecutiveRejected = 0L
        var wasAccepted = false
        var wasTriggerValid = false
        var wasGripValid = false

        fun observe(sample: HandObservationDiagnostic) {
            outcomes[sample.outcome.ordinal]++
            if (sample.triggerValid) {
                triggerValid++
                if (sample.triggerEstimate > 0.65f) triggerAboveClickThreshold++
                if (sample.triggerEstimate < 0.25f) triggerReleased++
            }
            if (sample.gripValid) {
                gripValid++
                if (sample.gripEstimate) gripPressed++
            }
            if (wasAccepted && !sample.accepted) trackingLosses++
            if (wasTriggerValid && !sample.triggerValid) triggerLosses++
            if (wasGripValid && !sample.gripValid) gripLosses++
            consecutiveRejected = if (sample.accepted) 0 else consecutiveRejected + 1
            maximumConsecutiveRejected = maxOf(maximumConsecutiveRejected, consecutiveRejected)
            wasAccepted = sample.accepted
            wasTriggerValid = sample.triggerValid
            wasGripValid = sample.gripValid
        }

        fun snapshot() = HandSideMetrics(outcomes.sum(), outcomes[HandObservationOutcome.ACCEPTED.ordinal],
            triggerValid, gripValid, triggerAboveClickThreshold, triggerReleased, gripPressed, trackingLosses, triggerLosses, gripLosses,
            maximumConsecutiveRejected, HandObservationOutcome.entries.filter { outcomes[it.ordinal] > 0 }
                .associateWith { outcomes[it.ordinal] })
    }

    private val sides = Array(2) { Side() }
    private var lastSequence = 0L
    private var processedPairs = 0L
    private var inferencePairs = 0L

    fun observe(sequence: Long, inferred: Boolean, left: HandObservationDiagnostic,
                right: HandObservationDiagnostic) {
        if (sequence <= lastSequence) return
        lastSequence = sequence
        processedPairs++
        if (inferred) inferencePairs++
        sides[0].observe(left)
        sides[1].observe(right)
    }

    fun snapshot() = HandTrackingMetricSnapshot(processedPairs, inferencePairs, sides[0].snapshot(), sides[1].snapshot())
}
