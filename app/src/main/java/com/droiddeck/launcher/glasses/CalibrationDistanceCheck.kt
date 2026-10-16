package com.droiddeck.launcher.glasses

import kotlin.math.abs

internal data class CalibrationDistanceResult(val accepted: Boolean, val message: String)

internal object CalibrationDistanceCheck {
    fun evaluate(expected: Float, corners: Int, points: List<SpatialVector>?): CalibrationDistanceResult {
        require(expected.isFinite() && expected in 0.15f..1.5f)
        if (corners != 24) return CalibrationDistanceResult(false,
            "$corners / 24 checkerboard corners detected in both cameras. Keep the full board visible and hold still. If it is already visible, select 12 in or 30 cm and measure that closer distance before retrying.")
        if (points?.size != 24 || points.any { !it.finite() }) return CalibrationDistanceResult(false,
            "The board is visible, but its depth could not be measured reliably. Hold the glasses still and keep the board flat and well lit, then retry.")
        val measured=(points.reduce { a,b -> a+b }*(1f/points.size)).length()
        if (!measured.isFinite()) return CalibrationDistanceResult(false,"The camera measurement was invalid. Hold still and try again.")
        val accepted=abs(measured-expected)<0.05f
        return CalibrationDistanceResult(accepted,
            "Cameras measured ${SpatialMeasurements.validation(measured)}; selected distance ${SpatialMeasurements.validation(expected)}. " +
                if (accepted) "Distance check passed." else "Recheck the distance from the left camera to the board center, then retry.")
    }
}
