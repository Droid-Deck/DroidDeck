package com.droiddeck.launcher.glasses

import kotlin.math.abs
import kotlin.math.min

internal object StereoFingerGestures {
    data class Result(val trigger: Float = 0f, val triggerValid: Boolean = false,
                      val grip: Boolean = false, val gripValid: Boolean = false)

    fun estimate(left: List<SpatialVector>?, right: List<SpatialVector>?, gripping: Boolean): Result {
        val a = View.create(left) ?: return Result()
        val b = View.create(right) ?: return Result()
        val triggerA = a.pinch()
        val triggerB = b.pinch()
        val triggerValid = triggerA != null && triggerB != null && abs(triggerA-triggerB) <= 0.30f
        val curls = listOf(9,13,17).map { base ->
            val ca = a.curl(base)
            val cb = b.curl(base)
            if (ca != null && cb != null && abs(ca-cb) <= 0.45f) min(ca,cb) else null
        }
        val gripValid = curls.all { it != null }
        val closed = curls.count { it != null && it >= (if (gripping) 0.40f else 0.65f) }
        return Result(if (triggerValid) min(triggerA!!,triggerB!!) else 0f,triggerValid,
            gripValid && closed >= (if (gripping) 2 else 3),gripValid)
    }

    private class View(val points: List<SpatialVector>,val width: Float) {
        private fun point(index: Int) = points[index].takeIf(SpatialVector::finite)
            ?.let { SpatialVector(it.x,it.y) }

        fun pinch(): Float? {
            val thumb = point(4) ?: return null
            val index = point(8) ?: return null
            val wrist = point(0) ?: return null
            if ((thumb-wrist).length() > width*3.5f || (index-wrist).length() > width*3.5f) return null
            return ((0.55f-(thumb-index).length()/width)/0.35f).coerceIn(0f,1f)
        }

        fun curl(base: Int): Float? {
            val wrist = point(0) ?: return null
            val joints = (base..base+3).map { point(it) ?: return null }
            val lengths = joints.zipWithNext { a,b -> (b-a).length() }
            if (lengths.any { it < width*0.005f }) return null
            val path = lengths.sum()
            val reach = (joints[0]-wrist).length()
            if (path !in width*0.15f..width*3f || reach < width*0.20f) return null
            val shortness = ((1.7f-(joints[3]-wrist).length()/reach)/0.55f).coerceIn(0f,1f)
            val bend = ((1f-(joints[3]-joints[0]).length()/path)/0.35f).coerceIn(0f,1f)
            return min(shortness,bend)
        }

        companion object {
            fun create(points: List<SpatialVector>?): View? {
                if (points?.size != 21) return null
                if (listOf(0,5,9,13,17).any { !points[it].finite() }) return null
                fun xy(index: Int) = SpatialVector(points[index].x,points[index].y)
                val across = xy(5)-xy(17)
                val along = xy(9)-xy(0)
                val width = across.length()
                val length = along.length()
                if (!width.isFinite() || !length.isFinite() || width < 1e-3f || length < 1e-3f) return null
                if (width/length !in 0.25f..3f || abs(across.cross(along).z)/(width*length) < 0.15f) return null
                return View(points,width)
            }
        }
    }
}
