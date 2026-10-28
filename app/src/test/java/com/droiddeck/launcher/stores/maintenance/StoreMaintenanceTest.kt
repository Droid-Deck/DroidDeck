package com.droiddeck.launcher.stores.maintenance

import android.content.Context
import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class StoreMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()

    private val adapter = object : StoreMaintenanceAdapter {
        override val store = Store.EPIC
        override fun capabilities(game: InstalledStoreGame) = setOf(MaintenanceAction.UPDATE)
        override fun check(context: Context, game: InstalledStoreGame) = MaintenanceCheck(MaintenanceCheckState.CURRENT)
        override fun prepare(context: Context, game: InstalledStoreGame, action: MaintenanceAction) = error("unused")
        override fun enqueue(context: Context, game: InstalledStoreGame, proposal: MaintenanceProposal) = Unit
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
