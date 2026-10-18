package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.HeadPose
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.cos
import kotlin.math.sin

class XrealOrientationFilterTest {
    private fun sample(time: Long, gyro: Vec3 = Vec3(0f, 0f, 0f), accel: Vec3 = Vec3(0f, -9.81f, 0f)) =
        XrealProtocol.Sample(time, gyro, accel)
    private fun matrix(q: FloatArray) = HeadPose.fromGlasses(q[0], q[1], q[2], q[3])

    @Test fun firstPoseAlignsGravityAndDuplicateOrReversedTimeIsDiscarded() {
        val filter = XrealOrientationFilter()
        val tilted = Vec3(4.905f, -8.4957f, 0f)
        val first = filter.update(sample(1000000, accel = tilted))!!
        assertNull(filter.update(sample(1000000)))
        assertNull(filter.update(sample(999999)))
        assertArrayEquals(first, filter.update(sample(2000000, accel = tilted)), 0.0001f)
    }

    @Test fun reconnectGapDoesNotIntegrateSecondsOfGyro() {
        val filter = XrealOrientationFilter()
        val first = filter.update(sample(1000000))!!
        assertArrayEquals(first, filter.update(sample(5000000000, Vec3(2f, 2f, 2f))), 0.0001f)
    }

    @Test fun invalidStartupGravityWaitsForAStationarySample() {
        val filter = XrealOrientationFilter()
        assertNull(filter.update(sample(1000000, accel = Vec3(0f, 0f, 0f))))
        assertNotNull(filter.update(sample(2000000)))
    }

    @Test fun observedNegativeYawTurnsLeftThirtyDegreesAtOneKhzThroughSharedPosePath() {
        val filter = XrealOrientationFilter()
        val first = matrix(filter.update(sample(1000000))!!)
        var last = FloatArray(4)
        repeat(1000) { last = filter.update(sample((it + 2) * 1000000L, Vec3(0f, -(Math.PI / 6).toFloat(), 0f)))!! }
        val view = HeadPose.relative(HeadPose.recenter(first), matrix(last))
        assertArrayEquals(floatArrayOf(-0.5f, 0f, -0.8660254f), HeadPose.apply(view, floatArrayOf(0f, 0f, -1f)), 0.001f)
    }

    @Test fun observedPositivePitchAndRollLookUpAndTiltRightWithoutCrossingAxes() {
        for (roll in listOf(false, true)) {
            val filter = XrealOrientationFilter()
            val first = matrix(filter.update(sample(1000000))!!)
            var last = FloatArray(4)
            repeat(1000) {
                val angle = (it + 1) * Math.PI / 6000
                val accel = if (roll) Vec3(-9.81f * sin(angle).toFloat(), -9.81f * cos(angle).toFloat(), 0f)
                    else Vec3(0f, -9.81f * cos(angle).toFloat(), 9.81f * sin(angle).toFloat())
                val rate = (Math.PI / 6).toFloat()
                last = filter.update(sample((it + 2) * 1000000L,
                    if (roll) Vec3(0f, 0f, rate) else Vec3(rate, 0f, 0f), accel))!!
            }
            val view = HeadPose.relative(HeadPose.recenter(first), matrix(last))
            val actual = HeadPose.apply(view, if (roll) floatArrayOf(0f, 1f, 0f) else floatArrayOf(0f, 0f, -1f))
            assertArrayEquals(if (roll) floatArrayOf(0.5f, 0.8660254f, 0f) else floatArrayOf(0f, 0.5f, -0.8660254f), actual, 0.002f)
        }
    }

    @Test fun capturedNeutralGravityStartsUprightBeforeRecenter() {
        val pose = XrealOrientationFilter().update(sample(1000000,
            accel = Vec3(-0.0909f, -9.6529f, -1.0731f)))!!
        // An upright start must not contain the previous approximately 180-degree
        // pitch correction. Check before recenter can hide a mounting error.
        assertTrue(kotlin.math.abs(pose[3]) > 0.99f)
        val screen = matrix(pose)
        assertTrue(screen[7] > 0.99f) // Screen up points toward shared world +Z.
    }
}
