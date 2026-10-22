package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.R
import java.util.Locale
import kotlin.math.roundToInt

internal object SpatialMeasurements {
    fun height(centimeters: Int): String {
        val tenths = (centimeters / 2.54 * 10).roundToInt()
        return String.format(Locale.getDefault(), "%d cm (%d ft %.1f in)", centimeters, tenths / 120, tenths % 120 / 10.0)
    }

    fun distance(meters: Float): String = String.format(
        Locale.getDefault(), "%.1f m (%.1f ft)", meters, meters / 0.3048f)

    fun validation(meters: Float): String = String.format(
        Locale.getDefault(), "%.1f cm (%.2f in)", meters * 100, meters / 0.0254f)
}

internal enum class CalibrationDistance(val meters: Float, val labelRes: Int) {
    INCHES_12(0.3048f, R.string.spatial_distance_12in),
    CENTIMETERS_30(0.3f, R.string.spatial_distance_30cm),
    INCHES_20(0.508f, R.string.spatial_distance_20in),
    CENTIMETERS_50(0.5f, R.string.spatial_distance_50cm),
}

internal data class SpatialCalibrationProgress(
    val collecting: Boolean = false,
    val views: Int = 0,
    val fitted: Boolean = false,
    val solving: Boolean = false,
    val capturing: Boolean = false,
    val captureMessage: String? = null,
    val validating: Boolean = false,
    val validationMessage: String? = null,
)
