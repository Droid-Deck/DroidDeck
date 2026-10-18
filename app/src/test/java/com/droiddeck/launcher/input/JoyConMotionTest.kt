package com.droiddeck.launcher.input

import com.droiddeck.launcher.glasses.Quaternion
import com.droiddeck.launcher.glasses.Vec3
import kotlin.math.PI
import kotlin.math.roundToInt
import org.junit.Assert.*
import org.junit.Test

class JoyConMotionTest {
    private val bias = Vec3(0.3f, -0.25f, 0.2f)

    @Test fun restingInSeveralPositionsLearnsTheAccelerometerOffset() {
        val calibration = JoyConAccelCalibration()
        assertFalse(calibration.ready)
        var learned = false
        for (up in directions) repeat(200) { learned = calibration.addResting(up * G + bias) || learned }
        assertTrue(learned)
        assertTrue(calibration.ready)
        assertVec(bias, calibration.bias, 0.01f)
        assertEquals(1f, calibration.scale, 1e-3f)
        assertVec(Vec3(0f, G, 0f), calibration.correct(Vec3(0f, G, 0f) + bias), 0.01f)
    }

    @Test fun restingInOnePositionTeachesNothing() {
        val calibration = JoyConAccelCalibration()
        repeat(2000) { assertFalse(calibration.addResting(Vec3(0f, G, 0f) + bias)) }
        assertFalse(calibration.ready)
        assertVec(Vec3(1f, 2f, 3f), calibration.correct(Vec3(1f, 2f, 3f)))
    }

    @Test fun aSavedOffsetComesBackAndAnImplausibleOneIsIgnored() {
        val first = JoyConAccelCalibration()
        for (up in directions) repeat(200) { first.addResting(up * G + bias) }
        val restored = JoyConAccelCalibration().apply { restore(first.saved()) }
        assertTrue(restored.ready)
        assertVec(first.bias, restored.bias, 0f)
        assertEquals(first.scale, restored.scale, 0f)
        for (bad in listOf(floatArrayOf(3f, 0f, 0f, 1f), floatArrayOf(0f, 0f, 0f, 1.5f), floatArrayOf(Float.NaN, 0f, 0f, 1f), floatArrayOf(0f, 0f, 1f))) {
            val c = JoyConAccelCalibration().apply { restore(bad) }
            assertFalse(bad.contentToString(), c.ready)
            assertNull(c.saved())
        }
    }

    @Test fun aLiftIsMeasuredAndTheHandStopsWhenItDoes() {
        val motion = JoyConMotion()
        still(motion, 1f)
        val lift = 0.25f
        val seconds = 0.5f
        val steps = (seconds / DT).roundToInt()
        for (i in 1..steps) {
            val s = (i - 0.5f) / steps
            val a = lift / (seconds * seconds) * (60 * s - 180 * s * s + 120 * s * s * s)
            motion.update(Quaternion.IDENTITY, Vec3(0f, G + a, 0f), Vec3(0f, 0f, 0f), true, DT)
        }
        assertEquals(lift, motion.displacement.y, 0.01f)
        assertTrue("moving up at ${motion.velocity}", motion.velocity.length() < 0.05f)
        still(motion, 2f)
        assertEquals(lift, motion.displacement.y, 0.015f)
        assertEquals(0f, motion.velocity.length(), 1e-3f)
        assertEquals(0f, motion.displacement.x, 1e-3f)
        assertEquals(0f, motion.displacement.z, 1e-3f)
    }

    @Test fun theInertialVelocityKeepsAccelerationThatStillnessWouldDrop() {
        val motion = JoyConMotion()
        still(motion, 1f)
        repeat((2f / DT).roundToInt()) { motion.update(Quaternion.IDENTITY, Vec3(0f, G + 0.05f, 0f), Vec3(0f, 0f, 0f), true, DT) }
        assertEquals(0f, motion.velocity.y, 1e-3f)
        assertEquals(0.1f, motion.inertial.y, 0.005f)
        val before = motion.inertial.y
        val seconds = 0.25f
        repeat((seconds / DT).roundToInt()) { motion.update(Quaternion.IDENTITY, Vec3(2f, G, 0f), Vec3(0f, 0f, 0f), true, DT) }
        assertEquals(2f * seconds, motion.inertial.x, 0.01f)
        assertEquals(before, motion.inertial.y, 0.005f)
    }

