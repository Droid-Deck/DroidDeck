package com.droiddeck.launcher.gpu

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin
import kotlin.math.sqrt

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CardboardTest {
    private val eps = 1e-4f

    private fun assertVec(expected: FloatArray, actual: FloatArray) = assertArrayEquals(expected, actual, eps)

    /** Rotation about a unit axis by an angle, as a quaternion (x, y, z, w). */
    private fun quat(ax: Float, ay: Float, az: Float, deg: Double): FloatArray {
        val h = deg * PI / 360.0
        val s = sin(h).toFloat()
        return floatArrayOf(ax * s, ay * s, az * s, cos(h).toFloat())
    }

    private fun matrix(q: FloatArray) = HeadPose.fromQuaternion(q[0], q[1], q[2], q[3])

    @Test fun settingsRoundTripAndClamp() {
        val c = Cardboard(enabled = true, source = Cardboard.SOURCE_SBS, viewer = "none", ipdMm = 70,
            screenWidthDm = 55, screenDistanceDm = 25, headTracking = false)
        assertEquals(c, Cardboard.decode(c.encode()))
        val wild = Cardboard.decode(JSONObject().put("ipdMm", 500).put("screenWidthDm", -3).put("viewer", "nope").put("source", 7))
        assertEquals(80, wild.ipdMm)
        assertEquals(5, wild.screenWidthDm)
        assertEquals(Cardboard.Viewer.CARDBOARD_V2.id, wild.viewer)
        assertEquals(Cardboard.SOURCE_SCREEN, wild.source)
        assertEquals(Cardboard.OFF, Cardboard.decode(JSONObject()))
    }

    @Test fun lensInsetLinesTheEyesUpWithTheLenses() {
        // A 128 mm panel with 64 mm lenses: each viewport centre is 32 mm out, exactly under its lens.
        assertEquals(0f, Cardboard.lensInset(128f, 64f), eps)
        // A wider 150 mm panel: viewport centres 37.5 mm out, lenses 32 mm: 5.5 mm of a 75 mm viewport inwards.
        assertEquals(5.5f / 75f, Cardboard.lensInset(150f, 64f), eps)
        // A narrow panel puts the lens centres outside the viewport centres (negative inset).
        assertEquals(true, Cardboard.lensInset(110f, 64f) < 0f)
        assertEquals(0f, Cardboard.lensInset(0f, 64f), eps)
        assertEquals(0f, Cardboard.lensInset(150f, 0f), eps)
    }

    @Test fun quaternionMatchesKnownRotations() {
        // 90 degrees about z: x -> y.
        val m = matrix(quat(0f, 0f, 1f, 90.0))
        assertVec(floatArrayOf(0f, 1f, 0f), HeadPose.apply(m, floatArrayOf(1f, 0f, 0f)))
        // Rotation vector without w: computed from the other three.
        val q = HeadPose.quaternionFromRotationVector(floatArrayOf(0f, 0f, sin(PI / 4).toFloat()))
        assertEquals(cos(PI / 4).toFloat(), q[3], eps)
    }

    @Test fun recenterMakesTheCurrentViewStraightAhead() {
        // Phone upright in landscape, screen facing the viewer, who faces north-east-ish.
        // Upright device: screen up = world up; looking direction = the screen's -z.
        for (heading in listOf(0.0, 37.0, 140.0, -100.0)) {
            // Start from "screen faces south, viewer looks north": device x = east, y = up, z = south.
            val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
            val yaw = matrix(quat(0f, 0f, 1f, heading))
            val screenToWorld = HeadPose.multiply(yaw, upright)
            val basis = HeadPose.recenterBasis(screenToWorld)
            assertVec(HeadPose.IDENTITY, HeadPose.relative(basis, screenToWorld))
        }
    }

    @Test fun recenterKeepsPitchButDropsHeading() {
        val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        // Looking 30 degrees down, then turning 50 degrees: after a recentre the view is still 30 degrees down.
        val pitched = HeadPose.multiply(upright, matrix(quat(1f, 0f, 0f, -30.0)))
        val turned = HeadPose.multiply(matrix(quat(0f, 0f, 1f, 50.0)), pitched)
        val pose = HeadPose.relative(HeadPose.recenterBasis(turned), turned)
        val forward = HeadPose.apply(pose, floatArrayOf(0f, 0f, -1f))
        assertEquals(0f, forward[0], eps)
        assertEquals(-sin(PI / 6).toFloat(), forward[1], eps)
        assertEquals(-cos(PI / 6).toFloat(), forward[2], eps)
    }

    @Test fun turningTheHeadTurnsTheView() {
        val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val basis = HeadPose.recenterBasis(upright)
        // Turn the head 90 degrees to the left (counter-clockwise seen from above).
        val turned = HeadPose.multiply(matrix(quat(0f, 0f, 1f, 90.0)), upright)
        val forward = HeadPose.apply(HeadPose.relative(basis, turned), floatArrayOf(0f, 0f, -1f))
        // The view now points to the left of the recentred forward direction: -x.
        assertVec(floatArrayOf(-1f, 0f, 0f), forward)
    }

    @Test fun lookingStraightDownStillRecentres() {
        // Screen facing up (phone flat, viewer looking down): forward has no horizontal part.
        val flat = HeadPose.IDENTITY
        val basis = HeadPose.recenterBasis(flat)
        val pose = HeadPose.relative(basis, flat)
        // Still a proper rotation (orthonormal, determinant 1).
        val t = HeadPose.multiply(HeadPose.transpose(pose), pose)
        assertVec(HeadPose.IDENTITY, t)
        val det = pose[0] * (pose[4] * pose[8] - pose[5] * pose[7]) -
            pose[1] * (pose[3] * pose[8] - pose[5] * pose[6]) + pose[2] * (pose[3] * pose[7] - pose[4] * pose[6])
        assertEquals(1f, det, eps)
        // And the view points straight down.
        assertVec(floatArrayOf(0f, -1f, 0f), HeadPose.apply(pose, floatArrayOf(0f, 0f, -1f)))
    }

    @Test fun displayRotationRemapsTheScreenAxes() {
        val r = matrix(quat(0.3f, -0.5f, 0.2f, 70.0).let { q ->
            val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]); floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
        })
        assertVec(r, HeadPose.toScreen(r, 0))
        // ROTATION_90: screen x is the device's y, screen y the device's -x (SensorManager.remapCoordinateSystem(AXIS_Y, AXIS_MINUS_X)).
        val s90 = HeadPose.toScreen(r, 1)
        assertVec(HeadPose.apply(r, floatArrayOf(0f, 1f, 0f)), HeadPose.apply(s90, floatArrayOf(1f, 0f, 0f)))
        assertVec(HeadPose.apply(r, floatArrayOf(-1f, 0f, 0f)), HeadPose.apply(s90, floatArrayOf(0f, 1f, 0f)))
        // ROTATION_270 is the opposite turn; four quarter turns of 90 give the original back.
        val s270 = HeadPose.toScreen(r, 3)
        assertVec(HeadPose.apply(r, floatArrayOf(0f, -1f, 0f)), HeadPose.apply(s270, floatArrayOf(1f, 0f, 0f)))
        assertVec(r, HeadPose.toScreen(HeadPose.toScreen(HeadPose.toScreen(HeadPose.toScreen(r, 1), 1), 1), 1))
    }
}
