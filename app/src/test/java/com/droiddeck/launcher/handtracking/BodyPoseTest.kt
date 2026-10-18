package com.droiddeck.launcher.handtracking

import com.droiddeck.launcher.glasses.Vec3
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BodyPoseTest {
    private val k = Intrinsics(800f, 800f, 480f, 360f, 960, 720)
    private val body = mapOf(
        BodyPose.NOSE to Vec3(0f, 0.62f, 0.10f),
        BodyPose.LEFT_EYE to Vec3(0.035f, 0.66f, 0.08f), BodyPose.RIGHT_EYE to Vec3(-0.035f, 0.66f, 0.08f),
        BodyPose.LEFT_EAR to Vec3(0.075f, 0.64f, 0f), BodyPose.RIGHT_EAR to Vec3(-0.075f, 0.64f, 0f),
        BodyPose.LEFT_SHOULDER to Vec3(0.19f, 0.48f, 0f), BodyPose.RIGHT_SHOULDER to Vec3(-0.19f, 0.48f, 0f),
        BodyPose.LEFT_ELBOW to Vec3(0.3f, 0.3f, 0.1f), BodyPose.RIGHT_ELBOW to Vec3(-0.28f, 0.25f, 0.05f),
        BodyPose.LEFT_WRIST to Vec3(0.35f, 0.45f, 0.35f), BodyPose.RIGHT_WRIST to Vec3(-0.25f, 0.6f, 0.3f),
        BodyPose.LEFT_PINKY to Vec3(0.37f, 0.47f, 0.42f), BodyPose.RIGHT_PINKY to Vec3(-0.27f, 0.63f, 0.37f),
        BodyPose.LEFT_INDEX to Vec3(0.34f, 0.5f, 0.43f), BodyPose.RIGHT_INDEX to Vec3(-0.24f, 0.66f, 0.37f),
        BodyPose.LEFT_HIP to Vec3(0.1f, 0f, 0f), BodyPose.RIGHT_HIP to Vec3(-0.1f, 0f, 0f),
    )
    private val hips = Vec3(0.1f, -0.3f, -2.2f)

    @Test fun handsComeOutRelativeToTheEyesInALevelFrame() {
        val frame = solve(turn = 0.0, pitch = 0.0)!!
        assertTrue(frame.seen)
        assertEquals(2.2f, frame.distance, 1e-3f)
        for (side in 0..1) assertClose(expectedHand(side, 0.0), frame.hands[side]!!)
        assertEquals(PI, abs(frame.faceYaw!!.toDouble()), 1e-4)
    }

    @Test fun aCameraPointingDownStillGivesLevelHands() {
        for (pitch in listOf(-0.25, 0.1, 0.15)) {
            val frame = solve(turn = 0.0, pitch = pitch)!!
            for (side in 0..1) assertClose(expectedHand(side, 0.0), frame.hands[side]!!)
        }
    }

    @Test fun aTurnedPersonGivesTheirFaceHeading() {
        for (turn in listOf(-0.6, 0.4)) {
            val frame = solve(turn = turn, pitch = 0.1)!!
            assertEquals(wrap(PI + turn), wrap(frame.faceYaw!!.toDouble()), 1e-3)
            for (side in 0..1) assertClose(expectedHand(side, turn), frame.hands[side]!!)
        }
    }

    @Test fun shoulderSpanIsAShareOfTheImageWidth() {
        val frame = solve(turn = 0.0, pitch = 0.0)!!
        assertEquals(800f * 0.38f / 2.2f / 960f, frame.shoulderSpan, 1e-3f)
    }

    @Test fun anUnseenWristLeavesThatHandOut() {
        val frame = solve(turn = 0.0, pitch = 0.0, hidden = setOf(BodyPose.RIGHT_WRIST))!!
        assertNotNull(frame.hands[0])
        assertNull(frame.hands[1])
    }

    @Test fun tooLittleOfThePersonOrAnUnlevelableCameraGivesNothing() {
        assertNull(solve(turn = 0.0, pitch = 0.0, hidden = body.keys - setOf(BodyPose.NOSE, BodyPose.LEFT_EYE, BodyPose.RIGHT_EYE)))
        assertNull(solve(turn = 0.0, pitch = PI / 2))
    }

    @Test fun aHandModelFixIsPlacedByTheHandsOwnSize() {
        val palmAt = Vec3(0.15f, 0.1f, 0.6f)
        val shape = List(BodyPose.HAND_POINTS) { i -> WorldPoint(((i * 37) % 11 - 5) * 0.01f, ((i * 53) % 13 - 6) * 0.01f, ((i * 29) % 7 - 3) * 0.01f) }
        val points = FloatArray(BodyPose.HAND_POINTS * 2) { j ->
            val w = shape[j / 2]
            val x = palmAt.x + w.x
            val y = palmAt.y + w.y
            val z = palmAt.z + w.z
            if (j % 2 == 0) k.fx * x / z + k.cx else k.fy * y / z + k.cy
        }
        val fix = BodyPose.handFix(points, shape, k)!!
        val palm = intArrayOf(0, 5, 9, 13, 17)
        val expectedPalm = Vec3(palmAt.x + palm.map { shape[it].x }.average().toFloat(), palmAt.y + palm.map { shape[it].y }.average().toFloat(),
            palmAt.z + palm.map { shape[it].z }.average().toFloat())
        assertClose(expectedPalm, fix.palm)
        val frame = solve(turn = 0.0, pitch = 0.0, fixes = arrayOf(fix, null))!!
        val eyes = toCamera(hips + (body[BodyPose.LEFT_EYE]!! + body[BodyPose.RIGHT_EYE]!!) * 0.5f)
        val d = fix.palm - eyes
        assertClose(Vec3(d.x, -d.y, -d.z), frame.hands[0]!!)
        assertNull("no fix and no held depth leaves the hand out", frame.hands[1])
        assertTrue(!frame.estimated[0])
        val joints = frame.joints[0]!!
        val mean = Vec3(palm.map { shape[it].x }.average().toFloat(), palm.map { shape[it].y }.average().toFloat(),
            palm.map { shape[it].z }.average().toFloat())
        for (i in 0 until BodyPose.HAND_POINTS) {
            assertClose(Vec3(shape[i].x - mean.x, -(shape[i].y - mean.y), -(shape[i].z - mean.z)),
                Vec3(joints[i * 3], joints[i * 3 + 1], joints[i * 3 + 2]))
        }
        assertNull(frame.joints[1])
    }

    @Test fun aHandTheModelMissedKeepsItsLastDepth() {
        val frame = solve(turn = 0.0, pitch = 0.0, fixes = arrayOfNulls(2), held = floatArrayOf(Float.NaN, 1.8f))!!
        assertNull(frame.hands[0])
        assertTrue(frame.estimated[1])
        assertNull("a guessed hand has no finger joints", frame.joints[1])
        val eyes = toCamera(hips + (body[BodyPose.LEFT_EYE]!! + body[BodyPose.RIGHT_EYE]!!) * 0.5f)
        assertEquals(-(1.8f - eyes.z), frame.hands[1]!!.z, 1e-3f)
    }

    @Test fun jointsNearTheEdgeOfThePictureShowAsLittleRoom() {
        val frame = solve(turn = 0.0, pitch = 0.0)!!
        assertTrue(frame.edgeRoom > 0.05f && frame.edgeRoom < 0.5f)
        val near = solve(turn = 0.0, pitch = 0.0, shift = 0.75f)!!
        assertTrue("${near.edgeRoom}", near.edgeRoom < frame.edgeRoom)
    }

    private fun solve(turn: Double, pitch: Double, hidden: Set<Int> = emptySet(), fixes: Array<HandFix?>? = null,
                      held: FloatArray? = null, shift: Float = 0f): BodyFrame? {
        val image = ArrayList<Landmark>()
        val world = ArrayList<WorldPoint>()
        for (i in 0 until 33) {
            val rel = body[i]?.let { rotateY(it, turn) }
            if (rel == null) {
                image += Landmark(0.5f, 0.5f, 0f)
                world += WorldPoint(0f, 0f, 0f)
                continue
            }
            val camera = tilt(toCamera(hips + rel), pitch)
            image += Landmark((k.fx * (camera.x + shift) / camera.z + k.cx) / k.width, (k.fy * camera.y / camera.z + k.cy) / k.height,
                if (i in hidden) 0.1f else 0.95f)
            val w = tilt(toCamera(rel), pitch)
            world += WorldPoint(w.x, w.y, w.z)
        }
        val up = tilt(toCamera(Vec3(0f, 1f, 0f)), pitch)
        return BodyPose.solve(image, world, k, CameraLevel(up), fixes, held)
    }

    private fun expectedHand(side: Int, turn: Double): Vec3 {
        val (wrist, pinky, index) = if (side == 0) Triple(BodyPose.LEFT_WRIST, BodyPose.LEFT_PINKY, BodyPose.LEFT_INDEX)
        else Triple(BodyPose.RIGHT_WRIST, BodyPose.RIGHT_PINKY, BodyPose.RIGHT_INDEX)
        val grip = (rotateY(body[wrist]!!, turn) + rotateY(body[pinky]!!, turn) + rotateY(body[index]!!, turn)) * (1f / 3f)
        val head = (rotateY(body[BodyPose.LEFT_EYE]!!, turn) + rotateY(body[BodyPose.RIGHT_EYE]!!, turn)) * 0.5f
        return grip - head
    }

    private fun toCamera(v: Vec3) = Vec3(v.x, -v.y, -v.z)

    private fun tilt(v: Vec3, pitch: Double): Vec3 {
        val c = cos(pitch).toFloat()
        val s = sin(pitch).toFloat()
        return Vec3(v.x, v.y * c - v.z * s, v.y * s + v.z * c)
    }

    private fun rotateY(v: Vec3, angle: Double): Vec3 {
        val c = cos(angle).toFloat()
        val s = sin(angle).toFloat()
        return Vec3(c * v.x + s * v.z, v.y, -s * v.x + c * v.z)
    }

    private fun wrap(a: Double): Double = Math.IEEEremainder(a, 2 * PI)

    private fun assertClose(want: Vec3, got: Vec3) {
        assertEquals("x", want.x, got.x, 2e-4f)
        assertEquals("y", want.y, got.y, 2e-4f)
        assertEquals("z", want.z, got.z, 2e-4f)
    }
}
