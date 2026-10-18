package com.droiddeck.launcher.glasses

import com.droiddeck.launcher.gpu.HeadPose
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GlassesMenuPoseTest {
    private val time = 1_000_000_000L

    @Test fun firstFreshGlassesPoseCentersTheScreenInAllThreeAxes() {
        val pose = GlassesMenuPose(spatialEnabled = { false })
        pose.onPose(0.2f, -0.3f, 0.1f, 0.9f, time)

        val frame = pose.snapshot(time)

        assertTrue(frame.valid)
        assertArrayEquals(HeadPose.IDENTITY, frame.rotation, 1e-5f)
        assertArrayEquals(FloatArray(3), frame.position, 0f)
    }

    @Test fun pageConfigurationPreservesTheThreeDofAnchorUntilExplicitRecenter() {
        val pose = GlassesMenuPose(spatialEnabled = { false })
        pose.onPose(0f, 0f, 0f, 1f, time)
        pose.snapshot(time)
        pose.onPose(0f, 0f, 0.1f, 0.995f, time+1)
        val moved = pose.snapshot(time+1)

        pose.configure(GlassesSettings(screenWidthDm = 40))
        assertArrayEquals(moved.rotation, pose.snapshot(time+2).rotation, 0f)
        assertTrue(moved.rotation.zip(HeadPose.IDENTITY.toList()).any { (a, b) -> kotlin.math.abs(a-b) > 0.1f })

        pose.recenter()
        assertArrayEquals(HeadPose.IDENTITY, pose.snapshot(time+3).rotation, 1e-5f)
    }

    @Test fun invalidOldAndFutureSamplesCannotReplaceTheLastSafeView() {
        val pose = GlassesMenuPose(spatialEnabled = { false })
        pose.onPose(0f, 0f, 0f, 1f, time)
        pose.snapshot(time)
        pose.onPose(0f, 0.1f, 0f, 0.995f, time+1)
        val valid = pose.snapshot(time+1)
        pose.onPose(Float.NaN, 0f, 0f, 1f, time+2)
        pose.onPose(0f, 0f, 0f, 0f, time+3)
        pose.onPose(0.1f, 0f, 0f, 0.995f, time)

        val stale = pose.snapshot(time+GlassesMenuPose.MAX_AGE_NS+2)
        assertFalse(stale.valid)
        assertArrayEquals(valid.rotation, stale.rotation, 0f)
        pose.onPose(0.1f, 0f, 0f, 0.995f, time+1_000_000_000L)
        val future = pose.snapshot(time+20)
        assertFalse(future.valid)
        assertArrayEquals(valid.rotation, future.rotation, 0f)
    }

    @Test fun detachResetDiscardsOldOriginAndHeldPose() {
        val pose = GlassesMenuPose(spatialEnabled = { false })
        pose.onPose(0f, 0f, 0f, 1f, time)
        pose.snapshot(time)
        pose.onPose(0f, 0.2f, 0f, 0.98f, time+1)
        pose.snapshot(time+1)

        pose.reset()
        assertFalse(pose.snapshot(time+2).valid)
        assertArrayEquals(HeadPose.IDENTITY, pose.snapshot(time+2).rotation, 0f)
        pose.onPose(0.2f, 0f, 0f, 0.98f, time+3)
        assertArrayEquals(HeadPose.IDENTITY, pose.snapshot(time+3).rotation, 1e-5f)
    }

    @Test fun spatialScreenPoseKeepsMetresAndHoldsDuringTrackingLoss() {
        var current: SpatialPose? = SpatialPose(SpatialVector(0.2f, -0.1f, 0.3f), SpatialRotation(y = 0.1f, w = 0.995f))
        var recenters = 0
        val pose = GlassesMenuPose({ true }, { current }, { recenters++ })
        pose.configure(GlassesSettings(positionalTracking = true))
        val valid = pose.snapshot(time)

        assertTrue(valid.spatial)
        assertTrue(valid.valid)
        assertArrayEquals(floatArrayOf(0.2f, -0.1f, 0.3f), valid.position, 0f)
        current = null
        val lost = pose.snapshot(time+1)
        assertFalse(lost.valid)
        assertArrayEquals(valid.position, lost.position, 0f)
        assertArrayEquals(valid.rotation, lost.rotation, 0f)
        pose.recenter()
        assertEquals(1, recenters)
    }

    @Test fun disabledTrackingReturnsAnUntranslatedCenteredView() {
        val pose = GlassesMenuPose({ true }, { SpatialPose(SpatialVector(x = 0.2f)) }, {})
        pose.configure(GlassesSettings(positionalTracking = true))
        pose.snapshot(time)

        pose.configure(GlassesSettings(headTracking = false, positionalTracking = true))

        val frame = pose.snapshot(time+1)
        assertFalse(frame.valid)
        assertArrayEquals(HeadPose.IDENTITY, frame.rotation, 0f)
        assertArrayEquals(FloatArray(3), frame.position, 0f)
    }

    @Test fun menuRecenterChangesTheScreenOriginWithoutMovingTheVrFloorOrigin() {
        val session = SpatialSession()
        session.reset(1, 1.6f)
        session.accept(SpatialSample(SpatialPose(SpatialVector(1f, 2f, 3f)), time, 1, true))
        session.accept(SpatialSample(SpatialPose(SpatialVector(1.2f, 2.1f, 3f)), time+1, 1, true))
        val pose = GlassesMenuPose({ true }, { now -> session.view(true).takeIf { session.valid(now) } },
            { session.recenter(true, time+1) })
        pose.configure(GlassesSettings(positionalTracking = true))
        val vrBefore = session.view(false)!!
        assertArrayEquals(floatArrayOf(0.2f, 0.1f, 0f), pose.snapshot(time+1).position, 1e-5f)

        pose.recenter()

        assertArrayEquals(FloatArray(3), pose.snapshot(time+1).position, 1e-5f)
        assertEquals(vrBefore, session.view(false))
    }

    @Test fun malformedSpatialPoseCannotReplaceTheHeldView() {
        var current = SpatialPose(SpatialVector(x = 0.1f))
        val pose = GlassesMenuPose({ true }, { current }, {})
        pose.configure(GlassesSettings(positionalTracking = true))
        val valid = pose.snapshot(time)

        current = SpatialPose(SpatialVector(x = Float.NaN))
        assertFalse(pose.snapshot(time+1).valid)
        assertArrayEquals(valid.position, pose.snapshot(time+1).position, 0f)
        current = SpatialPose(rotation = SpatialRotation(w = 0f))
        assertFalse(pose.snapshot(time+2).valid)
        assertArrayEquals(valid.rotation, pose.snapshot(time+2).rotation, 0f)
    }

    @Test fun snapshotsDoNotExposeTheHeldArraysForMutation() {
        val pose = GlassesMenuPose(spatialEnabled = { false })
        pose.onPose(0f, 0f, 0f, 1f, time)
        pose.snapshot(time).rotation.fill(Float.NaN)

        assertArrayEquals(HeadPose.IDENTITY, pose.snapshot(time+GlassesMenuPose.MAX_AGE_NS+1).rotation, 1e-5f)
    }
}
