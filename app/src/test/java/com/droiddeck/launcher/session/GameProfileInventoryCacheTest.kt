package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class GameProfileInventoryCacheTest {
    @Test
    fun loadsOnlyOncePerRevision() {
        val cache = GameProfileInventoryCache<String>()
        var loads = 0

        assertEquals(listOf("one"), cache.get(0) { loads++; listOf("one") })
        assertEquals(listOf("one"), cache.get(0) { loads++; listOf("two") })
        assertEquals(1, loads)

        assertEquals(listOf("two"), cache.get(1) { loads++; listOf("two") })
        assertEquals(2, loads)
    }

    @Test
    fun failedLoadIsRetriedInsteadOfCached() {
        val cache = GameProfileInventoryCache<String>()
        var loads = 0

        assertThrows(IllegalStateException::class.java) {
            cache.get(0) { loads++; error("inventory unavailable") }
        }

        assertEquals(listOf("recovered"), cache.get(0) { loads++; listOf("recovered") })
        assertEquals(2, loads)
    }

    @Test
    fun failedNewRevisionDoesNotReuseThePreviousInventory() {
        val cache = GameProfileInventoryCache<String>()
        assertEquals(listOf("old"), cache.get(0) { listOf("old") })

        assertThrows(IllegalStateException::class.java) {
            cache.get(1) { error("inventory unavailable") }
        }

        assertEquals(listOf("new"), cache.get(1) { listOf("new") })
        assertEquals(listOf("new"), cache.get(1) { error("should use cached inventory") })
    }

    @Test
    fun emptyInventoryIsCachedUntilTheRevisionChanges() {
        val cache = GameProfileInventoryCache<String>()

        assertEquals(emptyList<String>(), cache.get(0) { emptyList() })
        assertEquals(emptyList<String>(), cache.get(0) { error("should use cached inventory") })
        assertEquals(listOf("installed"), cache.get(1) { listOf("installed") })
    }

    @Test(timeout = 10_000)
    fun reopeningDuringAScanQueuesFreshInventoryWithoutWaitingAndRejectsOldResults() {
        data class Refresh(val appId: Long, val inventoryRevision: Long)

        val cache = GameProfileInventoryCache<String>()
        val coordinator = LatestRequestCoordinator<Refresh>()
        val scanStarted = CountDownLatch(1)
        val finishScan = CountDownLatch(1)
        val worker = Executors.newSingleThreadExecutor()
        try {
            assertTrue(coordinator.submit(Refresh(42, 0)))
            val first = coordinator.takeLatest()!!
            val oldInventory = worker.submit<List<String>> {
                cache.get(first.value.inventoryRevision) {
                    scanStarted.countDown()
                    check(finishScan.await(5, TimeUnit.SECONDS))
                    listOf("old")
                }
            }
            assertTrue(scanStarted.await(5, TimeUnit.SECONDS))

            assertFalse(coordinator.submit(Refresh(42, 1)))
            assertFalse(oldInventory.isDone)
            assertFalse(coordinator.isLatest(first))

            finishScan.countDown()
            assertEquals(listOf("old"), oldInventory.get(5, TimeUnit.SECONDS))
            assertFalse(coordinator.isLatest(first))
            val reopened = coordinator.takeLatest()!!
            val newInventory = worker.submit<List<String>> {
                cache.get(reopened.value.inventoryRevision) { listOf("new") }
            }
            assertEquals(listOf("new"), newInventory.get(5, TimeUnit.SECONDS))
            assertTrue(coordinator.isLatest(reopened))
            assertNull(coordinator.takeLatest())
        } finally {
            finishScan.countDown()
            worker.shutdownNow()
            assertTrue(worker.awaitTermination(5, TimeUnit.SECONDS))
        }
    }
}
