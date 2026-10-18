package com.droiddeck.launcher.glasses

import org.json.JSONObject

internal data class CalibrationObservationData(
    val objects: List<DoubleArray>, val left: List<DoubleArray>, val right: List<DoubleArray>, val head: SpatialPose,
) {
    companion object {
        fun parse(view: JSONObject): CalibrationObservationData {
            fun points(name: String, dimensions: Int): List<DoubleArray> {
                val rows=view.getJSONArray(name)
                require(rows.length() in 12..24) { "Invalid saved board corner count" }
                return (0 until rows.length()).map { row ->
                    val values=rows.getJSONArray(row)
                    require(values.length()==dimensions)
                    DoubleArray(dimensions) { values.getDouble(it).also { value -> require(value.isFinite()) } }
                }
            }
            val objects=points("objects",3); val left=points("left",2); val right=points("right",2)
            require(objects.size==left.size && objects.size==right.size)
            val pose=view.getJSONArray("head")
            require(pose.length()==7)
            val head=SpatialPose.fromSdk(FloatArray(7) { pose.getDouble(it).toFloat() }) ?: error("Invalid saved head pose")
            return CalibrationObservationData(objects,left,right,head)
        }
    }
}
