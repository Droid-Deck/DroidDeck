package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Test

class BatteryRuntimeEstimatorTest {
    @Test fun estimateUsesChargeDividedByRollingMeanCurrent() {
        val estimator = BatteryRuntimeEstimator()
        assertEquals(1.0, estimator.estimateHours(1_000_000.0, 1_000_000.0)!!, 0.0001)
        assertEquals(1.3333, estimator.estimateHours(2_000_000.0, 2_000_000.0)!!, 0.001)
    }

    @Test fun energyAndPowerFallbacksKeepMicroUnitsConsistent() {
        assertEquals(1_000_000.0, BatteryRuntimeEstimator.chargeMicroampHoursFromEnergy(4_000_000.0, 4_000_000.0)!!, 0.0)
        assertEquals(1_000_000.0, BatteryRuntimeEstimator.currentMicroampsFromPower(4_000_000.0, 4_000_000.0)!!, 0.0)
    }

    @Test fun durationFormattingUsesHoursAndMinutes() {
        assertEquals("2 hrs 15 mins", BatteryRuntimeEstimator.formatDuration(2.25))
        assertEquals("1 hr", BatteryRuntimeEstimator.formatDuration(1.0))
        assertEquals("5 mins", BatteryRuntimeEstimator.formatDuration(5.0 / 60.0))
        assertNotNull(BatteryRuntimeEstimator.formatDuration(0.01))
    }
}
