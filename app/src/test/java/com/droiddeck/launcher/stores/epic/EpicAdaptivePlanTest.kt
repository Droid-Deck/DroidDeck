package com.droiddeck.launcher.stores.epic

import java.io.File
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicAdaptivePlanTest {
    @get:Rule val temp = TemporaryFolder()
    private val mib = 1024L * 1024
    private val gib = 1024 * mib

    private fun file(name: String, size: Long) = EpicDownloadManager.FileInfo().apply {
        filename = name
        var left = size
        while (left > 0) {
            val part = minOf(left, Int.MAX_VALUE.toLong())
            parts.add(EpicDownloadManager.ChunkPart().apply { this.size = part.toInt() })
            left -= part
        }
    }

    @Test fun netPeakCreditsEachOldCopyAtItsSwapAndLargestReplacedIgnoresFreshFiles() {
        val files = listOf(file("a", 10 * gib), file("b", 2 * gib), file("c", 4 * gib))
        val old = mapOf("a" to 9 * gib, "c" to 5 * gib)
        // Running Σ(new − old): +1, +3, +2 GiB.
        assertEquals(3 * gib, EpicDownloadManager.settledNetPeak(files, old))
        assertEquals(10 * gib, EpicDownloadManager.largestReplacedFile(files, old))
        assertEquals(16 * gib, EpicDownloadManager.settledNetPeak(files, emptyMap()))
        assertEquals(0L, EpicDownloadManager.largestReplacedFile(files, emptyMap()))
        assertEquals(0L, EpicDownloadManager.largestReplacedFile(files, mapOf("b" to 0L)))
    }

    @Test fun cacheBudgetIsHalfTheHeadroomCappedAndDefaultsWhenSpaceIsUnknown() {
        assertEquals(EpicDownloadManager.ADAPTIVE_CACHE_DEFAULT_BYTES, EpicDownloadManager.adaptiveCacheBudget(-1, 0))
        assertEquals(EpicDownloadManager.ADAPTIVE_CACHE_MAX_BYTES, EpicDownloadManager.adaptiveCacheBudget(100 * gib, 10 * gib))
        assertEquals(0L, EpicDownloadManager.adaptiveCacheBudget(gib, gib))
        val free = 1000 * mib
        assertEquals((free - 400 * mib - EpicDownloadManager.ADAPTIVE_TRANSIENT_BYTES) / 2, EpicDownloadManager.adaptiveCacheBudget(free, 400 * mib))
    }

    @Test fun freshInstallsHaveNoOverlapLimit() {
        val plan = EpicDownloadManager.planAdaptiveSpace(20 * gib, 0, 30 * gib, true, 10 * gib)
        assertNull(plan.error)
        assertEquals(-1L, plan.overlapBudget)
        assertEquals(20 * gib, plan.gameNeed)
    }

    @Test fun updateOverlapBudgetIsTheLeftoverAfterNetGrowthAndCache() {
        // Card install, cache on internal: the card's leftover after the net growth is the budget.
        val roomy = EpicDownloadManager.planAdaptiveSpace(gib, 4 * gib, 20 * gib, true, 10 * gib)
        assertNull(roomy.error)
        assertFalse(roomy.cacheBesideGame)
        assertEquals(19 * gib, roomy.overlapBudget)
        assertEquals(20 * gib, roomy.gameNeed)
        // Tight: floored at the largest replaced file, one at a time.
        val tight = EpicDownloadManager.planAdaptiveSpace(gib, 4 * gib, 5 * gib, true, 10 * gib)
        assertNull(tight.error)
        assertEquals(4 * gib, tight.overlapBudget)
        // Not even one replaced file fits: the shortfall is named.
        val short = EpicDownloadManager.planAdaptiveSpace(gib, 4 * gib, 4 * gib, true, 10 * gib)
        assertTrue(short.error!!.contains("game's storage"))
        assertTrue(short.error!!.contains("short by 1.0 GB"))
        // Unknown free space: one replaced file at a time.
        assertEquals(4 * gib, EpicDownloadManager.planAdaptiveSpace(gib, 4 * gib, -1, true, -1).overlapBudget)
    }

    @Test fun cacheBesideTheGameComesOutOfTheOverlapBudget() {
        val plan = EpicDownloadManager.planAdaptiveSpace(gib, 2 * gib, 10 * gib, false, -1)
        assertNull(plan.error)
        assertTrue(plan.cacheBesideGame)
        assertTrue(plan.cacheBudget > 0)
        assertEquals(10 * gib - gib - plan.cacheBudget - EpicDownloadManager.ADAPTIVE_TRANSIENT_BYTES, plan.overlapBudget)
        assertEquals(10 * gib, plan.gameNeed)
    }

    @Test fun fullInternalStorageMovesTheCacheBesideTheGameInsteadOfFailing() {
        val plan = EpicDownloadManager.planAdaptiveSpace(20 * gib, 0, 30 * gib, true, 100 * mib)
        assertNull(plan.error)
        assertTrue(plan.cacheBesideGame)
        assertEquals(0L, plan.scratchNeed)
        assertTrue(plan.cacheBudget > 0)
        assertEquals(20 * gib + plan.cacheBudget + EpicDownloadManager.ADAPTIVE_TRANSIENT_BYTES, plan.gameNeed)
    }

    @Test fun ownedStagingIsRemovedOnlyWhenItHoldsJustItsMarker() {
        val root = temp.newFolder("game")
        val staging = EpicDownloadManager.recoverOwnedStaging(root, emptyList())
        File(staging, "keep.stage").writeText("x")
        EpicDownloadManager.releaseOwnedStaging(root)
        assertTrue(File(staging, "owner").exists())
        File(staging, "keep.stage").delete()
        EpicDownloadManager.releaseOwnedStaging(root)
        assertFalse(staging.exists())
        // A directory without the marker is not ours.
        staging.mkdir()
        File(staging, "owner").writeText("someone else")
        EpicDownloadManager.releaseOwnedStaging(root)
        assertTrue(staging.exists())
    }
}
