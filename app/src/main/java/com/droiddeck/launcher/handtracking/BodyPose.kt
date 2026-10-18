package com.droiddeck.launcher.handtracking

import com.droiddeck.launcher.glasses.Vec3
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

internal class Landmark(val x: Float, val y: Float, val visibility: Float)

internal class WorldPoint(val x: Float, val y: Float, val z: Float)

internal class Intrinsics(val fx: Float, val fy: Float, val cx: Float, val cy: Float, val width: Int, val height: Int)

internal class CameraLevel(up: Vec3) {
    val right: Vec3
    val up: Vec3
    val back: Vec3
    val valid: Boolean

    init {
        val u = up.normalized()
        val forward = Vec3(0f, 0f, 1f)
        val along = u.x * forward.x + u.y * forward.y + u.z * forward.z
        val horizontal = Vec3(forward.x - u.x * along, forward.y - u.y * along, forward.z - u.z * along)
        valid = u.length() > 0.5f && horizontal.length() > MIN_HORIZONTAL
        this.up = u
        back = (horizontal * -1f).normalized()
        right = this.up.cross(back)
    }

    fun level(v: Vec3) = Vec3(dot(v, right), dot(v, up), dot(v, back))

    private fun dot(a: Vec3, b: Vec3) = a.x * b.x + a.y * b.y + a.z * b.z

    companion object {
        private const val MIN_HORIZONTAL = 0.25f
    }
}

internal class BodyFrame(
    val hands: Array<Vec3?>,
    val faceYaw: Float?,
    val distance: Float,
    val shoulderSpan: Float,
    val seen: Boolean,
    val estimated: BooleanArray = BooleanArray(2),
    val edgeRoom: Float = 1f,
    val joints: Array<FloatArray?> = arrayOfNulls(2),
)

internal class HandFix(val palm: Vec3, val x: Float, val y: Float, val extent: Float, val points: FloatArray, val world: List<WorldPoint> = emptyList())

internal object Pnp {
    fun translation(count: Int, k: Intrinsics, x: (Int) -> Float, y: (Int) -> Float, world: (Int) -> WorldPoint): Vec3? {
        val ata = Array(3) { DoubleArray(3) }
        val atb = DoubleArray(3)
        for (i in 0 until count) {
            val a = ((x(i) - k.cx) / k.fx).toDouble()
            val b = ((y(i) - k.cy) / k.fy).toDouble()
            val w = world(i)
            add(ata, atb, doubleArrayOf(1.0, 0.0, -a), a * w.z - w.x)
            add(ata, atb, doubleArrayOf(0.0, 1.0, -b), b * w.z - w.y)
        }
        val t = solve3(ata, atb) ?: return null
        return Vec3(t[0].toFloat(), t[1].toFloat(), t[2].toFloat())
    }

    private fun add(ata: Array<DoubleArray>, atb: DoubleArray, row: DoubleArray, rhs: Double) {
        for (i in 0..2) {
            atb[i] += row[i] * rhs
            for (j in 0..2) ata[i][j] += row[i] * row[j]
        }
    }

    private fun solve3(a: Array<DoubleArray>, b: DoubleArray): DoubleArray? {
        val det = det3(a)
        if (abs(det) < 1e-12) return null
        return DoubleArray(3) { col -> det3(Array(3) { r -> DoubleArray(3) { c -> if (c == col) b[r] else a[r][c] } }) / det }
    }

    private fun det3(m: Array<DoubleArray>) = m[0][0] * (m[1][1] * m[2][2] - m[1][2] * m[2][1]) -
        m[0][1] * (m[1][0] * m[2][2] - m[1][2] * m[2][0]) + m[0][2] * (m[1][0] * m[2][1] - m[1][1] * m[2][0])
}

internal object BodyPose {
    const val NOSE = 0
    const val LEFT_EYE = 2
    const val RIGHT_EYE = 5
    const val LEFT_EAR = 7
    const val RIGHT_EAR = 8
    const val RIGHT_MOUTH = 10
    const val LEFT_SHOULDER = 11
    const val RIGHT_SHOULDER = 12
    const val LEFT_ELBOW = 13
    const val RIGHT_ELBOW = 14
    const val LEFT_WRIST = 15
    const val RIGHT_WRIST = 16
    const val LEFT_PINKY = 17
    const val RIGHT_PINKY = 18
    const val LEFT_INDEX = 19
    const val RIGHT_INDEX = 20
    const val LEFT_HIP = 23
    const val RIGHT_HIP = 24

