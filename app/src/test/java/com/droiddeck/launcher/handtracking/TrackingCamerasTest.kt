package com.droiddeck.launcher.handtracking

import com.droiddeck.launcher.glasses.Vec3
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test

class TrackingCamerasTest {
    private val wide = TrackingCamera("0", front = false, orientation = 90, focalPixels = 3000f, activeWidth = 4000, activeHeight = 3000, minZoom = 1f, realtime = true)
    private val ultra = TrackingCamera("2", front = false, orientation = 90, focalPixels = 1000f, activeWidth = 4000, activeHeight = 3000, minZoom = 1f, realtime = true)
    private val selfie = TrackingCamera("1", front = true, orientation = 270, focalPixels = 2000f, activeWidth = 4000, activeHeight = 3000, minZoom = 1f, realtime = true)

    @Test fun theBackCameraIsPreferredAndTheWidestBackLensWins() {
        assertSame(ultra, TrackingCameras.pick(listOf(selfie, wide, ultra), null))
        assertSame(ultra, TrackingCameras.pick(listOf(selfie, wide, ultra), TrackingCameras.REAR))
        assertSame(selfie, TrackingCameras.pick(listOf(selfie, wide, ultra), TrackingCameras.FRONT))
    }

    @Test fun aMissingSideFallsBackToWhatTheDeviceHas() {
        assertSame(wide, TrackingCameras.pick(listOf(wide), TrackingCameras.FRONT))
        assertSame(selfie, TrackingCameras.pick(listOf(selfie), null))
        assertNull(TrackingCameras.pick(emptyList(), null))
        assertEquals(setOf(TrackingCameras.REAR), TrackingCameras.sides(listOf(wide, ultra)))
        assertEquals(setOf(TrackingCameras.FRONT, TrackingCameras.REAR), TrackingCameras.sides(listOf(selfie, wide)))
    }

    @Test fun theCameraIsHeldAtItsFastestSteadyFrameRate() {
        val front = listOf(10 to 10, 15 to 15, 10 to 24, 24 to 24, 10 to 30, 30 to 30)
        assertEquals(30 to 30, TrackingCameras.steadyFps(front))
        assertEquals(24 to 24, TrackingCameras.steadyFps(listOf(15 to 15, 10 to 30, 24 to 24, 60 to 60)))
        assertEquals(15 to 30, TrackingCameras.steadyFps(listOf(7 to 30, 15 to 30)))
        assertNull(TrackingCameras.steadyFps(emptyList()))
    }

    @Test fun zoomingOutOnlyHappensWhenTheLensAllowsIt() {
        assertEquals(1f, wide.zoom(), 0f)
        val logical = TrackingCamera("0", false, 90, 3000f, 4000, 3000, minZoom = 0.6f, realtime = true)
        assertEquals(0.6f, logical.zoom(), 1e-6f)
        val roomy = TrackingCamera("0", false, 90, 3000f, 4000, 3000, minZoom = 0.3f, realtime = true)
        assertEquals((Math.tan(Math.toRadians(logical.fovDegrees / 2.0)) / Math.tan(Math.toRadians(50.0))).toFloat(), roomy.zoom(), 1e-5f)
        assertEquals(100f, Math.toDegrees(2 * Math.atan(Math.tan(Math.toRadians(roomy.fovDegrees / 2.0)) / roomy.zoom())).toFloat(), 1e-3f)
    }

    @Test fun intrinsicsFollowTheBufferZoomAndTurns() {
        val k = wide.intrinsics(960, 720, 1f, 0)
        assertEquals(720f, k.fx, 1e-3f)
        assertEquals(480f, k.cx, 0f)
        assertEquals(360f, k.cy, 0f)
        val turned = wide.intrinsics(960, 720, 1f, 1)
        assertEquals(720, turned.width)
        assertEquals(960, turned.height)
        assertEquals(360f, turned.cx, 0f)
        assertEquals(360f, wide.intrinsics(960, 720, 0.5f, 0).fx, 1e-3f)
    }

    @Test fun framesAreTurnedUprightFromGravity() {
        val portrait = Vec3(0f, 9.8f, 0f)
        assertEquals(1, CameraGeometry.uprightTurns(false, 90, portrait))
        assertEquals(3, CameraGeometry.uprightTurns(true, 270, portrait))
        assertEquals(0, CameraGeometry.uprightTurns(false, 0, portrait))
        val landscape = Vec3(9.8f, 0f, 0f)
        assertEquals(0, CameraGeometry.uprightTurns(false, 90, landscape))
        assertEquals(0, CameraGeometry.uprightTurns(true, 270, landscape))
        assertEquals(2, CameraGeometry.uprightTurns(false, 90, Vec3(-9.8f, 0f, 0f)))
        for ((front, orientation) in listOf(false to 90, true to 270, false to 270, true to 90)) {
            for (up in listOf(portrait, landscape, Vec3(0f, -9.8f, 0f), Vec3(-9.8f, 0f, 0f))) {
                val turns = CameraGeometry.uprightTurns(front, orientation, up)
                val seen = CameraGeometry.upInCamera(front, orientation, turns, up)
                assertEquals("front=$front $orientation° up=$up", -1f, seen.y, 1e-5f)
            }
        }
    }

    @Test fun theCameraLooksOutOfItsOwnSide() {
        val back = CameraGeometry.upInCamera(false, 90, 1, Vec3(0f, 0f, -9.8f))
        assertEquals(1f, back.z, 1e-5f)
        val front = CameraGeometry.upInCamera(true, 270, 3, Vec3(0f, 0f, 9.8f))
        assertEquals(1f, front.z, 1e-5f)
    }

    @Test fun placementTellsFlatFromStandingAndTilted() {
        assertEquals(Placement.FACE_UP, CameraGeometry.placement(false, Vec3(0f, 0.5f, 9.7f)))
        assertEquals(Placement.FACE_DOWN, CameraGeometry.placement(true, Vec3(0f, 0.5f, -9.7f)))
        assertEquals(Placement.STANDING, CameraGeometry.placement(false, Vec3(0f, 9.8f, 0f)))
        assertEquals(Placement.STANDING, CameraGeometry.placement(true, Vec3(9.2f, 0f, 3f)))
        val leanBack = Vec3(0f, Math.cos(Math.toRadians(60.0)).toFloat(), -Math.sin(Math.toRadians(60.0)).toFloat())
        assertEquals(Placement.POINTS_UP, CameraGeometry.placement(false, leanBack))
        assertEquals(Placement.POINTS_DOWN, CameraGeometry.placement(true, leanBack))
        assertEquals(Placement.UNKNOWN, CameraGeometry.placement(false, Vec3(0f, 0f, 0f)))
    }
}
