package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GameThreadHintsTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun `hints support modern multi-cluster Snapdragon, Dimensity, Exynos and Tensor SoCs`() {
        val supportedSocs = listOf(
            "CQ8725S",
            "SM8450", "SM8475", "SM8550", "QCS8550", "SM8550-AC",
            "SM8650", "SM8650-AC", "SM8635", "SM8735", "SM8845",
            "SM8750", "QTI SM8750", "QTI SM8750P", "SM8750-3-AB",
            "SM8850", "QTI SM8850", "QTI SM8850P", "SM8850-AC",
            "SM8975", "QTI SM8975",
            "SM7475", "SM7675", "SG8275P",
            "MT6895", "MT6895Z/CZA", "MT6897", "MT6899",
            "MT6983", "MT6985", "MT6989", "MT6989W", "MT6991", "MT6993",
            "Dimensity 9300+", "MediaTek Dimensity 8300",
            "s5e9925", "s5e9945", "s5e9955", "Exynos 2400", "EXYNOS2500",
            "gs101", "gs201", "zuma", "zumapro", "Tensor", "Tensor G2", "Tensor G3", "Tensor G4",
        )
        for (soc in supportedSocs) {
            assertTrue(soc, GameThreadHints.supported(soc))
        }
        for (unsupported in listOf("", "unknown", "SM6375", "SM89750", "SM8975-PRO", "Qualcomm SM8975", "Dimensity 700")) {
            assertFalse(unsupported, GameThreadHints.supported(unsupported))
        }
        assertTrue(GameThreadHints.supported("", "canoe", null))
        assertTrue(GameThreadHints.supported("", "zuma", null))
        assertFalse(GameThreadHints.supported("", "nowhere", null))
    }

    @Test fun `dynamic cpufreq policy detection accepts asymmetric topologies and rejects homogeneous ones`() {
        val s26Ultra = temp.newFolder("s26ultra")
        writePolicy(s26Ultra, 0, 3_628_800)
        writePolicy(s26Ultra, 6, 4_742_400)
        assertTrue(GameThreadHints.hasAsymmetricClusters(s26Ultra))
        assertTrue(GameThreadHints.supported("", "", s26Ultra))

        val threeCluster = temp.newFolder("three-cluster")
        writePolicy(threeCluster, 0, 1_800_000)
        writePolicy(threeCluster, 4, 2_800_000)
        writePolicy(threeCluster, 7, 3_200_000)
        assertTrue(GameThreadHints.hasAsymmetricClusters(threeCluster))

        val fourCluster = temp.newFolder("four-cluster")
        writePolicy(fourCluster, 0, 1_960_000)
        writePolicy(fourCluster, 4, 2_600_000)
        writePolicy(fourCluster, 7, 2_900_000)
        writePolicy(fourCluster, 9, 3_210_000)
        assertTrue(GameThreadHints.hasAsymmetricClusters(fourCluster))

        val homogeneous = temp.newFolder("homogeneous")
        writePolicy(homogeneous, 0, 2_000_000)
        writePolicy(homogeneous, 4, 2_000_000)
        assertFalse(GameThreadHints.hasAsymmetricClusters(homogeneous))
        assertFalse(GameThreadHints.supported("", "", homogeneous))
        assertFalse(GameThreadHints.hasAsymmetricClusters(File(temp.root, "missing")))
    }

    private fun writePolicy(root: File, policy: Int, maxFreqKhz: Long) {
        val dir = File(root, "policy$policy").apply { mkdirs() }
        File(dir, "cpuinfo_max_freq").writeText("$maxFreqKhz\n")
    }

    @Test fun `a thread joins above 60 percent and leaves under 15`() {
        assertEquals(emptySet<Int>(), GameThreadHints.pick(emptySet(), mapOf(1 to 0.55)))
        assertEquals(setOf(1), GameThreadHints.pick(emptySet(), mapOf(1 to 0.68)))
        assertEquals(setOf(1), GameThreadHints.pick(setOf(1), mapOf(1 to 0.25)))
        assertEquals(emptySet<Int>(), GameThreadHints.pick(setOf(1), mapOf(1 to 0.1)))
    }

    @Test fun `at most two threads, the boosted ones kept before busier newcomers`() {
        val busy = mapOf(1 to 0.2, 2 to 0.9, 3 to 0.8, 4 to 0.7, 5 to 0.95)
        assertEquals(setOf(1, 5), GameThreadHints.pick(setOf(1), busy))
    }

    @Test fun `a thread gone from proc is dropped`() {
        assertEquals(setOf(2), GameThreadHints.pick(setOf(1, 2), mapOf(2 to 0.7)))
    }

    @Test fun `a game at the panel's rate or a fraction of it holds pace`() {
        assertTrue(GameThreadHints.holdsPace(119.0, 120.0))
        assertTrue(GameThreadHints.holdsPace(60.5, 120.0))
        assertTrue(GameThreadHints.holdsPace(30.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(92.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(105.0, 120.0))
        assertFalse(GameThreadHints.holdsPace(3.0, 120.0))
    }
}