    private val ANCHORS = intArrayOf(NOSE, LEFT_EYE, RIGHT_EYE, LEFT_EAR, RIGHT_EAR, LEFT_SHOULDER, RIGHT_SHOULDER, LEFT_HIP, RIGHT_HIP)
    private val WRISTS = intArrayOf(LEFT_WRIST, RIGHT_WRIST)
    private val PINKIES = intArrayOf(LEFT_PINKY, RIGHT_PINKY)
    private val INDEXES = intArrayOf(LEFT_INDEX, RIGHT_INDEX)
    const val MIN_VISIBILITY = 0.6f

    fun solve(
        image: List<Landmark>,
        world: List<WorldPoint>,
        k: Intrinsics,
        level: CameraLevel,
        fixes: Array<HandFix?>? = null,
        held: FloatArray? = null,
    ): BodyFrame? {
        if (image.size <= RIGHT_HIP || world.size <= RIGHT_HIP || !level.valid) return null
        val t = translation(image, world, k) ?: return null
        fun at(i: Int): Vec3 {
            val w = world[i]
            val depth = w.z + t.z
            val px = image[i].x * k.width
            val py = image[i].y * k.height
            return Vec3((px - k.cx) / k.fx * depth, (py - k.cy) / k.fy * depth, depth)
        }
        fun visible(i: Int) = image[i].visibility >= MIN_VISIBILITY && image[i].x in 0f..1f && image[i].y in 0f..1f
        val eyesSeen = visible(LEFT_EYE) && visible(RIGHT_EYE)
        val shouldersSeen = visible(LEFT_SHOULDER) && visible(RIGHT_SHOULDER)
        val hands = arrayOfNulls<Vec3>(2)
        val estimated = BooleanArray(2)
        val joints = arrayOfNulls<FloatArray>(2)
        if (fixes == null) {
            val head = if (eyesSeen) (at(LEFT_EYE) + at(RIGHT_EYE)) * 0.5f else null
            if (head != null) for (side in 0..1) {
                if (!visible(WRISTS[side])) continue
                val grip = (at(WRISTS[side]) + at(PINKIES[side]) + at(INDEXES[side])) * (1f / 3f)
                hands[side] = level.level(grip - head)
            }
        } else {
            val head = if (eyesSeen) eyes(image, world, k) else null
            if (head != null) for (side in 0..1) {
                val fix = fixes.getOrNull(side)
                if (fix != null) {
                    hands[side] = level.level(fix.palm - head)
                    joints[side] = handJoints(fix.world, level)
                    continue
                }
                val depth = held?.getOrNull(side)?.takeIf { it.isFinite() && it > 0f } ?: continue
                if (!visible(WRISTS[side])) continue
                val gx = (image[WRISTS[side]].x + image[PINKIES[side]].x + image[INDEXES[side]].x) / 3f * k.width
                val gy = (image[WRISTS[side]].y + image[PINKIES[side]].y + image[INDEXES[side]].y) / 3f * k.height
                hands[side] = level.level(Vec3((gx - k.cx) / k.fx * depth, (gy - k.cy) / k.fy * depth, depth) - head)
                estimated[side] = true
            }
        }
        val faceYaw = if (visible(NOSE) && visible(LEFT_EAR) && visible(RIGHT_EAR)) {
            val n = world[NOSE]
            val l = world[LEFT_EAR]
            val r = world[RIGHT_EAR]
            val f = level.level(Vec3(n.x - (l.x + r.x) / 2, n.y - (l.y + r.y) / 2, n.z - (l.z + r.z) / 2))
            if (hypot(f.x, f.z) > 0.02f) atan2(-f.x, -f.z) else null
        } else null
        val span = if (shouldersSeen) hypot(image[LEFT_SHOULDER].x - image[RIGHT_SHOULDER].x,
            (image[LEFT_SHOULDER].y - image[RIGHT_SHOULDER].y) * k.height / k.width) else 0f
        var room = 1f
        for (i in EDGE_JOINTS) {
            if (image[i].visibility < MIN_VISIBILITY) continue
            room = minOf(room, image[i].x, 1f - image[i].x, image[i].y, 1f - image[i].y)
        }
        return BodyFrame(hands, faceYaw, t.z, span, eyesSeen && shouldersSeen, estimated, room, joints)
    }

