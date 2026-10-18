package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.HeadPose
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class RayNeoOrientationFilterTest {
    private fun sample(tick: Long, accel: FloatArray, gyro: FloatArray = FloatArray(3)) =
        RayNeoSensorSample(accel, gyro, 0f, FloatArray(3), 0f, 0f, tick)

    private fun screen(q: FloatArray): FloatArray {
        val p = RayNeoOrientationFilter.glassesPose(q)
        return HeadPose.fromGlasses(p[0], p[1], p[2], p[3])
    }

    @Test fun gravityCorrectionSettlesTowardTiltInsteadOfAwayFromIt() {
        val filter = RayNeoOrientationFilter(0f)
        filter.update(sample(0, floatArrayOf(0f, 9.81f, 0f)))
        val angle = Math.toRadians(20.0)
        val accel = floatArrayOf(0f, 9.81f * cos(angle).toFloat(), -9.81f * sin(angle).toFloat())
        var q = FloatArray(4)
        repeat(1000) { q = filter.update(sample((it + 1) * 100L, accel)) }
        assertArrayEquals(floatArrayOf(cos(angle / 2).toFloat(), sin(angle / 2).toFloat(), 0f, 0f), q, 0.002f)
    }

    @Test fun initialGravityDoesNotRotateAfterTheFirstRecenter() {
        val filter = RayNeoOrientationFilter(0f)
        val accel = floatArrayOf(4.905f, 8.4957f, 0f)
        val first = filter.update(sample(0, accel))
        var last = first
        repeat(1000) { last = filter.update(sample((it + 1) * 100L, accel)) }
        assertArrayEquals(first, last, 0.0001f)
        assertEquals(sin(Math.toRadians(15.0)).toFloat(), first[3], 0.001f)
    }

    @Test fun recordedGtLevelGravityStartsWithAnUprightHorizon() {
        val q = RayNeoOrientationFilter(0f, gt = true)
            .update(sample(0, floatArrayOf(5.697f, 0.001f, 7.947f)))
        val m = screen(q)
        val relative = HeadPose.relative(HeadPose.recenter(m), m)
        assertArrayEquals(HeadPose.IDENTITY, relative, 0.02f)
    }

    @Test fun gtLeftTurnLooksLeftWithoutPitchOrRoll() = checkGtMotion(0)
    @Test fun gtLookingUpLooksUpWithoutYawOrRoll() = checkGtMotion(1)
    @Test fun gtRollTiltsTheHorizonWithoutTurningTheView() = checkGtMotion(2)

    @Test fun nonMaxMountingKeepsYawAndPitchSeparateThroughTheSharedPosePath() {
        val profile = RayNeoSensorProfile.forDevice(true, 0x40)
        assertFalse(profile.gtMaxAxes)
        val mount = Math.toRadians(25.0)
        val s = sin(mount).toFloat(); val c = cos(mount).toFloat()
        for (pitch in listOf(false, true)) {
            val filter = RayNeoOrientationFilter(profile.rotationXDeg, profile.gtMaxAxes)
            val level = floatArrayOf(0f, 9.81f * c, 9.81f * s)
            val ref = HeadPose.recenter(screen(filter.update(sample(0, level))))
            var q = FloatArray(4)
            repeat(100) { i ->
                val angle = Math.toRadians((i + 1) * 0.3)
                val accel = if (pitch) floatArrayOf(0f, 9.81f * cos(mount - angle).toFloat(), 9.81f * sin(mount - angle).toFloat()) else level
                val gyro = if (pitch) floatArrayOf(30f, 0f, 0f) else floatArrayOf(0f, 30f * c, 30f * s)
                q = filter.update(sample((i + 1) * 100L, accel, gyro))
            }
            val forward = HeadPose.apply(HeadPose.relative(ref, screen(q)), floatArrayOf(0f, 0f, -1f))
            assertArrayEquals(if (pitch) floatArrayOf(0f, 0.5f, -0.8660254f) else floatArrayOf(-0.5f, 0f, -0.8660254f), forward, 0.01f)
        }
        assertTrue(RayNeoSensorProfile.forDevice(true, 0x7f).gtMaxAxes)
        assertTrue(RayNeoSensorProfile.forDevice(true, 0x41).gtMaxAxes)
        assertEquals(-20f, RayNeoSensorProfile.forDevice(false, 0x3a).rotationXDeg, 0f)
    }

    private fun checkGtMotion(axis: Int) {
        val mount = Math.toRadians(35.0)
        val s = sin(mount).toFloat(); val c = cos(mount).toFloat()
        val filter = RayNeoOrientationFilter(0f, gt = true)
        val level = floatArrayOf(9.81f * s, 0f, 9.81f * c)
        val first = screen(filter.update(sample(0, level)))
        val ref = HeadPose.recenter(first)
        var q = FloatArray(4)
        // Physical 30-degree turn over one second, through the complete shared
        // VITURE conversion and recenter pipeline. GT signs match device captures.
        repeat(100) { i ->
            val a = Math.toRadians((i + 1) * 0.3)
            val accel = when (axis) {
                1 -> floatArrayOf(9.81f * sin(mount + a).toFloat(), 0f, 9.81f * cos(mount + a).toFloat())
                2 -> floatArrayOf(9.81f * s * cos(a).toFloat(), -9.81f * sin(a).toFloat(), 9.81f * c * cos(a).toFloat())
                else -> level
            }
            val gyro = when (axis) {
                0 -> floatArrayOf(30f * s, 0f, 30f * c)
                1 -> floatArrayOf(0f, -30f, 0f)
                else -> floatArrayOf(-30f * c, 0f, 30f * s)
            }
            q = filter.update(sample((i + 1) * 100L, accel, gyro))
        }
        val view = HeadPose.relative(ref, screen(q))
        val forward = HeadPose.apply(view, floatArrayOf(0f, 0f, -1f))
        val up = HeadPose.apply(view, floatArrayOf(0f, 1f, 0f))
        val c30 = cos(Math.toRadians(30.0)).toFloat()
        val expectedForward = when (axis) {
            0 -> floatArrayOf(-0.5f, 0f, -c30)
            1 -> floatArrayOf(0f, 0.5f, -c30)
            else -> floatArrayOf(0f, 0f, -1f)
        }
        val expectedUp = when (axis) {
            0 -> floatArrayOf(0f, 1f, 0f)
            1 -> floatArrayOf(0f, c30, 0.5f)
            else -> floatArrayOf(-0.5f, c30, 0f)
        }
        assertArrayEquals(expectedForward, forward, 0.01f)
        assertArrayEquals(expectedUp, up, 0.01f)
    }
}
