package com.droiddeck.launcher.stores.download

import android.content.Context
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.*
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowStatFs

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadQueueRetryTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After fun cleanRows() { DownloadQueue.dismissFinished(); StoreSizes.known.clear() }

    private fun awaitFailure(key: String, error: String) {
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < until) {
            val entry = DownloadQueue.entry(key)
            if (entry?.state == DownloadState.FAILED && entry.error == error) return
            Thread.sleep(10)
        }
        fail("Expected failed real job: ${DownloadQueue.entry(key)}")
    }

    @Test fun storageRetryRechecksThenRunsTheRealBackendAndNormalRetryStillWorks() {
        StoreInstallRoot.internalRoot(app).mkdirs()
        ShadowStatFs.registerStats(StoreInstallRoot.internalRoot(app).path, 1024, 100, 100)
        val item = CatalogItem(Store.GOG, "preflight-retry", "Preflight retry", imageUrl = null, sizeBytes = Long.MAX_VALUE)
        val runs = AtomicInteger()
        var lastTarget: File? = null
        val previous = StoresState.backend(Store.GOG)
        val backend = object : StoreBackend {
            override val store = Store.GOG
            override fun signIn(context: Context) {}
            override fun signOut(context: Context) {}
            override fun syncLibrary(context: Context, force: Boolean) {}
            override fun loadShelves(context: Context, force: Boolean) {}
            override fun uninstall(context: Context, game: InstalledStoreGame) {}
            override fun install(context: Context, item: CatalogItem, root: File) {
                lastTarget = root
                DownloadQueue.enqueue(context, DownloadEntry(item.store, item.id, item.title)) {
                    object : DownloadQueue.DownloadJob {
                        override fun run(handle: DownloadQueue.JobHandle): String? {
                            throw IllegalStateException("backend-${runs.incrementAndGet()}")
                        }
                        override fun cancel(deleteFiles: Boolean) {}
                    }
                }
            }
        }
        StoresState.register(backend)
        try {
            StoresState.install(app, item)
            assertEquals(DownloadState.FAILED, DownloadQueue.entry(item.key)?.state)
            DownloadQueue.retry(app, item.key)
            assertEquals(DownloadState.FAILED, DownloadQueue.entry(item.key)?.state)
            assertEquals(0, runs.get())
            StoreSizes.known[item.key] = 1
            DownloadQueue.retry(app, item.key)
            awaitFailure(item.key, "backend-1")
            DownloadQueue.retry(app, item.key)
            awaitFailure(item.key, "backend-2")
            DownloadQueue.clear(app, item.key)
            assertNull(DownloadQueue.entry(item.key))

            // A card resume (including the restored paused path) must retain its real target.
            val card = File(app.filesDir, "retry-card").apply { mkdirs() }
            SessionPrefs.setGameStorage(app, card.path, "Card")
            val target = File(card, "Games")
            val folder = File(StoreInstallRoot.storeDir(target, item.store), StoreInstallRoot.folderName(item.title, item.id)).apply { mkdirs() }
            StoreInstalls.begin(folder, item.store, item.id, item.title, null, null)
            StoreSizes.known[item.key] = Long.MAX_VALUE
            StoresState.unfinished = StoreInstallRoot.unfinished(app)
            DownloadQueue.holdPaused(item.key)
            StoresState.requestInstall(app, item)
            assertEquals(target, lastTarget)
            assertEquals(DownloadState.PAUSED, DownloadQueue.entry(item.key)?.state)
            assertEquals(2, runs.get())
            val saved = File(app.filesDir, "store-queue.json")
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            var correctSavedRoot = false
            while (!correctSavedRoot && System.nanoTime() < until) {
                correctSavedRoot = runCatching {
                    val rows = JSONArray(saved.readText())
                    (0 until rows.length()).any { i ->
                        val row = rows.getJSONObject(i)
                        row.getJSONObject("item").getString("id") == item.id &&
                            row.getString("root") == target.absolutePath && row.getBoolean("paused")
                    }
                }.getOrDefault(false)
                if (!correctSavedRoot) Thread.sleep(10)
            }
            assertTrue("Paused saved queue must remember the existing card root", correctSavedRoot)
            DownloadQueue.clear(app, item.key)
        } finally {
            if (previous != null) StoresState.register(previous)
            SessionPrefs.setGameStorage(app, SessionPrefs.GAME_STORAGE_OFF, "")
            StoresState.unfinished = emptyList()
        }
    }

    @Test fun preflightClearRemovesTheRowWithoutRunningItsRetry() {
        val entry = DownloadEntry(Store.AMAZON, "clear-preflight", "Clear")
        DownloadQueue.recordFailed(app, entry, "storage") { fail("Clear must not retry") }
        DownloadQueue.clear(app, entry.key)
        assertNull(DownloadQueue.entry(entry.key))
    }

    @Test fun heldPausedBackendRestoresAndResumesWithoutAPlaceholder() {
        val entry = DownloadEntry(Store.GOG, "restored-paused", "Paused")
        val ran = CountDownLatch(1)
        DownloadQueue.holdPaused(entry.key)
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? {
                    ran.countDown()
                    throw IllegalStateException("restored")
                }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertEquals(DownloadState.PAUSED, DownloadQueue.entry(entry.key)?.state)
        assertEquals(1L, ran.count)
        DownloadQueue.resume(app, entry.key)
        assertTrue(ran.await(5, TimeUnit.SECONDS))
        awaitFailure(entry.key, "restored")
    }
}
