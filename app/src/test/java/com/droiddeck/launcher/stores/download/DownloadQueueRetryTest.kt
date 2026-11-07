package com.droiddeck.launcher.stores.download

import android.content.Context
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.session.SessionPrefs
import com.droiddeck.launcher.stores.*
import com.droiddeck.launcher.stores.battlenet.BattleNetInstall
import com.droiddeck.launcher.stores.battlenet.BattleNetInstaller
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.After
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLooper
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

    @Test fun cancellingBeforeTheJobIsPublishedCancelsIt() {
        val entry = DownloadEntry(Store.GOG, "cancel-before-publish", "Cancel before publish")
        val factoryEntered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val job = object : DownloadQueue.DownloadJob {
            override fun run(handle: DownloadQueue.JobHandle): String? = null
            override fun cancel(deleteFiles: Boolean) {
                if (deleteFiles) cancelled.countDown()
            }
        }
        DownloadQueue.enqueue(app, entry) {
            factoryEntered.countDown()
            release.await(5, TimeUnit.SECONDS)
            job
        }
        assertTrue(factoryEntered.await(5, TimeUnit.SECONDS))
        DownloadQueue.pause(entry.key)
        DownloadQueue.cancel(app, entry.key)
        release.countDown()
        assertTrue("cancel must reach the job published after the request", cancelled.await(5, TimeUnit.SECONDS))
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DownloadQueue.entry(entry.key)?.state != DownloadState.CANCELLED && System.nanoTime() < until) {
            Thread.sleep(10)
        }
        assertEquals(DownloadState.CANCELLED, DownloadQueue.entry(entry.key)?.state)
        DownloadQueue.clear(app, entry.key)
    }

    @Test fun aRejectedEnqueueDoesNotRunItsAcceptedCallback() {
        val entry = DownloadEntry(
            Store.BATTLENET, "rejected-enqueue", "Rejected enqueue",
            cleanupKey = "${Store.BATTLENET.id}:rejected-enqueue",
        )
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? {
                    started.countDown()
                    release.await()
                    return null
                }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        var accepted = false
        assertFalse(DownloadQueue.enqueue(app, entry, onAccepted = { accepted = true }) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? = null
                override fun cancel(deleteFiles: Boolean) {}
            }
        })
        assertFalse(accepted)
        release.countDown()
        DownloadQueue.clear(app, entry.key)
    }

    @Test fun aSavedQueueRestoreCarriesOwnershipIntoEnqueue() {
        val root = File(app.filesDir, "restore-root").apply { mkdirs() }
        val key = "battlenet:restore-chain"
        SavedQueue.resetForTest()
        SavedQueue.remember(
            app,
            CatalogItem(
                Store.BATTLENET, "restore-chain", "Restore chain", null,
                extra = mapOf("preexisting" to "0"),
            ),
            root,
        )
        val row = JSONObject().apply {
            put("paused", true)
            put("root", root.absolutePath)
            put("item", JSONObject().apply {
                put("store", Store.BATTLENET.id); put("id", "restore-chain"); put("title", "Restore chain")
                put("extra", JSONObject().put("preexisting", "0"))
                put("owned", true)
            })
        }
        File(app.filesDir, "store-queue.json").writeText(JSONArray().put(row).toString())
        DownloadQueue.holdPaused(key)
        val previous = StoresState.backend(Store.BATTLENET)
        val installed = CountDownLatch(1)
        StoresState.register(object : StoreBackend {
            override val store = Store.BATTLENET
            override fun signIn(context: Context) {}
            override fun signOut(context: Context) {}
            override fun syncLibrary(context: Context, force: Boolean) {}
            override fun loadShelves(context: Context, force: Boolean) {}
            override fun uninstall(context: Context, game: InstalledStoreGame) {}
            override fun install(context: Context, item: CatalogItem, root: File) {
                BattleNetInstall.enqueue(context, item, StoreInstallRoot.folderFor(context, store, item.id, item.title, root))
                installed.countDown()
            }
        })
        try {
            SavedQueue.restore(app)
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (installed.count > 0 && System.nanoTime() < until) {
                ShadowLooper.idleMainLooper()
                Thread.sleep(10)
            }
            assertTrue(installed.await(1, TimeUnit.SECONDS))
            assertEquals("0", SavedQueue.remembered(key)?.extra?.get("preexisting"))
            DownloadQueue.clear(app, key)
        } finally {
            if (previous != null) StoresState.register(previous)
            SavedQueue.resetForTest()
            File(app.filesDir, "store-queue.json").delete()
        }
    }

    @Test fun aRejectedUpdateKeepsTheSavedOwnershipSnapshot() {
        val original = CatalogItem(
            Store.BATTLENET, "rejected-update", "Rejected update", null,
            extra = mapOf("preexisting" to "0"),
        )
        SavedQueue.remember(app, original, null)
        val entry = DownloadEntry(Store.BATTLENET, "rejected-update", "Rejected update")
        val started = CountDownLatch(1)
        val release = CountDownLatch(1)
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? {
                    started.countDown()
                    release.await()
                    return null
                }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        var accepted = false
        assertFalse(DownloadQueue.enqueue(app, entry, onAccepted = {
            accepted = true
            SavedQueue.remember(app, original.copy(extra = mapOf("preexisting" to "1")), null)
        }) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? = null
                override fun cancel(deleteFiles: Boolean) {}
            }
        })
        assertFalse(accepted)
        assertEquals("0", SavedQueue.remembered(entry.key)?.extra?.get("preexisting"))
        release.countDown()
        DownloadQueue.clear(app, entry.key)
    }

    @Test fun aFactorylessFailedBattleNetEntryDoesNotBlockAReplacement() {
        val entry = DownloadEntry(Store.BATTLENET, "factoryless-failure", "Factoryless failure")
        DownloadQueue.recordFailed(app, entry, "failed before a job existed")
        DownloadQueue.clear(app, entry.key)
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? = null
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        assertNotNull("a cleared factoryless failure must not hold a cleanup key", DownloadQueue.entry(entry.key))
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DownloadQueue.entry(entry.key)?.isActive == true && System.nanoTime() < until) Thread.sleep(10)
        DownloadQueue.clear(app, entry.key)
    }

    @Test fun aBattleNetClientDownloadIsNotInTheGameCleanupGuard() {
        val entry = DownloadEntry(Store.BATTLENET, "battlenet-client", "Battle.net")
        DownloadQueue.enqueue(app, entry) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? = null
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        DownloadQueue.cancel(app, entry.key)
        assertFalse(DownloadQueue.isCancelling(entry.key))
        DownloadQueue.clear(app, entry.key)
    }

    @Test fun aPauseCallbackDoesNotReleaseAnotherCleanup() {
        val title = "Pause during cleanup"
        val entry = DownloadEntry(
            Store.BATTLENET, "pause-during-cleanup", title,
            cleanupKey = "${Store.BATTLENET.id}:pause-during-cleanup",
        )
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val job = object : DownloadQueue.DownloadJob {
            override fun run(handle: DownloadQueue.JobHandle): String? = null
            override fun cancel(deleteFiles: Boolean) {
                if (!deleteFiles) return
                cancelled.countDown()
                Thread({
                    release.await()
                    DownloadQueue.cleanupFinished(entry.key)
                }, "test-pause-cleanup").start()
            }
        }
        DownloadQueue.enqueue(app, entry) { job }
        DownloadQueue.cancel(app, entry.key)
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertTrue(DownloadQueue.isCancelling(entry.key))

        val folder = File(LinuxRuntime.rootDir(app), "root/Games/Diablo II Resurrected").apply { mkdirs() }
        val item = CatalogItem(Store.BATTLENET, entry.id, title, null)
        BattleNetInstaller(app, item, folder, false).cancel(false)
        assertTrue("a pause must not release the destructive cleanup guard", DownloadQueue.isCancelling(entry.key))

        release.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DownloadQueue.isCancelling(entry.key) && System.nanoTime() < until) Thread.sleep(10)
        assertFalse(DownloadQueue.isCancelling(entry.key))
        val entryUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DownloadQueue.entry(entry.key)?.state != DownloadState.CANCELLED && System.nanoTime() < entryUntil) {
            Thread.sleep(10)
        }
        DownloadQueue.clear(app, entry.key)
    }

    @Test fun aDoubleClearStartsOneCleanup() {
        val entry = DownloadEntry(
            Store.BATTLENET, "double-clear", "Double clear",
            cleanupKey = "${Store.BATTLENET.id}:double-clear",
        )
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancels = AtomicInteger()
        val job = object : DownloadQueue.DownloadJob {
            override fun run(handle: DownloadQueue.JobHandle): String? = null
            override fun cancel(deleteFiles: Boolean) {
                if (!deleteFiles) return
                cancels.incrementAndGet()
                cancelled.countDown()
                Thread({
                    release.await()
                    DownloadQueue.cleanupFinished(entry.key)
                }, "test-double-clear").start()
            }
        }
        DownloadQueue.enqueue(app, entry) { job }
        val failedUntil = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DownloadQueue.entry(entry.key)?.state != DownloadState.FAILED && System.nanoTime() < failedUntil) {
            Thread.sleep(10)
        }
        DownloadQueue.clear(app, entry.key)
        DownloadQueue.clear(app, entry.key)
        assertTrue(cancelled.await(5, TimeUnit.SECONDS))
        assertEquals(1, cancels.get())
        assertTrue(DownloadQueue.isCancelling(entry.key))
        release.countDown()
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DownloadQueue.isCancelling(entry.key) && System.nanoTime() < until) Thread.sleep(10)
        assertFalse(DownloadQueue.isCancelling(entry.key))
    }

    @Test fun aCancelledBattleNetJobBlocksAReplacementWhileCleanupRuns() {
        val entry = DownloadEntry(
            Store.BATTLENET, "same-folder-race", "Same folder race",
            cleanupKey = "${Store.BATTLENET.id}:same-folder-race",
        )
        val started = CountDownLatch(1)
        val cancelled = CountDownLatch(1)
        val release = CountDownLatch(1)
        val cancels = AtomicInteger()
        val job = object : DownloadQueue.DownloadJob {
            override fun run(handle: DownloadQueue.JobHandle): String? {
                started.countDown()
                return null
            }

            override fun cancel(deleteFiles: Boolean) {
                if (!deleteFiles) return
                cancels.incrementAndGet()
                cancelled.countDown()
                Thread({
                    release.await()
                    DownloadQueue.cleanupFinished(entry.key)
                }, "test-cleanup").start()
            }
        }
        DownloadQueue.enqueue(app, entry) { job }
        assertTrue(started.await(5, TimeUnit.SECONDS))
        try {
            DownloadQueue.cancel(app, entry.key)
            DownloadQueue.cancel(app, entry.key)
            assertTrue(cancelled.await(5, TimeUnit.SECONDS))
            assertEquals(1, cancels.get())
            assertTrue(DownloadQueue.isCancelling(entry.key))
            val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (DownloadQueue.entry(entry.key)?.state != DownloadState.CANCELLED && System.nanoTime() < until) {
                Thread.sleep(10)
            }
            assertEquals(DownloadState.CANCELLED, DownloadQueue.entry(entry.key)?.state)
            DownloadQueue.clear(app, entry.key)
            DownloadQueue.enqueue(app, entry) {
                object : DownloadQueue.DownloadJob {
                    override fun run(handle: DownloadQueue.JobHandle): String? = "replacement"
                    override fun cancel(deleteFiles: Boolean) {}
                }
            }
            assertNull("replacement must not be published during cleanup", DownloadQueue.entry(entry.key))
        } finally {
            release.countDown()
        }
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (DownloadQueue.isCancelling(entry.key) && System.nanoTime() < until) Thread.sleep(10)
        assertFalse(DownloadQueue.isCancelling(entry.key))
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
