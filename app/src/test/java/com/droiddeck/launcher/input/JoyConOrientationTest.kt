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

    @Test fun theSampleClockCorrectsTimestampsThatRunTwiceAsFast() {
        val clock = JoyConSampleClock()
        var stamp = 1_000_000_000L
        var arrival = 7_000_000_000L
        assertEquals(0f, clock.interval(stamp, arrival))
        val steps = (1..200).map {
            stamp += 10_000_000L
            arrival += 5_000_000L
            clock.interval(stamp, arrival)
        }
        assertEquals("no step before the rate is known", 0f, steps.first())
        assertEquals(0.005f, steps.last(), 1e-5f)
        assertEquals("a duplicate sample", 0f, clock.interval(stamp, arrival + 1_000L))
        assertEquals("a gap is capped", 0.05f, clock.interval(stamp + 200_000_000L, arrival + 100_000_000L))
        stamp += 200_000_000L
        arrival += 100_000_000L
        assertEquals("a restart after a long gap", 0f, clock.interval(stamp + 2_000_000_000L, arrival + 1_000_000_000L))
        assertEquals("the measured rate survives a restart", 0.005f,
            clock.interval(stamp + 2_010_000_000L, arrival + 1_005_000_000L), 1e-5f)
        assertEquals("a clock that went backwards restarts", 0f, clock.interval(stamp, arrival + 1_010_000_000L))
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
