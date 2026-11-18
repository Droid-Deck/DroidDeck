package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class CpuCacheSysfsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun cache(host: File, cpu: Int, vararg levels: Pair<Int, String?>) {
        levels.forEachIndexed { i, (level, size) ->
            val index = File(host, "cpu$cpu/cache/index$i").apply { mkdirs() }
            File(index, "level").writeText("$level\n")
            File(index, "type").writeText(if (level == 1) "Data\n" else "Unified\n")
            File(index, "shared_cpu_list").writeText("$cpu\n")
            if (size != null) File(index, "size").writeText(size)
        }
    }

    @Test fun everyLevelReportsTheSizeTheEmulatedCpuidReports() {
        val host = tmp.newFolder("host")
        cache(host, 0, 1 to null, 2 to null, 3 to null)
        val root = tmp.newFolder("out")
        val out = File(root, "1")
        val binds = CpuCacheSysfs.binds(root, 1, host)
        assertEquals(listOf(File(out, "cpu0").path + ":" + File(host, "cpu0/cache").path), binds)
        assertEquals("32K\n", File(out, "cpu0/index0/size").readText())
        assertEquals("512K\n", File(out, "cpu0/index1/size").readText())
        assertEquals("8192K\n", File(out, "cpu0/index2/size").readText())
        assertEquals("2\n", File(out, "cpu0/index1/level").readText())
        assertEquals("Unified\n", File(out, "cpu0/index1/type").readText())
    }

    @Test fun aKernelSizeThatDisagreesWithTheEmulatedCpuidIsReplaced() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to "12288K\n")
        val root = tmp.newFolder("out")
        val out = File(root, "1")
        CpuCacheSysfs.binds(root, 1, host)
        assertEquals("512K\n", File(out, "cpu0/index0/size").readText())
        assertEquals("12288K\n", File(host, "cpu0/cache/index0/size").readText())
    }

    @Test fun everyCoreIsBoundAndACoreWithoutCacheInfoIsLeftAlone() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to null)
        cache(host, 1, 2 to null)
        File(host, "cpu2").mkdirs()
        File(host, "cpufreq").mkdirs()
        val root = tmp.newFolder("out")
        val out = File(root, "1")
        val binds = CpuCacheSysfs.binds(root, 1, host)
        assertEquals(2, binds.size)
        assertFalse(File(out, "cpu2").exists())
        assertTrue(binds.none { it.contains("cpufreq") })
    }

    @Test fun aLevelOutsideTheEmulatedCpuidIsNotGivenASize() {
        val host = tmp.newFolder("host")
        cache(host, 0, 4 to null)
        val root = tmp.newFolder("out")
        val out = File(root, "1")
        assertEquals(emptyList<String>(), CpuCacheSysfs.binds(root, 1, host))
        assertFalse(File(out, "cpu0/index0/size").exists())
    }

    @Test fun stalePreviousSessionFilesAreRemoved() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to null)
        val root = tmp.newFolder("out")
        val out = File(root, "1")
        File(out, "cpu7/index0").mkdirs()
        CpuCacheSysfs.binds(root, 1, host)
        assertFalse(File(out, "cpu7").exists())
    }

    @Test fun aLinkLeftInTheStagedFolderIsRemovedWithoutTouchingItsTarget() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to null)
        val kept = tmp.newFolder("kept")
        File(kept, "file").writeText("x")
        val root = tmp.newFolder("out")
        val out = File(root, "1")
        File(out, "cpu0").mkdirs()
        Files.createSymbolicLink(File(out, "cpu0/link").toPath(), kept.toPath())
        CpuCacheSysfs.binds(root, 1, host)
        assertFalse(Files.exists(File(out, "cpu0/link").toPath(), LinkOption.NOFOLLOW_LINKS))
        assertEquals("x", File(kept, "file").readText())
    }

    @Test fun anUnreadableHostLeavesNoBinds() {
        assertEquals(emptyList<String>(), CpuCacheSysfs.binds(tmp.newFolder("out"), 1, File(tmp.root, "missing")))
    }

    @Test fun olderGenerationsAndTheFlatLayoutAreRemovedButANewerGenerationIsKept() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to null)
        val root = tmp.newFolder("out")
        listOf("cpu0", "2", "5").forEach { File(root, "$it/cpu0/index0").mkdirs() }
        val binds = CpuCacheSysfs.binds(root, 3, host)
        assertEquals(listOf(File(root, "3/cpu0").path + ":" + File(host, "cpu0/cache").path), binds)
        assertFalse(File(root, "cpu0").exists())
        assertFalse(File(root, "2").exists())
        assertTrue(File(root, "5/cpu0/index0").exists())
    }

    @Test fun theFirstGenerationOfAProcessRemovesEveryEarlierFolder() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to null)
        val root = tmp.newFolder("out")
        File(root, "7/cpu0").mkdirs()
        CpuCacheSysfs.binds(root, 1, host)
        assertFalse(File(root, "7").exists())
        assertEquals("512K\n", File(root, "1/cpu0/index0/size").readText())
    }

    @Test fun aCoreWhoseMetadataCannotBeCopiedIsSkippedAndTheOthersStayBound() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to null)
        cache(host, 1, 2 to null, 4 to "1024K\n")
        val locked = File(host, "cpu1/cache/index1/size")
        locked.setReadable(false)
        assumeFalse(locked.canRead())
        val root = tmp.newFolder("out")
        val binds = CpuCacheSysfs.binds(root, 1, host)
        assertEquals(listOf(File(root, "1/cpu0").path + ":" + File(host, "cpu0/cache").path), binds)
    }

    @Test fun anUnreadableKernelSizeForAnEmulatedLevelIsStillReplaced() {
        val host = tmp.newFolder("host")
        cache(host, 0, 2 to "12288K\n")
        val locked = File(host, "cpu0/cache/index0/size")
        locked.setReadable(false)
        assumeFalse(locked.canRead())
        val root = tmp.newFolder("out")
        assertEquals(1, CpuCacheSysfs.binds(root, 1, host).size)
        assertEquals("512K\n", File(root, "1/cpu0/index0/size").readText())
    }
}
