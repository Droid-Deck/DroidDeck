package com.droiddeck.launcher.glasses

import android.content.Context
import com.droiddeck.launcher.R
import kotlin.math.abs

internal data class CalibrationDistanceResult(val accepted: Boolean, val message: String)

internal object CalibrationDistanceCheck {
    fun evaluate(context: Context, expected: Float, corners: Int, points: List<SpatialVector>?): CalibrationDistanceResult {
        require(expected.isFinite() && expected in 0.15f..1.5f)
        if (corners != 24) return CalibrationDistanceResult(false,
            context.getString(R.string.spatial_distance_corners, corners))
        if (points?.size != 24 || points.any { !it.finite() }) return CalibrationDistanceResult(false,
            context.getString(R.string.spatial_distance_depth))
        val measured=(points.reduce { a,b -> a+b }*(1f/points.size)).length()
        if (!measured.isFinite()) return CalibrationDistanceResult(false,context.getString(R.string.spatial_distance_invalid))
        val accepted=abs(measured-expected)<0.05f
        return CalibrationDistanceResult(accepted,
            context.getString(R.string.spatial_distance_result,
                SpatialMeasurements.validation(measured), SpatialMeasurements.validation(expected),
                context.getString(if (accepted) R.string.spatial_distance_passed else R.string.spatial_distance_retry)))
    }
}
