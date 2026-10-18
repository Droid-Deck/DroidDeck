package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test
import java.util.Locale

class SpatialMeasurementsTest {
    @Test fun validationKeepsExactImperialAndMetricMeasurements() {
        assertEquals(0.3048f, CalibrationDistance.INCHES_12.meters, 0.000001f)
        assertEquals(0.3f, CalibrationDistance.CENTIMETERS_30.meters, 0.000001f)
        assertEquals(0.508f, CalibrationDistance.INCHES_20.meters, 0.000001f)
        assertEquals(0.5f, CalibrationDistance.CENTIMETERS_50.meters, 0.000001f)
    }

    @Test fun heightCarriesRoundedInchesIntoFeetAndAlwaysShowsBothUnits() {
        val previous = Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            assertEquals("160 cm (5 ft 3.0 in)", SpatialMeasurements.height(160))
            assertEquals("183 cm (6 ft 0.0 in)", SpatialMeasurements.height(183))
            assertEquals("1.0 m (3.3 ft)", SpatialMeasurements.distance(1f))
            assertEquals("50.8 cm (20.00 in)", SpatialMeasurements.validation(0.508f))
        } finally { Locale.setDefault(previous) }
    }
}
