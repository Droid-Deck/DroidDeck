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

    @Test fun uninstallWaitsWhileAnInstallerIsStillDiscardingTheFolder() {
        val folder = File(app.filesDir, "discarding-game").apply { mkdirs() }
        File(folder, "game.bin").writeText("x")
        val game = InstalledStoreGame(StoreGameSidecar(Store.GOG, "discarding", "Discarding", exe = "game.bin"), folder)
        StoreInstalls.beginDiscard(folder)
        try {
            val done = CountDownLatch(1)
            StoresState.uninstall(app, game) { done.countDown() }
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            assertTrue(File(folder, "game.bin").isFile)
            assertTrue(DownloadQueue.holdForRemoval(game.key))
            DownloadQueue.releaseRemoval(game.key)
        } finally {
            StoreInstalls.endDiscard(folder)
            folder.deleteRecursively()
        }
    }
}
