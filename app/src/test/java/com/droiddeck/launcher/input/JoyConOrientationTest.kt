package com.droiddeck.launcher.input

import com.droiddeck.launcher.glasses.Vec3
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin
import org.junit.Assert.*
import org.junit.Test

class JoyConOrientationTest {
    private val faceUp = floatArrayOf(0f, 0f, G)
    private val still = floatArrayOf(0f, 0f, 0f)

    @Test fun aJoyConLyingFaceUpPointsStraightAhead() {
        val filter = JoyConOrientation()
        filter.update(faceUp, still, 0f)
        assertTrue(filter.ready)
        assertVec(Vec3(0f, 0f, -1f), forward(filter))
        assertVec(Vec3(0f, 1f, 0f), filter.rotation.rotate(Vec3(0f, 1f, 0f)))
    }

    @Test fun standingOnItsBaseAimsUp() {
        val filter = JoyConOrientation()
        filter.update(floatArrayOf(G, 0f, 0f), still, 0f)
        assertVec(Vec3(0f, 1f, 0f), forward(filter))
    }

    @Test fun nothingIsReportedUntilGravityIsPlausible() {
        val filter = JoyConOrientation()
        filter.update(floatArrayOf(0f, 0f, 3f), still, 0f)
        assertFalse(filter.ready)
        filter.update(floatArrayOf(0f, 0f, 30f), still, 0f)
        assertFalse(filter.ready)
        filter.update(faceUp, still, 0f)
        assertTrue(filter.ready)
    }

    @Test fun turningAboutTheFaceTurnsTheAimLeft() {
        val filter = JoyConOrientation()
        filter.update(faceUp, still, 0f)
        repeat(100) { filter.update(faceUp, floatArrayOf(0f, 0f, 1f), 0.01f) }
        assertVec(Vec3(-sin(1f), 0f, -cos(1f)), forward(filter), 2e-3f)
        assertVec(Vec3(0f, 1f, 0f), filter.rate)
    }

    @Test fun gravityPullsATiltedEstimateBackLevel() {
        val filter = JoyConOrientation()
        filter.update(faceUp, still, 0f)
        repeat(50) { filter.update(faceUp, floatArrayOf(1f, 0f, 0f), 0.01f) }
        assertTrue(filter.rotation.rotate(Vec3(0f, 1f, 0f)).y < 0.97f)
        repeat(600) { filter.update(faceUp, still, 0.01f) }
        assertEquals(1f, filter.rotation.rotate(Vec3(0f, 1f, 0f)).y, 1e-3f)
    }

    @Test fun aSteadyGyroOffsetIsLearnedWhileTheJoyConRests() {
        val filter = JoyConOrientation()
        filter.update(faceUp, still, 0f)
        val drifting = floatArrayOf(0f, 0f, 0.02f)
        repeat(1000) { filter.update(faceUp, drifting, 0.01f) }
        assertEquals(0f, filter.rate.length(), 1e-3f)
        val heading = heading(filter)
        repeat(1000) { filter.update(faceUp, drifting, 0.01f) }
        assertEquals(heading, heading(filter), 2e-3f)
    }

    @Test fun movingTheJoyConDoesNotTeachTheBias() {
        val filter = JoyConOrientation()
        filter.update(faceUp, still, 0f)
        repeat(300) { filter.update(faceUp, floatArrayOf(0f, 0f, 0.5f), 0.01f) }
        assertEquals(0.5f, filter.rate.length(), 1e-4f)
    }

    @Test fun reportsArriveNewestFirstAndTheRepeatedSampleIsDropped() {
        val reports = JoyConReports()
        fun sample(n: Int) = floatArrayOf(n.toFloat(), 0f, G) to floatArrayOf(n * 0.1f, 0f, 0f)
        fun send(at: Long, vararg newestFirst: Int): List<JoyConReports.Sample> {
            for (n in newestFirst) sample(n).let { reports.add(it.first, it.second, at) }
            return reports.flush()
        }
        assertEquals(listOf(1f, 2f, 3f), send(1_000_000_000L, 3, 2, 1).map { it.accel[0] })
        val second = send(1_010_000_000L, 5, 4, 3)
        assertEquals(listOf(4f, 5f), second.map { it.accel[0] })
        assertEquals("the report interval is shared by its new samples", 0.005f, second[0].dt, 1e-6f)
        assertEquals(listOf(6f, 7f, 8f), send(1_025_000_000L, 8, 7, 6).map { it.accel[0] })
        val still = send(1_035_000_000L, 8, 8, 8)
        assertEquals("a still report still moves time on", 1, still.size)
        assertEquals(0.01f, still[0].dt, 1e-6f)
        assertEquals("a long gap is capped", 0.05f, send(2_000_000_000L, 11, 10, 9)[0].dt, 1e-6f)
    }

    @Test fun reportsInOldestFirstOrderAreRecognised() {
        val reports = JoyConReports()
        var at = 1_000_000_000L
        var next = 1
        val seen = ArrayList<Float>()
        repeat(12) {
            val samples = listOf(next - 1, next, next + 1).filter { it > 0 }
            for (n in samples) reports.add(floatArrayOf(n.toFloat(), 0f, G), floatArrayOf(0f, 0f, 0f), at)
            seen += reports.flush().map { it.accel[0] }
            next += 2
            at += 10_000_000L
        }
        assertEquals((10..24).map { it.toFloat() }, seen.takeLast(15))
    }

    private fun forward(filter: JoyConOrientation) = filter.rotation.rotate(Vec3(0f, 0f, -1f))

    private fun heading(filter: JoyConOrientation): Float = forward(filter).let { atan2(-it.x, -it.z) }

    private fun assertVec(want: Vec3, got: Vec3, tolerance: Float = 1e-4f) {
        assertEquals("x of $got", want.x, got.x, tolerance)
        assertEquals("y of $got", want.y, got.y, tolerance)
        assertEquals("z of $got", want.z, got.z, tolerance)
    }

    private companion object { const val G = 9.80665f }
}
