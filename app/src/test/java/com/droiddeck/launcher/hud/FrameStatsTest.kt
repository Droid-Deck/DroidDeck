package com.droiddeck.launcher.hud

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class FrameStatsTest {
    /** MangoHud's update_hud_info_with_frametime and fps()/frame_timing(), line by line (overlay.cpp). */
    private class Mango {
        val frametimeData = ArrayList<Float>(List(200) { 0f })
        var lastPresentTime = 0L
        var lastFpsUpdate = 0L
        var framesSinceUpdate = 0L
        var fps = 0.0

        fun update(frametimeNs: Long, now: Long) {
            val elapsed = now - lastFpsUpdate
            if (lastPresentTime != 0L) {
                frametimeData.add(frametimeNs / 1000000f)
                frametimeData.removeAt(0)
            }
            if (elapsed >= 500000000L) {
                fps = 1000000000.0 * framesSinceUpdate / elapsed
                framesSinceUpdate = 0
                lastFpsUpdate = now
            }
            lastPresentTime = now
            framesSinceUpdate++
        }
    }

    @Test fun readingsMatchMangoHudFrameByFrame() {
        val random = Random(7)
        val stats = FrameStats()
        val mango = Mango()
        var now = 5_000_000_000L
        val graph = FloatArray(FrameStats.GRAPH_FRAMES)
        repeat(20_000) { i ->
            val frametime = when {
                i % 977 == 0 -> 100_000_000L
                i % 131 == 0 -> 33_000_000L
                else -> 2_700_000L + random.nextLong(-400_000L, 400_000L)
            }
            now += frametime
            stats.add(frametime, now)
            mango.update(frametime, now)
            assertEquals("fps at frame $i", mango.fps.toFloat(), stats.fps, 0.0001f)
            assertEquals(1000f / maxOf(mango.fps.toFloat(), 1f), stats.frametimeMs(), 0.0001f)
            if (i % 499 == 0 || i < 210) {
                stats.graph(graph)
                assertArrayEquals("graph at frame $i", mango.frametimeData.toFloatArray(), graph, 0f)
                assertEquals(mango.frametimeData.min(), stats.graphMinMs(), 0f)
                assertEquals(mango.frametimeData.max(), stats.graphMaxMs(), 0f)
            }
        }
    }

    @Test fun compositorFramesWakeTheHudWhenGamescopeSharesNone() {
        val feed = GameFrameFeed(java.io.File("/nonexistent/droiddeck-hud/frames"))
        var now = 1_000_000_000L
        assertFalse(feed.poll(now))
        feed.onCompositorFrame(now)
        now += 16_666_667L
        feed.onCompositorFrame(now)
        assertTrue(feed.poll(now))
        assertFalse(feed.poll(now + 1))
    }

    @Test fun aCompletedPeriodIsCountedWhenMangoHudWouldRedraw() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        stats.add(10_000_000L, now)
        assertEquals(1L, stats.fpsUpdates)
        repeat(49) { now += 10_000_000L; stats.add(10_000_000L, now) }
        assertEquals(1L, stats.fpsUpdates)
        now += 10_000_000L; stats.add(10_000_000L, now)
        assertEquals(2L, stats.fpsUpdates)
        assertEquals(100f, stats.fps, 0.01f)
    }

    @Test fun aSteadyRateReadsExactlyWithoutMillisecondRounding() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        val frametime = 16_666_667L
        repeat(600) { now += frametime; stats.add(frametime, now) }
        assertEquals(60f, stats.fps, 0.01f)
        val lows = stats.lows(now)
        assertEquals(60f, lows.low1Fps, 0.01f)
        assertEquals(60f, lows.low01Fps, 0.01f)
        assertEquals(60f, stats.averageFps(), 0.01f)
    }

    @Test fun stuttersShowInTheGraphAndTheLows() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        repeat(1000) { i ->
            val frametime = if (i % 100 == 50) 100_000_000L else 2_700_000L
            now += frametime
            stats.add(frametime, now)
        }
        assertEquals(100f, stats.graphMaxMs(), 0.001f)
        assertEquals(2.7f, stats.graphMinMs(), 0.001f)
        assertEquals(10f, stats.lows(now).low01Fps, 0.01f)
        assertEquals(370.37f, stats.lows(now).low1Fps, 0.01f)
    }

    @Test fun pausesStayOutOfTheAverageAndLowsButNotTheGraph() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        repeat(300) { now += 10_000_000L; stats.add(10_000_000L, now) }
        now += 3_000_000_000L
        stats.add(3_000_000_000L, now)
        assertEquals(100f, stats.lows(now).low01Fps, 0.01f)
        assertEquals(3000f, stats.graphMaxMs(), 0.01f)
    }

    @Test fun resetStartsOver() {
        val stats = FrameStats()
        var now = 1_000_000_000L
        repeat(300) { now += 10_000_000L; stats.add(10_000_000L, now) }
        stats.reset()
        assertEquals(0f, stats.fps, 0f)
        assertEquals(0f, stats.graphMaxMs(), 0f)
        assertEquals(FrameStats.Lows.EMPTY, stats.lows(now))
    }

    @Test fun engineNamesFollowMangoHud() {
        assertEquals("DXVK", GameFrameFeed.engineLabel("DXVK"))
        assertEquals("VKD3D", GameFrameFeed.engineLabel("vkd3d"))
        assertEquals("ZINK", GameFrameFeed.engineLabel("mesa zink"))
        assertEquals("GAMESCOPE", GameFrameFeed.engineLabel("gamescope"))
        assertEquals("GAMESCOPE", GameFrameFeed.engineLabel(""))
        assertEquals("VULKAN", GameFrameFeed.engineLabel("UnityEngine"))
    }
}
