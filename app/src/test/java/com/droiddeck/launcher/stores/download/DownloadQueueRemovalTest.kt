package com.droiddeck.launcher.stores.download

import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreInstalls
import com.droiddeck.launcher.stores.StoresState
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadQueueRemovalTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanRows() { DownloadQueue.dismissFinished() }

    private class BlockingCleanup {
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancels = AtomicInteger()
        fun job() = object : DownloadQueue.DownloadJob {
            override fun run(handle: DownloadQueue.JobHandle): String? = throw IllegalStateException("fails on purpose")
            override fun cancel(deleteFiles: Boolean) {
                cancels.incrementAndGet()
                started.countDown()
                release.await(5, TimeUnit.SECONDS)
            }
        }
    }

    private fun failedEntry(id: String, cleanup: BlockingCleanup): String {
        val entry = DownloadEntry(Store.GOG, id, id)
        DownloadQueue.enqueue(app, entry) { cleanup.job() }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) {
            if (DownloadQueue.entry(entry.key)?.state == DownloadState.FAILED) return entry.key
            Thread.sleep(10)
        }
        fail("job never failed: ${DownloadQueue.entry(entry.key)}")
        return entry.key
    }

    private fun awaitHold(key: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) {
            if (DownloadQueue.holdForRemoval(key)) return
            Thread.sleep(10)
        }
        fail("removal hold never became available for $key")
    }

    @Test fun aRemovalHoldIsExclusiveUntilReleased() {
        val key = "gog:removal-hold-test"
        assertTrue(DownloadQueue.holdForRemoval(key))
        assertFalse(DownloadQueue.holdForRemoval(key))
        DownloadQueue.releaseRemoval(key)
        assertTrue(DownloadQueue.holdForRemoval(key))
        DownloadQueue.releaseRemoval(key)
    }

    @Test fun clearingFirstKeepsRemovalOutUntilItsCleanupFinishes() {
        val cleanup = BlockingCleanup()
        val key = failedEntry("clear-then-hold", cleanup)
        DownloadQueue.clear(app, key)
        assertTrue(cleanup.started.await(5, TimeUnit.SECONDS))
        assertFalse(DownloadQueue.holdForRemoval(key))
        cleanup.release.countDown()
        awaitHold(key)
        DownloadQueue.releaseRemoval(key)
    }

    @Test fun cancellingFirstKeepsRemovalOutUntilItsCleanupFinishes() {
        val cleanup = BlockingCleanup()
        val key = failedEntry("cancel-then-hold", cleanup)
        DownloadQueue.cancel(app, key)
        assertTrue(cleanup.started.await(5, TimeUnit.SECONDS))
        assertFalse(DownloadQueue.holdForRemoval(key))
        cleanup.release.countDown()
        awaitHold(key)
        DownloadQueue.releaseRemoval(key)
    }

    @Test fun holdingFirstStopsClearAndCancelFromStartingCleanup() {
        val cleared = BlockingCleanup()
        val clearKey = failedEntry("hold-then-clear", cleared)
        assertTrue(DownloadQueue.holdForRemoval(clearKey))
        DownloadQueue.clear(app, clearKey)
        val cancelled = BlockingCleanup()
        val cancelKey = failedEntry("hold-then-cancel", cancelled)
        assertTrue(DownloadQueue.holdForRemoval(cancelKey))
        DownloadQueue.cancel(app, cancelKey)
        assertFalse(cleared.started.await(300, TimeUnit.MILLISECONDS))
        assertFalse(cancelled.started.await(300, TimeUnit.MILLISECONDS))
        assertEquals(0, cleared.cancels.get() + cancelled.cancels.get())
        DownloadQueue.releaseRemoval(clearKey)
        DownloadQueue.releaseRemoval(cancelKey)
        cleared.release.countDown()
        cancelled.release.countDown()
    }

    @Test fun aReinstallWaitsForTheCancelledDownloadsCleanup() {
        val cleanup = BlockingCleanup()
        val key = failedEntry("reinstall-after-clear", cleanup)
        DownloadQueue.clear(app, key)
        assertTrue(cleanup.started.await(5, TimeUnit.SECONDS))
        val ran = CountDownLatch(1)
        DownloadQueue.enqueue(app, DownloadEntry(Store.GOG, "reinstall-after-clear", "again")) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? { ran.countDown(); return "/done" }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertFalse(ran.await(300, TimeUnit.MILLISECONDS))
        assertEquals(DownloadState.QUEUED, DownloadQueue.entry(key)?.state)
        cleanup.release.countDown()
        assertTrue(ran.await(5, TimeUnit.SECONDS))
    }

    @Test fun cleanupCollectsTheDeletionWorkerAnInstallerStarts() {
        val folder = File(app.filesDir, "collected-discard").apply { mkdirs() }
        File(folder, "part.bin").writeText("x")
        val workers = StoreInstalls.collectingDiscards { StoreInstalls.discard(app, folder, emptyList()) }
        assertEquals(1, workers.size)
        workers.forEach { it.join(5000) }
        assertFalse(folder.exists())
        assertFalse(StoreInstalls.isDiscarding(folder))
    }

    private fun idleUntil(condition: () -> Boolean): Boolean {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (condition()) return true
            Thread.sleep(10)
        }
        return false
    }

    @Test fun uninstallWaitsForAnInstallersDiscardThenRemovesTheGame() {
        val folder = File(app.filesDir, "discarding-game").apply { mkdirs() }
        File(folder, "game.bin").writeText("x")
        val game = InstalledStoreGame(StoreGameSidecar(Store.GOG, "discarding", "Discarding", exe = "game.bin"), folder)
        StoreInstalls.beginDiscard(folder)
        val done = CountDownLatch(1)
        try {
            StoresState.uninstall(app, game) { done.countDown() }
            Thread.sleep(300)
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertEquals(1L, done.count)
            assertTrue(File(folder, "game.bin").isFile)
        } finally {
            StoreInstalls.endDiscard(folder)
        }
        assertTrue(idleUntil { done.count == 0L })
        assertFalse(folder.exists())
    }

    @Test fun uninstallWaitsForACancelledUpdatesCleanupThenRemovesTheGame() {
        val cleanup = BlockingCleanup()
        val key = failedEntry("cancel-then-uninstall", cleanup)
        val folder = File(app.filesDir, "cancel-then-uninstall").apply { mkdirs() }
        File(folder, "game.bin").writeText("x")
        val game = InstalledStoreGame(StoreGameSidecar(Store.GOG, "cancel-then-uninstall", "Cancelled", exe = "game.bin"), folder)
        assertEquals(key, game.key)
        DownloadQueue.cancel(app, key)
        assertTrue(cleanup.started.await(5, TimeUnit.SECONDS))
        val done = CountDownLatch(1)
        StoresState.uninstall(app, game) { done.countDown() }
        Thread.sleep(300)
        org.robolectric.shadows.ShadowLooper.idleMainLooper()
        assertEquals(1L, done.count)
        assertTrue(File(folder, "game.bin").isFile)
        cleanup.release.countDown()
        assertTrue(idleUntil { done.count == 0L })
        assertFalse(folder.exists())
    }

    private fun blockDiscards(): CountDownLatch {
        val release = CountDownLatch(1)
        StoreInstalls.beforeDiscard = { release.await(5, TimeUnit.SECONDS) }
        return release
    }

    @After fun restoreDiscards() { StoreInstalls.beforeDiscard = {} }

    @Test fun aDiscardStartedInsideARunningJobKeepsTheGameHeld() {
        val folder = File(app.filesDir, "run-discard").apply { mkdirs() }
        val release = blockDiscards()
        val entry = DownloadEntry(Store.GOG, "run-discard", "run")
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? {
                    StoreInstalls.discard(app, folder, emptyList())
                    return null
                }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertTrue(idleUntil { DownloadQueue.entry(entry.key)?.state == DownloadState.FAILED })
        assertFalse(DownloadQueue.holdForRemoval(entry.key))
        release.countDown()
        awaitHold(entry.key)
        DownloadQueue.releaseRemoval(entry.key)
    }

    @Test fun aJobThatThrowsAfterStartingADiscardStillKeepsTheGameHeld() {
        val folder = File(app.filesDir, "throw-after-discard").apply { mkdirs() }
        val release = blockDiscards()
        val entry = DownloadEntry(Store.GOG, "throw-after-discard", "throws")
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? {
                    StoreInstalls.discard(app, folder, emptyList())
                    throw IllegalStateException("fails after discard")
                }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertTrue(idleUntil { DownloadQueue.entry(entry.key)?.state == DownloadState.FAILED })
        assertFalse(DownloadQueue.holdForRemoval(entry.key))
        val reinstalled = CountDownLatch(1)
        DownloadQueue.enqueue(app, DownloadEntry(Store.GOG, "throw-after-discard", "again")) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? { reinstalled.countDown(); return "/done" }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertFalse(reinstalled.await(300, TimeUnit.MILLISECONDS))
        assertEquals(DownloadState.QUEUED, DownloadQueue.entry(entry.key)?.state)
        release.countDown()
        assertTrue(reinstalled.await(5, TimeUnit.SECONDS))
        assertTrue(idleUntil { DownloadQueue.entry(entry.key)?.state == DownloadState.INSTALLED })
    }

    @Test fun cancellingARunningJobTwiceWaitsForEveryDiscard() {
        val folder = File(app.filesDir, "double-cancel").apply { mkdirs() }
        val release = blockDiscards()
        val running = CountDownLatch(1)
        val stop = CountDownLatch(1)
        val entry = DownloadEntry(Store.GOG, "double-cancel", "twice")
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? {
                    running.countDown()
                    stop.await(5, TimeUnit.SECONDS)
                    return null
                }
                override fun cancel(deleteFiles: Boolean) {
                    if (deleteFiles) StoreInstalls.discard(app, folder, emptyList())
                }
            }
        }
        assertTrue(running.await(5, TimeUnit.SECONDS))
        DownloadQueue.cancel(app, entry.key)
        DownloadQueue.cancel(app, entry.key)
        stop.countDown()
        assertTrue(idleUntil { DownloadQueue.entry(entry.key)?.state != DownloadState.RUNNING })
        assertFalse(DownloadQueue.holdForRemoval(entry.key))
        release.countDown()
        awaitHold(entry.key)
        DownloadQueue.releaseRemoval(entry.key)
    }
}
