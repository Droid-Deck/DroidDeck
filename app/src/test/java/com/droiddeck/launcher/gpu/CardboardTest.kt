package com.droiddeck.launcher.gpu

import org.json.JSONObject
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
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
        val flat = c.copy(source = Cardboard.SOURCE_FLAT)
        assertEquals(flat, Cardboard.decode(flat.encode()))
        val wild = Cardboard.decode(JSONObject().put("ipdMm", 500).put("screenWidthDm", -3).put("viewer", "nope").put("source", 7))
        assertEquals(80, wild.ipdMm)
        assertEquals(5, wild.screenWidthDm)
        assertEquals(Cardboard.Viewer.CARDBOARD_V2.id, wild.viewer)
        assertEquals(Cardboard.SOURCE_SCREEN, wild.source)
        assertEquals(Cardboard.OFF, Cardboard.decode(JSONObject()))
    }

    @Test fun lensInsetLinesTheEyesUpWithTheLenses() {
        assertEquals(0f, Cardboard.lensInset(128f, 64f), eps)
        assertEquals(5.5f / 75f, Cardboard.lensInset(150f, 64f), eps)
        assertEquals(true, Cardboard.lensInset(110f, 64f) < 0f)
        assertEquals(0f, Cardboard.lensInset(0f, 64f), eps)
        assertEquals(0f, Cardboard.lensInset(150f, 0f), eps)
    }

    @Test fun steamVrStatusFollowsTheAgentAnswer() {
        fun answer(vararg pairs: Pair<String, Boolean>) = JSONObject().apply { pairs.forEach { put(it.first, it.second) } }
        assertEquals(SteamVr.Status.UNAVAILABLE, SteamVr.parse(answer("ok" to false, "installed" to true, "driver" to true)))
        assertEquals(SteamVr.Status.UNAVAILABLE, SteamVr.parse(answer("ok" to true, "installed" to true, "driver" to false)))
        assertEquals(SteamVr.Status.NOT_INSTALLED, SteamVr.parse(answer("ok" to true, "driver" to true)))
        assertEquals(SteamVr.Status.INSTALLING, SteamVr.parse(answer("ok" to true, "installing" to true)))
        assertEquals(SteamVr.Status.READY, SteamVr.parse(answer("ok" to true, "installed" to true, "driver" to true)))
        assertEquals(SteamVr.Status.RUNNING,
            SteamVr.parse(answer("ok" to true, "installed" to true, "driver" to true, "running" to true)))
        assertEquals(SteamVr.Status.UNAVAILABLE,
            SteamVr.parse(answer("ok" to true, "installed" to true, "driver" to true).put("exitCode", 1)))
    }

    @Test fun steamVrActionsSucceedOnlyWhenTheHelperDid() {
        fun answer(ok: Boolean, exitCode: Int? = null, started: Boolean? = null) = JSONObject().apply {
            put("ok", ok)
            exitCode?.let { put("exitCode", it) }
            started?.let { put("started", it) }
        }
        assertTrue(SteamVr.succeeded("start", answer(true, exitCode = 0)))
        assertTrue(SteamVr.succeeded("stop", answer(true, exitCode = 0)))
        assertFalse(SteamVr.succeeded("start", answer(true, exitCode = 1)))
        assertFalse(SteamVr.succeeded("stop", answer(true, exitCode = 2)))
        assertFalse(SteamVr.succeeded("start", answer(true)))
        assertFalse(SteamVr.succeeded("start", answer(false, exitCode = 0)))
        assertTrue(SteamVr.succeeded("install", answer(true, started = true)))
        assertFalse(SteamVr.succeeded("install", answer(true)))
        assertFalse(SteamVr.succeeded("install", answer(false, started = true)))
    }

    @Test fun quaternionMatchesKnownRotations() {
        val m = matrix(quat(0f, 0f, 1f, 90.0))
        assertVec(floatArrayOf(0f, 1f, 0f), HeadPose.apply(m, floatArrayOf(1f, 0f, 0f)))
        val q = HeadPose.quaternionFromRotationVector(floatArrayOf(0f, 0f, sin(PI / 4).toFloat()))
        assertEquals(cos(PI / 4).toFloat(), q[3], eps)
    }

    @Test fun recenterMakesTheCurrentViewStraightAhead() {
        for (heading in listOf(0.0, 37.0, 140.0, -100.0)) {
            val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
            val yaw = matrix(quat(0f, 0f, 1f, heading))
            val screenToWorld = HeadPose.multiply(yaw, upright)
            assertVec(HeadPose.IDENTITY, HeadPose.relative(HeadPose.recenter(screenToWorld), screenToWorld))
        }
    }

    @Test fun recenterKeepsPitchButDropsHeading() {
        val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val pitched = HeadPose.multiply(upright, matrix(quat(1f, 0f, 0f, -30.0)))
        val turned = HeadPose.multiply(matrix(quat(0f, 0f, 1f, 50.0)), pitched)
        val pose = HeadPose.relative(HeadPose.recenter(turned), turned)
        val forward = HeadPose.apply(pose, floatArrayOf(0f, 0f, -1f))
        assertEquals(0f, forward[0], eps)
        assertEquals(-sin(PI / 6).toFloat(), forward[1], eps)
        assertEquals(-cos(PI / 6).toFloat(), forward[2], eps)
    }

    @Test fun virtualScreenRecenterUsesTheCurrentHeightAndDirection() {
        for (pitch in listOf(-30.0, -10.0, 15.0, 35.0)) {
            val yaw = matrix(quat(0f, 0f, 1f, 50.0))
            val tilt = HeadPose.multiply(matrix(quat(1f, 0f, 0f, pitch)), matrix(quat(0f, 0f, 1f, 12.0)))
            val screen = HeadPose.multiply(HeadPose.multiply(yaw, HeadPose.fromGlasses(0f, 0f, 0f, 1f)), tilt)
            val ref = HeadPose.recenter(screen, centerView = true)
            assertVec(HeadPose.IDENTITY, HeadPose.relative(ref, screen))
            val lookUp = matrix(quat(1f, 0f, 0f, 20.0))
            assertVec(lookUp, HeadPose.relative(ref, HeadPose.multiply(screen, lookUp)))
        }
    }

    @Test fun turningTheHeadTurnsTheView() {
        val upright = floatArrayOf(1f, 0f, 0f, 0f, 0f, -1f, 0f, 1f, 0f)
        val ref = HeadPose.recenter(upright)
        val turned = HeadPose.multiply(matrix(quat(0f, 0f, 1f, 90.0)), upright)
        val forward = HeadPose.apply(HeadPose.relative(ref, turned), floatArrayOf(0f, 0f, -1f))
        assertVec(floatArrayOf(-1f, 0f, 0f), forward)
    }

    @Test fun heldFlatTheViewStartsStraightAhead() {
        for (m in listOf(HeadPose.IDENTITY, HeadPose.multiply(matrix(quat(0f, 0f, 1f, 30.0)), matrix(quat(1f, 0f, 0f, 20.0))))) {
            assertVec(HeadPose.IDENTITY, HeadPose.relative(HeadPose.recenter(m), m))
        }
    }

    @Test fun heldFlatTurningAtTheHipsPans() {
        val flat = HeadPose.IDENTITY
        val ref = HeadPose.recenter(flat)
        val turned = HeadPose.multiply(matrix(quat(0f, 0f, 1f, 40.0)), flat)
        val pose = HeadPose.relative(ref, turned)
        val forward = HeadPose.apply(pose, floatArrayOf(0f, 0f, -1f))
        val up = HeadPose.apply(pose, floatArrayOf(0f, 1f, 0f))
        assertVec(floatArrayOf(-sin(PI * 40 / 180).toFloat(), 0f, -cos(PI * 40 / 180).toFloat()), forward)
        assertVec(floatArrayOf(0f, 1f, 0f), up)
        val tipped = HeadPose.multiply(flat, matrix(quat(1f, 0f, 0f, -20.0)))
        val down = HeadPose.apply(HeadPose.relative(ref, tipped), floatArrayOf(0f, 0f, -1f))
        assertEquals(-sin(PI / 9).toFloat(), down[1], eps)
    }

    @Test fun fieldOfViewComesFromThePanelSeenThroughTheLens() {
        val v2 = Cardboard.Viewer.CARDBOARD_V2
        assertEquals(87.07f, Cardboard.fovDeg(v2, 74.7f), 0.05f)
        assertEquals(v2.fallbackFovDeg, Cardboard.fovDeg(v2, 0f), eps)
        assertEquals(Cardboard.Viewer.NONE.fallbackFovDeg, Cardboard.fovDeg(Cardboard.Viewer.NONE, 74.7f), eps)
    }

    @Test fun displayRotationRemapsTheScreenAxes() {
        val r = matrix(quat(0.3f, -0.5f, 0.2f, 70.0).let { q ->
            val n = sqrt(q[0] * q[0] + q[1] * q[1] + q[2] * q[2] + q[3] * q[3]); floatArrayOf(q[0] / n, q[1] / n, q[2] / n, q[3] / n)
        })
        assertVec(r, HeadPose.toScreen(r, 0))
        val s90 = HeadPose.toScreen(r, 1)
        assertVec(HeadPose.apply(r, floatArrayOf(0f, -1f, 0f)), HeadPose.apply(s90, floatArrayOf(1f, 0f, 0f)))
        assertVec(HeadPose.apply(r, floatArrayOf(1f, 0f, 0f)), HeadPose.apply(s90, floatArrayOf(0f, 1f, 0f)))
        val s270 = HeadPose.toScreen(r, 3)
        assertVec(HeadPose.apply(r, floatArrayOf(0f, 1f, 0f)), HeadPose.apply(s270, floatArrayOf(1f, 0f, 0f)))
        assertVec(r, HeadPose.toScreen(HeadPose.toScreen(HeadPose.toScreen(HeadPose.toScreen(r, 1), 1), 1), 1))
    }

    @Test fun glassesLevelAndAheadIsStraightAhead() {
        val s2w = HeadPose.fromGlasses(0f, 0f, 0f, 1f)
        assertVec(floatArrayOf(1f, 0f, 0f), HeadPose.apply(s2w, floatArrayOf(0f, 0f, -1f)))
        assertVec(floatArrayOf(0f, 0f, 1f), HeadPose.apply(s2w, floatArrayOf(0f, 1f, 0f)))
        assertVec(HeadPose.IDENTITY, HeadPose.relative(HeadPose.recenter(s2w), s2w))
    }

    @Test fun glassesTurnedLeftLookLeft() {
        val ref = HeadPose.recenter(HeadPose.fromGlasses(0f, 0f, 0f, 1f))
        val q = quat(0f, 0f, 1f, 30.0)
        val view = HeadPose.relative(ref, HeadPose.fromGlasses(q[0], q[1], q[2], q[3]))
        val s = sin(PI / 6).toFloat(); val c = cos(PI / 6).toFloat()
        assertVec(floatArrayOf(-s, 0f, -c), HeadPose.apply(view, floatArrayOf(0f, 0f, -1f)))
        assertVec(floatArrayOf(0f, 1f, 0f), HeadPose.apply(view, floatArrayOf(0f, 1f, 0f)))
    }

    @Test fun glassesRecenterKeepsTheHorizon() {
        val q = quat(0f, 0f, 1f, 75.0)
        val ref = HeadPose.recenter(HeadPose.fromGlasses(q[0], q[1], q[2], q[3]))
        assertVec(HeadPose.IDENTITY, HeadPose.relative(ref, HeadPose.fromGlasses(q[0], q[1], q[2], q[3])))
        val tilt = quat(0f, 1f, 0f, 20.0)
        val both = HeadPose.multiply(HeadPose.fromQuaternion(q[0], q[1], q[2], q[3]), HeadPose.fromQuaternion(tilt[0], tilt[1], tilt[2], tilt[3]))
        val view = HeadPose.multiply(HeadPose.transpose(ref.basis), HeadPose.multiply(both, floatArrayOf(0f, 0f, -1f, -1f, 0f, 0f, 0f, 1f, 0f)))
        val ahead = HeadPose.apply(view, floatArrayOf(0f, 0f, -1f))
        assertEquals(0f, ahead[0], eps)
        assertEquals(-sin(PI / 9).toFloat(), ahead[1], eps)
    }

    @Test fun monoIsNotPartOfTheSavedSettings() {
        val c = Cardboard(enabled = true, mono = true)
        assertEquals(false, Cardboard.decode(c.encode()).mono)
    }
}
