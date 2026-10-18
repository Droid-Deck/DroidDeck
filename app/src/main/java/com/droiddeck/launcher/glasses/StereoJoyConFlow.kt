package com.droiddeck.launcher.glasses

import org.opencv.core.*
import org.opencv.video.Video

internal class StereoJoyConFlow : AutoCloseable {
    data class Points(val left: List<Point>, val right: List<Point>, val seedNs: Long)
    private val previousLeft = Mat()
    private val previousRight = Mat()
    private val tracks = arrayOfNulls<Points>(2)
    private var previousTime = 0L

    fun reset() {
        previousTime = 0
        tracks.fill(null)
        previousLeft.release(); previousRight.release()
    }

    fun begin(left: Mat, right: Mat, time: Long) {
        for (side in 0..1) {
            val old = tracks[side] ?: continue
            if (!JoyConFlowGate.fresh(old.seedNs, previousTime, time) ||
                previousLeft.size() != left.size() || previousRight.size() != right.size()) {
                tracks[side] = null
                continue
            }
            tracks[side] = try {
                Points(follow(previousLeft, left, old.left), follow(previousRight, right, old.right), old.seedNs)
            } catch (e: CvException) {
                android.util.Log.w("StereoJoyConFlow", "Discarding failed optical flow", e)
                null
            }
        }
    }

    fun points(side: Int): Points? = tracks[side]

    fun seed(side: Int, left: Point, right: Point, time: Long) {
        tracks[side] = Points(listOf(Point(left.x, left.y)), listOf(Point(right.x, right.y)), time)
    }

    fun end(left: Mat, right: Mat, time: Long) {
        left.copyTo(previousLeft); right.copyTo(previousRight); previousTime = time
    }

    private fun follow(previous: Mat, current: Mat, points: List<Point>): List<Point> {
        val indices = points.indices.filter { points[it].x.isFinite() && points[it].y.isFinite() }
        val result = MutableList(points.size) { missing() }
        if (indices.isEmpty()) return result
        val before = MatOfPoint2f(*indices.map { points[it] }.toTypedArray())
        val after = MatOfPoint2f(); val back = MatOfPoint2f()
        val forwardStatus = MatOfByte(); val backStatus = MatOfByte()
        val error = MatOfFloat(); val backError = MatOfFloat()
        try {
            Video.calcOpticalFlowPyrLK(previous, current, before, after, forwardStatus, error)
            Video.calcOpticalFlowPyrLK(current, previous, after, back, backStatus, backError)
            val next = after.toArray(); val returned = back.toArray()
            val valid = forwardStatus.toArray(); val validBack = backStatus.toArray(); val errors = error.toArray()
            for ((i, index) in indices.withIndex()) {
                val n = next[i]; val b = returned[i]; val p = points[index]
                if (JoyConFlowGate.point(n.x,n.y,b.x,b.y,p.x,p.y,errors[i],valid[i].toInt()!=0,
                        validBack[i].toInt()!=0,current.cols(),current.rows())) result[index] = n
            }
        } finally {
            listOf(before, after, back, forwardStatus, backStatus, error, backError).forEach(Mat::release)
        }
        return result
    }

    private fun missing() = Point(Double.NaN, Double.NaN)
    override fun close() = reset()
}
