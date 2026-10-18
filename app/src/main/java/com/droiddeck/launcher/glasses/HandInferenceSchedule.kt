package com.droiddeck.launcher.glasses

internal class HandInferenceSchedule(
    private val stableFrames: Int = 3,
    private val discoveryIntervalMs: Long = 240,
    private val maximumGapMs: Long = 400
) {
    init {
        require(stableFrames > 0)
        require(discoveryIntervalMs > 0 && maximumGapMs >= discoveryIntervalMs)
    }

    private var lastFrameMs = -1L
    private var selectedAtMs = -1L
    private var selectedMaximum = 2
    private var lastSearchMs = -1L
    private var candidateSide = -1
    private var consistentFrames = 0
    private var trackingSide = -1

    fun select(timestampMs: Long): Int {
        if (timestampMs < 0) {
            reset()
            return 2
        }
        if (selectedAtMs >= 0 || lastFrameMs >= 0 &&
            (timestampMs <= lastFrameMs || timestampMs - lastFrameMs > maximumGapMs)) reset()
        val maximum = if (trackingSide >= 0 && lastSearchMs >= 0 &&
            timestampMs - lastSearchMs < discoveryIntervalMs) 1 else 2
        lastFrameMs = timestampMs
        selectedAtMs = timestampMs
        selectedMaximum = maximum
        if (maximum == 2) lastSearchMs = timestampMs
        return maximum
    }

    fun observe(timestampMs: Long, leftSides: List<Int>, rightSides: List<Int>,
                leftRawCount: Int = leftSides.size, rightRawCount: Int = rightSides.size) {
        if (timestampMs < 0 || selectedAtMs != timestampMs) {
            reset()
            return
        }
        selectedAtMs = -1L
        val side = leftSides.singleOrNull() ?: -1
        val consistent = leftRawCount == 1 && rightRawCount == 1 && side in 0..1 &&
            rightSides.singleOrNull() == side
        if (!consistent || selectedMaximum == 1 && side != trackingSide) {
            forgetHand()
            return
        }
        if (candidateSide != side) {
            candidateSide = side
            consistentFrames = 0
        }
        consistentFrames = minOf(stableFrames, consistentFrames + 1)
        if (consistentFrames >= stableFrames) trackingSide = side
    }

    fun reset() {
        lastFrameMs = -1L
        selectedAtMs = -1L
        selectedMaximum = 2
        lastSearchMs = -1L
        forgetHand()
    }

    private fun forgetHand() {
        candidateSide = -1
        consistentFrames = 0
        trackingSide = -1
    }
}
