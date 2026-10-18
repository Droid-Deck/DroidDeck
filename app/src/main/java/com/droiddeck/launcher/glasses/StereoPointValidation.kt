package com.droiddeck.launcher.glasses

import kotlin.math.abs
import kotlin.math.hypot

internal object StereoPointValidation {
    fun reconstruct(homogeneous: DoubleArray, left: DoubleArray, right: DoubleArray,
                    p1: DoubleArray, p2: DoubleArray): SpatialVector? {
        if (homogeneous.size != 4 || left.size != 2 || right.size != 2 || p1.size != 12 || p2.size != 12) return null
        val w = homogeneous[3]
        if (!w.isFinite() || abs(w) < 1e-8) return null
        val v = DoubleArray(3) { homogeneous[it]/w }
        if (v.any { !it.isFinite() } || v[2] !in 0.15..1.5) return null
        for ((projection,pixel) in listOf(p1 to left,p2 to right)) {
            val q = DoubleArray(3) { row -> (0..2).sumOf { projection[row*4+it]*v[it] } + projection[row*4+3] }
            if (q.any { !it.isFinite() } || pixel.any { !it.isFinite() } || q[2] <= 0 ||
                hypot(q[0]/q[2]-pixel[0],q[1]/q[2]-pixel[1]) > 3.0) return null
        }
        return SpatialVector(v[0].toFloat(),v[1].toFloat(),v[2].toFloat())
    }
}
