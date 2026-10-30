package com.droiddeck.launcher.stores.maintenance

import android.content.Context
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoresState
import com.droiddeck.launcher.stores.download.DownloadEntry
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import com.droiddeck.launcher.stores.download.SavedQueue
import org.json.JSONObject
import org.json.JSONArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()

    private val adapter = object : StoreMaintenanceAdapter {
        override val store = Store.EPIC
        override fun capabilities(game: InstalledStoreGame) = setOf(MaintenanceAction.UPDATE)
        override fun check(context: Context, game: InstalledStoreGame) = MaintenanceCheck(MaintenanceCheckState.CURRENT)
        override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction) = error("unused")
        override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) = true
        override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) = Unit
    }

    @Test fun operationPinsFolderTargetAndFullSidecarBeforeApply() {
        val app = RuntimeEnvironment.getApplication()
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "app", "Example", "bin/game.exe", args = listOf("--user-value"),
            env = mapOf("CUSTOM_SETTING" to "kept"), installedAt = 1234L, extra = mapOf("channel" to "test"))
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        StoreMaintenance.register(adapter)

        StoreMaintenance.enqueue(app, game, MaintenanceProposal(folder, sidecar, "version 2", MaintenanceAction.UPDATE,
            downloadBytes = 42, diskBytes = 84, target = JSONObject().put("schema", 1).put("build", "2")))

        val journal = StoreMaintenance.readOperation(folder)!!
        assertEquals(folder.path, journal.getString("folder"))
        assertEquals("app", journal.getString("id"))
        assertEquals("queued", journal.getString("phase"))
        assertEquals("2", journal.getJSONObject("target").getString("build"))
        val before = StoreGameSidecar.parse(journal.getJSONObject("before").toString())!!
        assertEquals(sidecar.args, before.args)
        assertEquals(sidecar.env, before.env)
        assertEquals(sidecar.installedAt, before.installedAt)
        assertEquals(sidecar.extra, before.extra)
    }

    @Test fun failedApplyLeavesLaunchExcludedRecoveryStateAndJournal() {
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "app", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        StoreMaintenance.register(adapter)
        StoreMaintenance.enqueue(RuntimeEnvironment.getApplication(), game,
            MaintenanceProposal(folder, sidecar, "version 2", MaintenanceAction.UPDATE, target = JSONObject().put("schema", 1)))

        runCatching {
            StoreMaintenance.withApplyLease(game) {
                error("simulated interrupted apply")
            }
        }

        assertEquals(StoreGameSidecar.STATE_UPDATING, StoreGameSidecar.read(folder)!!.state)
        assertEquals("applying", StoreMaintenance.readOperation(folder)!!.getString("phase"))
        assertEquals(RecoveryState.NEEDS_ATTENTION, StoreMaintenance.recovery(RuntimeEnvironment.getApplication(), game).state)
        assertTrue(StoreMaintenance.launchExcluded(game))
        assertTrue(StoreMaintenance.operationFile(folder).isFile)
    }

    @Test fun queuedOperationDoesNotMarkTheInstallAsApplying() {
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "app", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        StoreMaintenance.register(adapter)
        StoreMaintenance.enqueue(RuntimeEnvironment.getApplication(), game,
            MaintenanceProposal(folder, sidecar, "version 2", MaintenanceAction.UPDATE, target = JSONObject().put("schema", 1)))

        assertFalse(StoreMaintenance.launchExcluded(game))
        assertEquals(RecoveryState.RESUMABLE, StoreMaintenance.recovery(RuntimeEnvironment.getApplication(), game).state)
    }

    @Test fun failedQueueSubmissionClearsOnlyItsNewQueuedJournalAndRethrowsOriginalError() {
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "enqueue-failure", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        val failure = IllegalStateException("queue rejected")
        StoreMaintenance.register(object : StoreMaintenanceAdapter {
            override val store = Store.EPIC
            override fun capabilities(game: InstalledStoreGame) = setOf(MaintenanceAction.UPDATE)
            override fun check(context: Context, game: InstalledStoreGame) = MaintenanceCheck()
            override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction) = error("unused")
            override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal): Boolean { throw failure }
            override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) = Unit
        })
        val proposal = MaintenanceProposal(folder, sidecar, "target", MaintenanceAction.UPDATE,
            target = JSONObject().put("schema", 1).put("build", "target"))

        val thrown = runCatching { StoreMaintenance.enqueue(RuntimeEnvironment.getApplication(), game, proposal) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertNull(StoreMaintenance.readOperation(folder))
    }

    @Test fun failedQueueSubmissionPreservesJournalThatHasAdvancedFromQueued() {
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "enqueue-advanced", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        val failure = IllegalStateException("queue response lost")
        StoreMaintenance.register(object : StoreMaintenanceAdapter {
            override val store = Store.EPIC
            override fun capabilities(game: InstalledStoreGame) = setOf(MaintenanceAction.UPDATE)
            override fun check(context: Context, game: InstalledStoreGame) = MaintenanceCheck()
            override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction) = error("unused")
            override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal): Boolean {
                val file = StoreMaintenance.operationFile(game.folder)
                file.writeText(JSONObject(file.readText()).put("phase", "download").toString())
                throw failure
            }
            override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) = Unit
        })
        val proposal = MaintenanceProposal(folder, sidecar, "target", MaintenanceAction.UPDATE,
            target = JSONObject().put("schema", 1).put("build", "target"))

        val thrown = runCatching { StoreMaintenance.enqueue(RuntimeEnvironment.getApplication(), game, proposal) }.exceptionOrNull()

        assertSame(failure, thrown)
        assertEquals("download", StoreMaintenance.readOperation(folder)!!.getString("phase"))
    }

    @Test fun queueRaceRejectionClearsOnlyMaintenanceJournalAndKeepsExistingSavedJob() {
        val app = RuntimeEnvironment.getApplication()
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "queue-race", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        val existing = CatalogItem(Store.EPIC, sidecar.id, "Existing job", imageUrl = null, owned = true)
        val maintenance = CatalogItem(Store.EPIC, sidecar.id, "Maintenance job", imageUrl = null, owned = true)
        val key = DownloadEntry(Store.EPIC, sidecar.id, existing.title).key
        fun job() = object : DownloadQueue.DownloadJob {
            override fun run(handle: DownloadQueue.JobHandle): String? = null
            override fun cancel(deleteFiles: Boolean) = Unit
        }
        DownloadQueue.holdPaused(key)
        StoreMaintenance.register(object : StoreMaintenanceAdapter {
            override val store = Store.EPIC
            override fun capabilities(game: InstalledStoreGame) = setOf(MaintenanceAction.UPDATE)
            override fun check(context: Context, game: InstalledStoreGame) = MaintenanceCheck()
            override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction) = error("unused")
            override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal): Boolean {
                SavedQueue.remember(context, existing, null)
                DownloadQueue.enqueue(context, DownloadEntry(Store.EPIC, sidecar.id, existing.title), ::job)
                return DownloadQueue.enqueueIfIdle(context, DownloadEntry(Store.EPIC, sidecar.id, maintenance.title), onAccepted = {
                    SavedQueue.remember(context, maintenance, null)
                }, factory = ::job)
            }
            override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) = Unit
        })
        val proposal = MaintenanceProposal(folder, sidecar, "target", MaintenanceAction.UPDATE,
            target = JSONObject().put("schema", 1).put("build", "target"))

        assertTrue(runCatching { StoreMaintenance.enqueue(app, game, proposal) }.isFailure)

        assertNull(StoreMaintenance.readOperation(folder))
        assertEquals(DownloadState.PAUSED, DownloadQueue.entry(key)?.state)
        val saved = File(app.filesDir, "store-queue.json")
        val until = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        var existingSaved = false
        while (!existingSaved && System.nanoTime() < until) {
            existingSaved = runCatching {
                val rows = JSONArray(saved.readText())
                (0 until rows.length()).any { i -> rows.getJSONObject(i).getJSONObject("item").getString("title") == existing.title }
            }.getOrDefault(false)
            if (!existingSaved) Thread.sleep(10)
        }
        assertTrue("rejected maintenance must not replace the active queue's saved item", existingSaved)
        DownloadQueue.clear(app, key)
    }

    @Test fun uninstallAdmissionAndMaintenanceAdmissionCannotOverlapOrRecreateADeletedInstall() {
        val app = RuntimeEnvironment.getApplication()
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "uninstall-race", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        val proposal = MaintenanceProposal(folder, sidecar, "target", MaintenanceAction.UPDATE,
            target = JSONObject().put("schema", 1).put("build", "target"))
        StoreMaintenance.register(adapter)

        assertTrue(StoreMaintenance.beginUninstall(game))
        assertTrue(game.key in StoresState.uninstalling)
        val prepareFailure = runCatching { StoreMaintenance.prepare(app, game, MaintenanceAction.UPDATE) }.exceptionOrNull()
        assertTrue(prepareFailure?.message.orEmpty().contains("being uninstalled"))
        assertTrue(runCatching { StoreMaintenance.enqueue(app, game, proposal) }.isFailure)
        assertNull(StoreMaintenance.readOperation(folder))

        StoresState.uninstalling.remove(game.key)
        folder.deleteRecursively()
        assertTrue(runCatching { StoreMaintenance.enqueue(app, game, proposal) }.isFailure)
        assertFalse("stale proposals must not recreate an uninstalled game folder", folder.exists())
    }

    @Test fun queuedMaintenanceBlocksUninstallAdmission() {
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "maintenance-before-uninstall", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        StoreMaintenance.register(adapter)
        StoreMaintenance.enqueue(RuntimeEnvironment.getApplication(), game,
            MaintenanceProposal(folder, sidecar, "target", MaintenanceAction.UPDATE, target = JSONObject().put("schema", 1)))

        assertFalse(StoreMaintenance.beginUninstall(game))
        assertFalse(game.key in StoresState.uninstalling)
        assertEquals("queued", StoreMaintenance.readOperation(folder)!!.getString("phase"))
    }

    @Test fun inFlightMaintenancePreparationBlocksUninstallAdmission() {
        val app = RuntimeEnvironment.getApplication()
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "prepare-uninstall-race", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        val proposal = MaintenanceProposal(folder, sidecar, "target", MaintenanceAction.UPDATE,
            target = JSONObject().put("schema", 1))
        val enteredPrepare = CountDownLatch(1)
        val finishPrepare = CountDownLatch(1)
        val failure = AtomicReference<Throwable?>()
        StoreMaintenance.register(object : StoreMaintenanceAdapter {
            override val store = Store.EPIC
            override fun capabilities(game: InstalledStoreGame) = setOf(MaintenanceAction.UPDATE)
            override fun check(context: Context, game: InstalledStoreGame) = MaintenanceCheck()
            override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction): MaintenanceProposal {
                enteredPrepare.countDown()
                check(finishPrepare.await(5, TimeUnit.SECONDS))
                return proposal
            }
            override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) = true
            override fun resume(context: Context, game: InstalledStoreGame, operation: JSONObject) = Unit
        })
        val worker = Thread({ runCatching { StoreMaintenance.prepare(app, game, MaintenanceAction.UPDATE) }
            .onFailure(failure::set) }, "test-maintenance-prepare")
        worker.start()

        assertTrue(enteredPrepare.await(5, TimeUnit.SECONDS))
        try {
            assertFalse(StoreMaintenance.beginUninstall(game))
            assertFalse(game.key in StoresState.uninstalling)
        } finally {
            finishPrepare.countDown()
        }
        worker.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse("maintenance preparation should finish", worker.isAlive)
        assertNull(failure.get())
        assertTrue(StoreMaintenance.beginUninstall(game))
        StoresState.uninstalling.remove(game.key)
    }

    @Test fun applyLeaseIsExclusiveAndMarksTheJournalBeforeMutation() {
        val folder = temporary.newFolder().canonicalFile
        val sidecar = StoreGameSidecar(Store.EPIC, "app", "Example", "bin/game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        StoreMaintenance.register(adapter)
        StoreMaintenance.enqueue(RuntimeEnvironment.getApplication(), game,
            MaintenanceProposal(folder, sidecar, "version 2", MaintenanceAction.UPDATE, target = JSONObject().put("schema", 1)))

        val lease = StoreMaintenance.beginApplyLease(game)
        try {
            assertEquals("applying", StoreMaintenance.readOperation(folder)!!.getString("phase"))
            assertEquals(StoreGameSidecar.STATE_UPDATING, StoreGameSidecar.read(folder)!!.state)
            assertTrue(runCatching { StoreMaintenance.beginApplyLease(game) }.isFailure)
        } finally {
            lease.close()
        }
    }

    @Test fun unsupportedOrMismatchedJournalBlocksLaunch() {
        val folder = temporary.newFolder().canonicalFile
        val game = InstalledStoreGame(StoreGameSidecar(Store.EPIC, "app", "Example", "bin/game.exe"), folder)
        File(folder, ".droiddeck-maintenance").mkdirs()
        StoreMaintenance.operationFile(folder).writeText(JSONObject().put("schema", 9).put("folder", folder.path).put("phase", "download").toString())

        assertTrue(StoreMaintenance.launchExcluded(game))
        assertTrue(runCatching { StoreMaintenance.readOperation(folder) }.isFailure)
        assertEquals(RecoveryState.NEEDS_ATTENTION, StoreMaintenance.recovery(RuntimeEnvironment.getApplication(), game).state)
    }
}
