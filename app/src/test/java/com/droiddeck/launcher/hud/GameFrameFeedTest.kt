package com.droiddeck.launcher.hud

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class GameFrameFeedTest {
    private val ms = 1_000_000L

    private class FakeRing : FrameRing {
        val frames = mutableListOf<Pair<Long, Long>>()
        private val cursors = HashMap<Long, Int>()
        private var opened = 0L

        override fun open(path: String): Long {
            cursors[++opened] = 0
            return opened
        }

        override fun poll(handle: Long, frametimes: LongArray, times: LongArray): Int {
            var at = cursors[handle] ?: return -1
            var n = 0
            while (at < frames.size && n < frametimes.size) {
                frametimes[n] = frames[at].first
                times[n] = frames[at].second
                n++
                at++
            }
            cursors[handle] = at
            return n
        }

        override fun info(handle: Long, info: IntArray): String? = null
        override fun close(handle: Long) {
            cursors.remove(handle)
        }
    }

    private fun loggerFeed(ring: FakeRing, delivered: MutableList<Long>, notBeforeNs: Long = 0L) =
        GameFrameFeed(File("frames"), onFrame = { _, at -> delivered += at }, keepBacklog = true,
            notBeforeNs = notBeforeNs, ring = ring)

    @Test fun theLoggerGetsTheFramesAlreadyInTheRingWhenItOpens() {
        val ring = FakeRing()
        val delivered = mutableListOf<Long>()
        listOf(100L, 200L, 300L).forEach { ring.frames += 16 * ms to it * ms }
        assertTrue(loggerFeed(ring, delivered).poll(1_000 * ms))
        assertEquals(listOf(100 * ms, 200 * ms, 300 * ms), delivered)
    }

    @Test fun framesDrawnWhileTheRingWasClosedAreDeliveredOnceAfterItReopens() {
        val ring = FakeRing()
        val delivered = mutableListOf<Long>()
        val feed = loggerFeed(ring, delivered)
        listOf(100L, 200L).forEach { ring.frames += 16 * ms to it * ms }
        feed.poll(1_000 * ms)
        feed.poll(7_000 * ms)
        listOf(7_500L, 8_000L).forEach { ring.frames += 500 * ms to it * ms }
        feed.poll(10_000 * ms)
        assertEquals(listOf(100 * ms, 200 * ms, 7_500 * ms, 8_000 * ms), delivered)
    }

    @Test fun framesFromBeforeTheFeedWasMadeAreSkipped() {
        val ring = FakeRing()
        val delivered = mutableListOf<Long>()
        listOf(500L, 800L, 1_200L, 1_500L).forEach { ring.frames += 16 * ms to it * ms }
        loggerFeed(ring, delivered, notBeforeNs = 1_000 * ms).poll(2_000 * ms)
        assertEquals(listOf(1_200 * ms, 1_500 * ms), delivered)
    }

    @Test fun theHudStillSkipsTheBacklogWhenItOpens() {
        val ring = FakeRing()
        listOf(100L, 200L).forEach { ring.frames += 16 * ms to it * ms }
        val feed = GameFrameFeed(File("frames"), ring = ring)
        assertFalse(feed.poll(1_000 * ms))
        ring.frames += 16 * ms to 1_100 * ms
        assertTrue(feed.poll(1_200 * ms))
    }
}
