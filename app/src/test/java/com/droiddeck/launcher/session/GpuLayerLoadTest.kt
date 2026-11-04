package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpuLayerLoadTest {
    @get:Rule val tmp = TemporaryFolder()
    private var now = 0L
    private fun load(dir: File) = GpuStatsComponent.LayerLoad(dir, clock = { now })
    private fun write(dir: File, pid: Int, busyNs: Long) = File(dir, "$pid").writeText("%20d %-32s\n".format(busyNs, "game"))

    @Test fun busyTimeOfAllProcessesOverWallTime() {
        val dir = tmp.newFolder("gpu-load")
        val load = load(dir)
        write(dir, 100, 1_000_000_000L)
        write(dir, 200, 5_000_000_000L)
        now = 10_000_000_000L
        assertNull(load.percent())
        write(dir, 100, 1_300_000_000L)
        write(dir, 200, 5_100_000_000L)
        now += 1_000_000_000L
        assertEquals(40L, load.percent())
    }

    @Test fun aNewProcessStartsFromItsOwnTotalAndAGoneOneStopsCounting() {
        val dir = tmp.newFolder("gpu-load")
        val load = load(dir)
        write(dir, 100, 2_000_000_000L)
        now = 1L
        load.percent()
        File(dir, "100").delete()
        write(dir, 300, 9_000_000_000L)
        now += 500_000_000L
        assertEquals(0L, load.percent())
        write(dir, 300, 9_250_000_000L)
        now += 500_000_000L
        assertEquals(50L, load.percent())
    }

    @Test fun capsAtFullAndIgnoresOtherFiles() {
        val dir = tmp.newFolder("gpu-load")
        val load = load(dir)
        File(dir, "on").createNewFile()
        write(dir, 1, 0L)
        write(dir, 2, 0L)
        now = 1L
        load.percent()
        write(dir, 1, 900_000_000L)
        write(dir, 2, 900_000_000L)
        now += 1_000_000_000L
        assertEquals(100L, load.percent())
    }

    @Test fun theOnFlagFollowsTheHudAndHidingDropsTheBaseline() {
        val dir = tmp.newFolder("gpu-load")
        val load = load(dir)
        load.wanted(true)
        assertTrue(File(dir, "on").exists())
        write(dir, 1, 100L)
        now = 1L
        load.percent()
        load.wanted(false)
        assertFalse(File(dir, "on").exists())
        load.wanted(true)
        now += 1_000L
        assertNull(load.percent())
    }

    @Test fun theCompositorsOwnGpuTimeAddsToTheGames() {
        val dir = tmp.newFolder("gpu-load")
        var compositor: Long? = 7_000_000_000L
        val load = GpuStatsComponent.LayerLoad(dir, clock = { now }, extra = { compositor })
        write(dir, 100, 1_000_000_000L)
        now = 1L
        assertNull(load.percent())
        write(dir, 100, 1_600_000_000L)
        compositor = 7_150_000_000L
        now += 1_000_000_000L
        assertEquals(75L, load.percent())
        compositor = null
        write(dir, 100, 1_900_000_000L)
        now += 1_000_000_000L
        assertEquals(30L, load.percent())
    }

    @Test fun onlyTheCompositorCountsWhenNoGameReportsYet() {
        val dir = File(tmp.root, "never-created")
        var compositor = 0L
        val load = GpuStatsComponent.LayerLoad(dir, clock = { now }, extra = { compositor })
        now = 1L
        load.percent()
        compositor = 200_000_000L
        now += 1_000_000_000L
        assertEquals(20L, load.percent())
    }

    @Test fun compositorTimingFollowsTheHudOnlyOnChanges() {
        val dir = tmp.newFolder("gpu-load")
        val calls = mutableListOf<Boolean>()
        val load = GpuStatsComponent.LayerLoad(dir, clock = { now }, onWanted = { calls += it })
        load.wanted(true)
        load.wanted(true)
        load.wanted(false)
        load.wanted(true)
        load.clear()
        assertEquals(listOf(true, false, true, false), calls)
    }
}
