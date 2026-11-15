package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class FramePacingTest {
    private val ms = 1_000_000L

    @Test fun nothingIsLoggedWithoutFrames() {
        assertNull(FramePacing().line(10_000 * ms, 3_000))
    }

    @Test fun aFreezeShowsUpInTheWindowThatHadIt() {
        val pacing = FramePacing()
        var now = 1_000 * ms
        repeat(89) { now += 33 * ms; pacing.add(33 * ms, now) }
        now += 400 * ms
        pacing.add(400 * ms, now)
        assertEquals(
            "last 3 s: 90 frames (30.0 fps), frame time 37.1 ms avg, 400 ms worst, 1 over 50 ms, 1 over 250 ms | " +
                "last 30 s: 1% low 30.3 fps, 0.1% low 2.5 fps",
            pacing.line(now, 3_000))
    }

    @Test fun theWindowStartsOverButTheLowsRememberThirtySeconds() {
        val pacing = FramePacing()
        var now = 1_000 * ms
        now += 400 * ms
        pacing.add(400 * ms, now)
        repeat(99) { now += 20 * ms; pacing.add(20 * ms, now) }
        pacing.line(now, 3_000)
        repeat(100) { now += 20 * ms; pacing.add(20 * ms, now) }
        val line = pacing.line(now, 3_000)!!
        assertTrue(line, line.startsWith("last 3 s: 100 frames (33.3 fps), frame time 20.0 ms avg, 20 ms worst, 0 over 50 ms, 0 over 250 ms"))
        assertTrue("an earlier freeze still counts in the lows: $line", line.endsWith("0.1% low 2.5 fps"))
        now += FramePacing.LOWS_WINDOW_NS
        pacing.add(20 * ms, now)
        assertTrue("a freeze older than 30 s still counted", pacing.line(now, 3_000)!!.endsWith("1% low 50.0 fps, 0.1% low 50.0 fps"))
    }

    @Test fun aFreezeThatIsStillGoingOnIsLoggedWithoutFrames() {
        val pacing = FramePacing()
        var now = 1_000 * ms
        repeat(30) { now += 33 * ms; pacing.add(33 * ms, now) }
        pacing.line(now, 3_000)
        now += 4_200 * ms
        assertEquals("last 3 s: no frames, last one 4.2 s ago", pacing.line(now, 3_000))
        now += FramePacing.LOWS_WINDOW_NS
        assertNull("a game that stopped long ago kept logging", pacing.line(now, 3_000))
    }

    @Test fun fpsFollowsTheRealLengthOfTheWindow() {
        val pacing = FramePacing()
        var now = 1_000 * ms
        repeat(100) { now += 33 * ms; pacing.add(33 * ms, now) }
        assertTrue(pacing.line(now, 4_000)!!.startsWith("last 4 s: 100 frames (25.0 fps)"))
    }

    @Test fun lowsSayNotAvailableWhenEveryFrameWasAPause() {
        val pacing = FramePacing()
        pacing.add(5_000 * ms, 10_000 * ms)
        assertTrue(pacing.line(10_000 * ms, 3_000)!!.endsWith("1% low n/a, 0.1% low n/a"))
    }

    @Test fun aPauseCountsAsAFreezeButNotInTheLows() {
        val pacing = FramePacing()
        var now = 1_000 * ms
        repeat(50) { now += 16 * ms; pacing.add(16 * ms, now) }
        now += 5_000 * ms
        pacing.add(5_000 * ms, now)
        val line = pacing.line(now, 3_000)!!
        assertTrue(line, line.contains("5000 ms worst, 1 over 50 ms, 1 over 250 ms"))
        assertTrue(line, line.endsWith("1% low 62.5 fps, 0.1% low 62.5 fps"))
    }
}
