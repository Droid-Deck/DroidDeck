package com.droiddeck.launcher.stores.epic

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class EpicAdaptivePlanTest {
    private val mib = 1024L * 1024
    private val gib = 1024 * mib

    @Test fun freshInstallsNeedNoOpenStagingAllowance() {
        assertEquals(0L, EpicDownloadManager.adaptiveOpenStagingAllowance(emptyMap()))
        val old = mapOf("big.pak" to 10 * gib, "small.pak" to gib)
        assertEquals(10 * gib + EpicDownloadManager.ADAPTIVE_OPEN_WINDOW_BYTES, EpicDownloadManager.adaptiveOpenStagingAllowance(old))
        assertEquals(100 * mib, EpicDownloadManager.adaptiveOpenStagingAllowance(mapOf("a" to 60 * mib, "b" to 40 * mib)))
    }

    @Test fun cacheBudgetIsHalfTheHeadroomCappedAndDefaultsWhenSpaceIsUnknown() {
        assertEquals(EpicDownloadManager.ADAPTIVE_CACHE_DEFAULT_BYTES, EpicDownloadManager.adaptiveCacheBudget(-1, 0))
        assertEquals(EpicDownloadManager.ADAPTIVE_CACHE_MAX_BYTES, EpicDownloadManager.adaptiveCacheBudget(100 * gib, 10 * gib))
        assertEquals(0L, EpicDownloadManager.adaptiveCacheBudget(gib, gib))
        val free = 1000 * mib
        assertEquals((free - 400 * mib - EpicDownloadManager.ADAPTIVE_TRANSIENT_BYTES) / 2, EpicDownloadManager.adaptiveCacheBudget(free, 400 * mib))
    }

    @Test fun cardInstallKeepsTheCacheOnInternalScratchWhenItFits() {
        val plan = EpicDownloadManager.planAdaptiveSpace(20 * gib, 0, 30 * gib, true, 10 * gib)
        assertNull(plan.error)
        assertFalse(plan.cacheBesideGame)
        assertEquals(EpicDownloadManager.ADAPTIVE_CACHE_MAX_BYTES, plan.cacheBudget)
        assertEquals(20 * gib, plan.gameNeed)
        assertEquals(plan.cacheBudget + EpicDownloadManager.ADAPTIVE_TRANSIENT_BYTES, plan.scratchNeed)
    }

    @Test fun fullInternalStorageMovesTheCacheBesideTheGameInsteadOfFailing() {
        val plan = EpicDownloadManager.planAdaptiveSpace(20 * gib, 0, 30 * gib, true, 100 * mib)
        assertNull(plan.error)
        assertTrue(plan.cacheBesideGame)
        assertEquals(0L, plan.scratchNeed)
        assertTrue(plan.cacheBudget > 0)
        assertEquals(20 * gib + plan.cacheBudget + EpicDownloadManager.ADAPTIVE_TRANSIENT_BYTES, plan.gameNeed)
        assertTrue(plan.gameNeed <= 30 * gib)
    }

    @Test fun shortStorageIsNamedWithTheShortfall() {
        val card = EpicDownloadManager.planAdaptiveSpace(20 * gib, 0, 10 * gib, true, 10 * gib)
        assertTrue(card.error!!.contains("game's storage"))
        assertTrue(card.error!!.contains("short by 10.0 GB"))
        val internal = EpicDownloadManager.planAdaptiveSpace(20 * gib, gib, 20 * gib, false, -1)
        assertTrue(internal.error!!.contains("game's storage"))
        assertNull(EpicDownloadManager.planAdaptiveSpace(20 * gib, 0, -1, true, -1).error)
    }
}