    @Test fun aSidewaysPushIsMeasuredInTheWorldFrame() {
        val motion = JoyConMotion()
        val turned = Quaternion.fromAxisAngle(Vec3(0f, 0f, 1f), (PI / 2).toFloat())
        val down = turned.conjugate().rotate(Vec3(0f, G, 0f))
        repeat((1f / DT).toInt()) { motion.update(turned, down, Vec3(0f, 0f, 0f), true, DT) }
        val steps = (0.4f / DT).roundToInt()
        for (i in 1..steps) {
            val s = (i - 0.5f) / steps
            val a = 0.3f / (0.4f * 0.4f) * (60 * s - 180 * s * s + 120 * s * s * s)
            motion.update(turned, down + turned.conjugate().rotate(Vec3(a, 0f, 0f)), Vec3(0f, 0f, 0f), true, DT)
        }
        assertEquals(0.3f, motion.displacement.x, 0.015f)
        assertEquals(0f, motion.displacement.y, 0.01f)
    }

    @Test fun turningInPlaceDoesNotMoveTheHand() {
        val motion = JoyConMotion()
        still(motion, 1f)
        val rate = 2f
        var q = Quaternion.IDENTITY
        repeat((1f / DT).toInt()) {
            q = q.integrateBodyRate(Vec3(0f, 0f, rate), DT).normalized()
            motion.update(q, q.conjugate().rotate(Vec3(0f, G, 0f)), Vec3(0f, 0f, rate), true, DT)
        }
        repeat((1f / DT).toInt()) { motion.update(q, q.conjugate().rotate(Vec3(0f, G, 0f)), Vec3(0f, 0f, 0f), true, DT) }
        assertEquals(0f, motion.displacement.length(), 0.005f)
    }

    @Test fun anUncalibratedJoyConAtRestDoesNotDrift() {
        val motion = JoyConMotion()
        val filter = JoyConOrientation()
        val tilted = Vec3(0.3f, G + 0.35f, -0.2f)
        repeat((30f / DT).toInt()) {
            filter.update(tilted, Vec3(0f, 0f, 0f), DT)
            if (filter.ready) motion.update(filter.rotation, tilted, filter.rate, false, DT)
        }
        assertTrue("drifted ${motion.displacement}", motion.displacement.length() < 0.05f)
        assertEquals(0f, motion.velocity.length(), 1e-3f)
    }

    @Test fun longHandlingWithATiltErrorCannotRunAway() {
        val motion = JoyConMotion()
        still(motion, 1f)
        val leaking = Vec3(0.5f, G, 0f)
        repeat((10f / DT).toInt()) { motion.update(Quaternion.IDENTITY, leaking, Vec3(0f, 0f, 1f), true, DT) }
        assertTrue("velocity ${motion.velocity}", motion.velocity.length() < 0.15f)
        assertTrue("moved ${motion.displacement}", motion.displacement.length() < 1.5f)
    }

    @Test fun onlyAStillJoyConCountsAsResting() {
        val motion = JoyConMotion()
        still(motion, 0.2f)
        assertFalse(motion.resting)
        still(motion, 0.2f)
        assertTrue(motion.resting)
        motion.update(Quaternion.IDENTITY, Vec3(0f, G, 0f), Vec3(0.3f, 0f, 0f), true, DT)
        assertFalse(motion.resting)
    }

    private fun still(motion: JoyConMotion, seconds: Float) {
        repeat((seconds / DT).toInt()) { motion.update(Quaternion.IDENTITY, Vec3(0f, G, 0f), Vec3(0f, 0f, 0f), true, DT) }
    }

    private fun assertVec(want: Vec3, got: Vec3, tolerance: Float = 1e-4f) {
        assertEquals("x of $got", want.x, got.x, tolerance)
        assertEquals("y of $got", want.y, got.y, tolerance)
        assertEquals("z of $got", want.z, got.z, tolerance)
    }

    private companion object {
        const val G = 9.80665f
        const val DT = 0.005f
        val directions = listOf(Vec3(0f, 1f, 0f), Vec3(0f, -1f, 0f), Vec3(1f, 0f, 0f), Vec3(-1f, 0f, 0f), Vec3(0f, 0f, 1f), Vec3(0f, 0f, -1f))
    }
}
