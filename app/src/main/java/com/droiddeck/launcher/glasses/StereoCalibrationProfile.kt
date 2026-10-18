package com.droiddeck.launcher.glasses

import org.json.JSONObject

internal data class CalibrationMatrix(val rows: Int, val columns: Int, val values: DoubleArray)

internal data class StereoCalibrationProfile(
    val identity: String, val width: Int, val height: Int, val fisheye: Boolean,
    val matrices: List<CalibrationMatrix>, val cameraToHead: SpatialPose, val error: Double,
    val verified: Boolean, val bundledBase: Boolean = false,
) {
    companion object {
        const val BASE_ASSET = "calibration/luma-ultra-640x480.json"
        const val BASE_IDENTITY = "luma-ultra-base-v1"

        fun select(saved: String?, base: () -> String?, identity: String?, width: Int, height: Int): StereoCalibrationProfile? {
            if (identity != null && saved != null) parse(saved, identity, width, height)?.let { return it }
            return base()?.let { parse(it, identity ?: BASE_IDENTITY, width, height, bundledBase = true) }
        }

        fun parse(text: String, identity: String, width: Int, height: Int,
                  bundledBase: Boolean = false): StereoCalibrationProfile? = runCatching {
            val data = JSONObject(text)
            require(data.getInt("version") == 1 && data.getInt("width") == width && data.getInt("height") == height)
            if (bundledBase) {
                require(data.getString("identity") == BASE_IDENTITY && data.getString("deviceModel") == "VITURE Luma Ultra")
                require(data.getString("cameraPair") == "left0-right0" && width == 640 && height == 480)
            } else require(data.getString("identity") == identity)
            val fisheye = data.getBoolean("fisheye")
            val matrices = listOf("k1", "d1", "k2", "d2", "r", "t").map { name ->
                val matrix = data.getJSONObject(name)
                val rows = matrix.getInt("rows"); val columns = matrix.getInt("cols")
                val values = matrix.getJSONArray("data")
                require(rows in 1..14 && columns in 1..14 && values.length() == rows * columns)
                CalibrationMatrix(rows, columns, DoubleArray(values.length()) { values.getDouble(it).also { n -> require(n.isFinite()) } })
            }
            require(listOf(0, 2, 4).all { matrices[it].rows == 3 && matrices[it].columns == 3 })
            require(matrices[5].rows == 3 && matrices[5].columns == 1)
            require(listOf(1, 3).all {
                val matrix = matrices[it]
                (matrix.rows == 1 || matrix.columns == 1) && matrix.values.size in if (fisheye) listOf(4) else listOf(4, 5, 8, 12, 14)
            })
            require(listOf(0, 2).all { matrices[it].values[0] > 0 && matrices[it].values[4] > 0 })
            val pose = data.getJSONArray("cameraToHead"); require(pose.length() == 7)
            val cameraToHead = SpatialPose.fromSdk(FloatArray(7) { pose.getDouble(it).toFloat() }) ?: error("Invalid camera transform")
            val error = data.getDouble("error"); require(error.isFinite() && error in 0.0..2.0)
            StereoCalibrationProfile(identity, width, height, fisheye, matrices, cameraToHead, error,
                verified = !bundledBase && data.getBoolean("verified"), bundledBase = bundledBase)
        }.getOrNull()
    }
}