    fun handJoints(world: List<WorldPoint>, level: CameraLevel): FloatArray? {
        if (world.size < HAND_POINTS) return null
        var mx = 0f
        var my = 0f
        var mz = 0f
        for (i in PALM) {
            mx += world[i].x
            my += world[i].y
            mz += world[i].z
        }
        val n = PALM.size.toFloat()
        val out = FloatArray(HAND_POINTS * 3)
        for (i in 0 until HAND_POINTS) {
            val v = level.level(Vec3(world[i].x - mx / n, world[i].y - my / n, world[i].z - mz / n))
            out[i * 3] = v.x
            out[i * 3 + 1] = v.y
            out[i * 3 + 2] = v.z
        }
        return out
    }

    fun eyes(image: List<Landmark>, world: List<WorldPoint>, k: Intrinsics): Vec3? {
        val used = HEAD.filter { image[it].visibility >= MIN_VISIBILITY }
        if (used.size < MIN_HEAD_POINTS) return null
        val t = Pnp.translation(used.size, k, { image[used[it]].x * k.width }, { image[used[it]].y * k.height }, { world[used[it]] })
            ?: return null
        if (!(t.z > MIN_DISTANCE && t.z < MAX_DISTANCE)) return null
        val l = world[LEFT_EYE]
        val r = world[RIGHT_EYE]
        return Vec3(t.x + (l.x + r.x) / 2, t.y + (l.y + r.y) / 2, t.z + (l.z + r.z) / 2)
    }

    fun handFix(points: FloatArray, world: List<WorldPoint>, k: Intrinsics): HandFix? {
        if (world.size < HAND_POINTS || points.size < HAND_POINTS * 2) return null
        val t = Pnp.translation(HAND_POINTS, k, { points[it * 2] }, { points[it * 2 + 1] }, { world[it] }) ?: return null
        if (!(t.z > MIN_HAND_DISTANCE && t.z < MAX_DISTANCE)) return null
        var px = 0f
        var py = 0f
        var wx = 0f
        var wy = 0f
        var wz = 0f
        for (i in PALM) {
            px += points[i * 2]
            py += points[i * 2 + 1]
            wx += world[i].x
            wy += world[i].y
            wz += world[i].z
        }
        val n = PALM.size.toFloat()
        var minX = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE
        var minY = Float.MAX_VALUE
        var maxY = -Float.MAX_VALUE
        for (i in 0 until HAND_POINTS) {
            minX = minOf(minX, points[i * 2])
            maxX = maxOf(maxX, points[i * 2])
            minY = minOf(minY, points[i * 2 + 1])
            maxY = maxOf(maxY, points[i * 2 + 1])
        }
        return HandFix(Vec3(t.x + wx / n, t.y + wy / n, t.z + wz / n), px / n, py / n, maxOf(maxX - minX, maxY - minY), points, world)
    }

    private fun translation(image: List<Landmark>, world: List<WorldPoint>, k: Intrinsics): Vec3? {
        val used = ANCHORS.filter { image[it].visibility >= MIN_VISIBILITY }
        if (used.size < MIN_ANCHORS) return null
        val t = Pnp.translation(used.size, k, { image[used[it]].x * k.width }, { image[used[it]].y * k.height }, { world[used[it]] })
            ?: return null
        if (!(t.z > MIN_DISTANCE && t.z < MAX_DISTANCE)) return null
        return t
    }

    const val HAND_POINTS = 21
    private val HEAD = (NOSE..RIGHT_MOUTH).toList()
    private val PALM = intArrayOf(0, 5, 9, 13, 17)
    private val EDGE_JOINTS = intArrayOf(LEFT_ELBOW, RIGHT_ELBOW, LEFT_WRIST, RIGHT_WRIST)
    private const val MIN_ANCHORS = 4
    private const val MIN_HEAD_POINTS = 4
    private const val MIN_DISTANCE = 0.2f
    private const val MIN_HAND_DISTANCE = 0.05f
    private const val MAX_DISTANCE = 6f
}
