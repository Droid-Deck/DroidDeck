package com.droiddeck.launcher.session

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class GpuStatsBindsTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun feedsWithNoValueYetAreStillBound() {
        val dir = tmp.newFolder("kgsl-3d0")
        GpuStatsComponent.createMissing(listOf(File(dir, "perf_now"), File(dir, "cur_freq")))
        val binds = GpuStatsComponent(dir, tmp.root).binds()
        assertTrue(binds.contains(File(dir, "perf_now").path + ":" + GpuStatsComponent.MSM_LOAD))
        assertTrue(binds.contains(File(dir, "cur_freq").path + ":" + GpuStatsComponent.MSM_CLOCK))
    }

    @Test fun aWrittenValueIsKept() {
        val dir = tmp.newFolder("kgsl-3d0")
        File(dir, "cur_freq").writeText("586000000 \n")
        GpuStatsComponent.createMissing(listOf(File(dir, "cur_freq")))
        assertEquals("586000000 \n", File(dir, "cur_freq").readText())
    }
}
