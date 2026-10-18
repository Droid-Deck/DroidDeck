package com.droiddeck.launcher.glasses

import org.junit.Assert.*
import org.junit.Test

class JoyConWristPositionTest {
    private fun points(depth: Float = .4f): MutableList<SpatialVector?> = MutableList<SpatialVector?>(21) { null }.apply {
        this[0] = SpatialVector(0f, 0f, depth)
        this[5] = SpatialVector(.035f, -.06f, depth)
        this[9] = SpatialVector(0f, -.065f, depth)
        this[13] = SpatialVector(-.02f, -.06f, depth)
        this[17] = SpatialVector(-.045f, -.05f, depth)
    }

    @Test fun grippingControllerNeedsOnlyTheWristAndNeverGeneratesFingerGestures() {
        val estimator = JoyConWristPosition()
        val full = estimator.estimate(points(), SpatialPose(), 1_000_000_000)!!
        val occluded = MutableList<SpatialVector?>(21) { null }.apply { this[0] = points()[0] }
        val grip = estimator.estimate(occluded, SpatialPose(), 1_080_000_000)!!
        assertEquals(occluded[0], grip.pose.position)
        assertEquals(full.pose.position, grip.pose.position)
        assertTrue(grip.valid)
        assertFalse(grip.triggerValid)
        assertFalse(grip.gripValid)
        assertNull(HandGestures(false).estimate(occluded, SpatialPose(), 1_080_000_000))
    }

    @Test fun erroneousKnucklesCannotMoveOrRejectTheWrist() {
        val malformed = points().apply {
            this[5] = SpatialVector(Float.NaN, 0f, .4f)
            this[9] = SpatialVector(4f, 5f, 6f)
            this[13] = this[0]
            this[17] = null
        }
        val grip = JoyConWristPosition().estimate(malformed, SpatialPose(), 1)!!
        assertEquals(malformed[0], grip.pose.position)
    }

    @Test fun measuredExtensionMovesTheWorldPositionByTheSameDistance() {
        val estimator = JoyConWristPosition()
        val world = SpatialPose(SpatialVector(1f, 2f, 3f), SpatialRotation(y = 1f, w = 0f))
        val near = estimator.estimate(points(.3f).take(1), world, 1_000_000_000)!!
        val far = estimator.estimate(points(.7f).take(1), world, 1_300_000_000)!!
        assertEquals(SpatialVector(1f, 2f, 2.7f), near.pose.position)
        assertEquals(-.4f, far.pose.position.z - near.pose.position.z, .0001f)
        assertEquals(near.pose.position.x, far.pose.position.x, .0001f)
        assertEquals(1_300_000_000, far.timeNs)
    }

    @Test fun missingOrNonfiniteWristIsRejectedEvenWithVisibleKnuckles() {
        for (wrist in listOf(null, SpatialVector(Float.NaN, 0f, .4f), SpatialVector(0f, 0f, Float.POSITIVE_INFINITY))) {
            val estimator = JoyConWristPosition()
            assertNull(estimator.estimate(points().apply { this[0] = wrist }, SpatialPose(), 1))
            assertEquals(HandObservationOutcome.WRIST_LANDMARK_MISSING, estimator.lastOutcome)
        }
        assertNull(JoyConWristPosition().estimate(emptyList(), SpatialPose(), 1))
    }

    @Test fun implausibleJumpsAndRepeatedFramesStayRejectedWithoutPoisoningTheLastPosition() {
        val estimator = JoyConWristPosition()
        assertNotNull(estimator.estimate(points(), SpatialPose(), 1_000_000_000))
        assertNull(estimator.estimate(points(1.2f), SpatialPose(), 1_080_000_000))
        assertEquals(HandObservationOutcome.WRIST_MOTION_REJECTED, estimator.lastOutcome)
        assertNull(estimator.estimate(points(), SpatialPose(), 1_000_000_000))
        assertNotNull(estimator.estimate(points(.45f).take(1), SpatialPose(), 1_090_000_000))
        estimator.reset()
        assertNotNull(estimator.estimate(points(1.2f).take(1), SpatialPose(), 1_100_000_000))
    }
}
