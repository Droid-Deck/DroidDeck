package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class HandProcessingRateTest {
    private fun sample(rate: HandProcessingRate, processingMs: Long = 35, ageMs: Long = 65,
                       healthy: Boolean = true): Int =
        rate.observe(processingMs * 1_000_000, ageMs * 1_000_000, healthy)

    private fun fastest(rate: HandProcessingRate) {
        repeat(16) { sample(rate) }
        assertEquals(800, rate.permille)
    }

    @Test fun beginsConservativelyAndRequiresEightHealthySamplesPerIncrease() {
        val rate = HandProcessingRate()
        assertEquals(500, rate.permille)
        repeat(7) { assertEquals(500, sample(rate)) }
        assertEquals(600, sample(rate))
        repeat(7) { assertEquals(600, sample(rate)) }
        assertEquals(800, sample(rate))
        repeat(20) { assertEquals(800, sample(rate)) }
    }

    @Test fun onlySelectsFifteenHzWhenTwentyHzHasInsufficientHeadroom() {
        val rate = HandProcessingRate()
        repeat(8) { sample(rate, 50, 80) }
        assertEquals(600, rate.permille)
        repeat(30) { assertEquals(600, sample(rate, 50, 80)) }
    }

    @Test fun slowProcessingStaysAtTwelvePointFiveHz() {
        val rate = HandProcessingRate()
        repeat(30) { assertEquals(500, sample(rate, 60, 100)) }
    }

    @Test fun sustainedProcessingPastTheCurrentPeriodReducesRate() {
        val rate = HandProcessingRate()
        fastest(rate)
        repeat(2) { assertEquals(800, sample(rate, 70)) }
        assertEquals(600, sample(rate, 70))
        repeat(7) { assertEquals(600, sample(rate, 70)) }
        assertEquals(500, sample(rate, 70))
        repeat(7) { assertEquals(500, sample(rate)) }
    }

    @Test fun largeProcessingSpikeSkipsDirectlyToConservativeRate() {
        val rate = HandProcessingRate()
        fastest(rate)
        assertEquals(500, sample(rate, 80, 100))
    }

    @Test fun ageLimitReducesRateWithoutWaitingForProcessingAverage() {
        val rate = HandProcessingRate()
        fastest(rate)
        assertEquals(500, sample(rate, ageMs = 140))
        repeat(7) { assertEquals(500, sample(rate)) }
        assertEquals(600, sample(rate))
    }

    @Test fun agingFramesCannotRaiseTheRate() {
        val rate = HandProcessingRate()
        repeat(30) { assertEquals(500, sample(rate, ageMs = 111)) }
        repeat(8) { sample(rate, ageMs = 100) }
        assertEquals(600, rate.permille)
        repeat(30) { assertEquals(600, sample(rate, ageMs = 91)) }
    }

    @Test fun marginalHeadroomInterruptsConsecutiveUpshiftSamples() {
        val rate = HandProcessingRate()
        repeat(7) { sample(rate, 55) }
        assertEquals(500, sample(rate, 60))
        repeat(7) { assertEquals(500, sample(rate)) }
        assertEquals(600, sample(rate))
    }

    @Test fun recoveryMustAlsoBringAverageProcessingBelowUpshiftThreshold() {
        val rate = HandProcessingRate()
        sample(rate, 100)
        repeat(8) { assertEquals(500, sample(rate)) }
        repeat(8) { sample(rate) }
        assertEquals(600, rate.permille)
    }

    @Test fun trackingLossAndInvalidTimingResetTheRateAndHistory() {
        for (invalid in listOf(Triple(35_000_000L, 65_000_000L, false),
            Triple(0L, 65_000_000L, true), Triple(-1L, 65_000_000L, true),
            Triple(35_000_000L, -1L, true))) {
            val rate = HandProcessingRate()
            fastest(rate)
            assertEquals(500, rate.observe(invalid.first, invalid.second, invalid.third))
            repeat(7) { assertEquals(500, sample(rate)) }
            assertEquals(600, sample(rate))
        }
    }

    @Test fun lifecycleResetDropsOldHeadroom() {
        val rate = HandProcessingRate()
        fastest(rate)
        assertEquals(500, rate.reset())
        repeat(7) { assertEquals(500, sample(rate)) }
        assertEquals(600, sample(rate))
    }

    @Test fun periodicDiscoverySearchesPermitFifteenHzButNotTwentyHz() {
        val rate = HandProcessingRate()
        repeat(8) { sample(rate, if (it % 3 == 2) 74 else 36, 90) }
        assertEquals(600, rate.permille)
        for (frame in 8 until 90) {
            assertEquals(600, sample(rate, if (frame % 3 == 2) 74 else 36, 90))
        }
    }

    @Test fun continuouslySlowDiscoveryCannotRaiseRate() {
        val rate = HandProcessingRate()
        repeat(30) { assertEquals(500, sample(rate, 70, 100)) }
    }
}
