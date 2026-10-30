package com.droiddeck.launcher.stores.itch

import com.droiddeck.launcher.stores.InstalledStoreGame
import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.maintenance.RecoveryState
import com.droiddeck.launcher.stores.maintenance.StoreMaintenance
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.rules.TemporaryFolder
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

@RunWith(RobolectricTestRunner::class)
class ItchStagingTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun file(root: File, path: String, text: String) = File(root, path).also { it.parentFile?.mkdirs(); it.writeText(text) }

    @Test fun newFilesReplaceOldOnesAndEverythingElseStays() {
        val game = tmp.newFolder("game")
        val staged = tmp.newFolder("staged")
        file(game, "game.exe", "old")
        file(game, "data/level.dat", "old")
        file(game, "saves/slot1.sav", "mine")
        file(game, ".droiddeck-store.json", "record")
        file(staged, "game.exe", "new")
        file(staged, "data/level.dat", "new")
        file(staged, "data/extra.dat", "new")
        file(staged, "saves/default.sav", "new")
        ItchStaging.merge(staged, game)
        assertEquals("new", File(game, "game.exe").readText())
        assertEquals("new", File(game, "data/level.dat").readText())
        assertEquals("new", File(game, "data/extra.dat").readText())
        assertEquals("mine", File(game, "saves/slot1.sav").readText())
        assertEquals("new", File(game, "saves/default.sav").readText())
        assertEquals("record", File(game, ".droiddeck-store.json").readText())
        assertTrue(staged.listFiles().orEmpty().all { it.isDirectory && it.listFiles().orEmpty().isEmpty() })
    }

    @Test fun aFileDirectoryCollisionFailsWithoutDeletingExistingData() {
        val game = tmp.newFolder("game")
        val staged = tmp.newFolder("staged")
        file(game, "a/inner.txt", "old")
        file(game, "b", "old")
        file(staged, "a", "new")
        file(staged, "b/inner.txt", "new")
        assertThrows(IllegalArgumentException::class.java) { ItchStaging.merge(staged, game) }
        assertEquals("old", File(game, "a/inner.txt").readText())
        assertEquals("old", File(game, "b").readText())
    }

    @Test fun deletingFollowsNoLinkOutOfTheTree() {
        val outside = tmp.newFolder("outside")
        file(outside, "keep.txt", "keep")
        val staged = tmp.newFolder("staged")
        file(staged, "own.txt", "x")
        java.nio.file.Files.createSymbolicLink(File(staged, "link").toPath(), outside.toPath())
        ItchStaging.delete(staged)
        assertFalse(staged.exists())
        assertEquals("keep", File(outside, "keep.txt").readText())
    }

    @Test fun aLinkInTheGameIsReplacedNotFollowed() {
        val outside = tmp.newFolder("outside")
        file(outside, "keep.txt", "keep")
        val game = tmp.newFolder("game")
        java.nio.file.Files.createSymbolicLink(File(game, "data").toPath(), outside.toPath())
        val staged = tmp.newFolder("staged")
        file(staged, "data/level.dat", "new")
        ItchStaging.merge(staged, game)
        assertEquals("keep", File(outside, "keep.txt").readText())
        assertFalse(File(outside, "level.dat").exists())
        assertEquals("new", File(game, "data/level.dat").readText())
    }

    @Test fun theInstallRecordIsNeverReplacedByTheArchive() {
        val game = tmp.newFolder("game")
        val staged = tmp.newFolder("staged")
        file(game, ".droiddeck-store.json", "record")
        file(staged, ".droiddeck-store.json", "forged")
        file(staged, "sub/.droiddeck-store.json", "nested")
        ItchStaging.merge(staged, game)
        assertEquals("record", File(game, ".droiddeck-store.json").readText())
        assertEquals("nested", File(game, "sub/.droiddeck-store.json").readText())
    }

    @Test fun managedCommitRepairsCorruptionRemovesOnlyUntouchedObsoleteFilesAndKeepsSaves() {
        val game = tmp.newFolder("managed-game")
        val staged = tmp.newFolder("managed-stage")
        file(game, "bin/game.exe", "corrupted")
        file(game, "data/removed.dat", "publisher-old")
        file(game, "saves/slot1.sav", "my-save")
        file(game, "data/user.ini", "my-config")
        file(staged, "bin/game.exe", "publisher-new")
        file(staged, "data/new.dat", "new")
        val previous = ItchStaging.inventory(tmp.newFolder("old-inventory")).toMutableMap()
        val oldFiles = tmp.newFolder("old-publisher")
        file(oldFiles, "bin/game.exe", "publisher-prior")
        file(oldFiles, "data/removed.dat", "publisher-old")
        previous.putAll(ItchStaging.inventory(oldFiles))
        val next = ItchStaging.commit(staged, game, previous)
        assertEquals("publisher-new", File(game, "bin/game.exe").readText())
        assertFalse(File(game, "data/removed.dat").exists())
        assertEquals("my-save", File(game, "saves/slot1.sav").readText())
        assertEquals("my-config", File(game, "data/user.ini").readText())
        assertEquals("new", File(game, "data/new.dat").readText())
        ItchStaging.writeInventory(game, next)
        assertEquals(next, ItchStaging.readInventory(game))
    }

    @Test fun interruptedCommitResumesFromPinnedInventoryAndCompletesObsoleteCleanup() {
        val game = tmp.newFolder("resume-game")
        val staged = tmp.newFolder("resume-stage")
        file(game, "old.txt", "old")
        file(staged, "a.txt", "new-a")
        file(staged, "b.txt", "new-b")
        val previous = ItchStaging.inventory(game)
        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, previous, afterEntry = { if (it == 1) error("simulated interruption") })
        }
        assertTrue(ItchStaging.recover(game))
        val next = ItchStaging.commit(staged, game, previous)
        assertEquals("new-a", File(game, "a.txt").readText())
        assertEquals("new-b", File(game, "b.txt").readText())
        assertFalse(File(game, "old.txt").exists())
        assertEquals(setOf("a.txt", "b.txt"), next.keys)
        assertFalse(ItchStaging.recover(game))
    }

    @Test fun progressIsAppendedAndTornTrailingRecordIsDiscardedBeforeResume() {
        val game = tmp.newFolder("progress-game")
        val staged = tmp.newFolder("progress-stage")
        file(staged, "a.txt", "a")
        file(staged, "b.txt", "b")
        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, emptyMap(), afterEntry = { if (it == 1) error("simulated interruption") })
        }
        val metadata = File(game, ".droiddeck-maintenance")
        val plan = File(metadata, "itch-commit.json")
        val progress = File(metadata, "itch-commit-progress.jsonl")
        val planBefore = plan.readText()
        assertFalse(JSONObject(planBefore).has("done"))
        assertTrue(progress.readText().contains("\"path\""))
        progress.appendText("{\"path\":\"torn")

        assertTrue(ItchStaging.recover(game))
        assertFalse(progress.readText().contains("torn"))
        ItchStaging.commit(staged, game, emptyMap())
        assertEquals("a", File(game, "a.txt").readText())
        assertEquals("b", File(game, "b.txt").readText())
        assertFalse(plan.exists())
        assertFalse(progress.exists())
    }

    @Test fun completeMalformedProgressRecordFailsClosed() {
        val game = tmp.newFolder("bad-progress-game")
        val staged = tmp.newFolder("bad-progress-stage")
        file(staged, "a.txt", "a")
        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, emptyMap(), afterEntry = { error("simulated interruption") })
        }
        val progress = File(game, ".droiddeck-maintenance/itch-commit-progress.jsonl")
        progress.appendText("{bad}\n")
        assertThrows(IllegalStateException::class.java) { ItchStaging.recover(game) }
    }

    @Test fun legacyDoneJournalMigratesToAppendOnlyProgress() {
        val game = tmp.newFolder("legacy-journal-game")
        val staged = tmp.newFolder("legacy-journal-stage")
        file(staged, "a.txt", "a")
        file(staged, "b.txt", "b")
        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, emptyMap(), afterEntry = { if (it == 1) error("simulated interruption") })
        }
        val metadata = File(game, ".droiddeck-maintenance")
        val journal = File(metadata, "itch-commit.json")
        val progress = File(metadata, "itch-commit-progress.jsonl")
        val old = JSONObject(journal.readText())
        val done = JSONObject().put("a.txt", true)
        journal.writeText(JSONObject().put("schema", 1).put("next", old.getJSONObject("next"))
            .put("obsolete", old.getJSONObject("obsolete")).put("done", done).toString())
        progress.delete()

        ItchStaging.commit(staged, game, emptyMap())
        assertEquals("a", File(game, "a.txt").readText())
        assertEquals("b", File(game, "b.txt").readText())
        assertFalse(journal.exists())
        assertFalse(progress.exists())
    }

    @Test fun backupMetadataUsesCanonicalInstallWhenParentIsSymlinked() {
        val actualParent = tmp.newFolder("actual-install-parent")
        val aliasParent = File(tmp.root, "linked-install-parent")
        java.nio.file.Files.createSymbolicLink(aliasParent.toPath(), actualParent.toPath())
        val game = File(aliasParent, "game").also { assertTrue(it.mkdirs()) }
        val staged = tmp.newFolder("canonical-stage")
        file(game, "bin/game.dat", "old")
        file(staged, "bin/game.dat", "new")
        val previous = ItchStaging.inventory(game)

        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, previous, afterEntry = { error("simulated interruption") })
        }
        val canonicalGame = File(actualParent, "game")
        assertTrue(File(canonicalGame, ".droiddeck-maintenance/itch-backups/bin/game.dat").isFile)
        assertTrue(ItchStaging.recover(game))
        ItchStaging.commit(staged, game, previous)
        assertEquals("new", File(canonicalGame, "bin/game.dat").readText())
    }

    @Test fun failedInventoryPublicationKeepsCommitJournalUntilResume() {
        val game = tmp.newFolder("inventory-publication-game")
        val staged = tmp.newFolder("inventory-publication-stage")
        file(staged, "game.exe", "publisher")
        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, emptyMap(), onCommitted = { error("simulated inventory failure") })
        }
        assertTrue(ItchStaging.recover(game))
        val next = ItchStaging.commit(staged, game, emptyMap(), onCommitted = { ItchStaging.writeInventory(game, it) })
        assertEquals("publisher", File(game, "game.exe").readText())
        assertEquals(next, ItchStaging.readInventory(game))
        assertFalse(ItchStaging.recover(game))
    }

    @Test fun unmanagedCollisionPreservesUserFile() {
        val game = tmp.newFolder("collision-game")
        val staged = tmp.newFolder("collision-stage")
        file(game, "saves/slot1.sav", "my-save")
        file(staged, "saves/slot1.sav", "archive-file")
        assertThrows(IllegalArgumentException::class.java) { ItchStaging.commit(staged, game, emptyMap()) }
        assertEquals("my-save", File(game, "saves/slot1.sav").readText())
        assertFalse(ItchStaging.recover(game))
    }

    @Test fun legacyInventoryBootstrapRequiresAnAbsentInventoryAndExactTarget() {
        val targetStage = tmp.newFolder("legacy-target")
        file(targetStage, "game.exe", "publisher build")
        file(targetStage, "notes.txt", "publisher notes")
        val target = ItchStaging.inventory(targetStage)

        assertEquals(target, ItchStaging.previousInventory(null, target, exactLegacyTarget = true, selectedExe = "game.exe"))
        assertEquals(emptyMap<String, String>(), ItchStaging.previousInventory(null, target, exactLegacyTarget = false, selectedExe = "game.exe"))
        assertEquals(emptyMap<String, String>(), ItchStaging.previousInventory(emptyMap(), target, exactLegacyTarget = true, selectedExe = "game.exe"))
        assertEquals(mapOf("notes.txt" to target.getValue("notes.txt")),
            ItchStaging.previousInventory(target, target - "game.exe", exactLegacyTarget = true, selectedExe = "game.exe"))

        val game = tmp.newFolder("legacy-game")
        file(game, "game.exe", "corrupted publisher build")
        file(game, "user-notes.txt", "keep")
        ItchStaging.commit(targetStage, game,
            ItchStaging.previousInventory(null, target, exactLegacyTarget = true, selectedExe = "game.exe"))
        assertEquals("publisher build", File(game, "game.exe").readText())
        assertEquals("keep", File(game, "user-notes.txt").readText())

        val unknownBuild = tmp.newFolder("unknown-legacy-game")
        val unknownTargetStage = tmp.newFolder("unknown-legacy-target")
        file(unknownTargetStage, "game.exe", "publisher build")
        file(unknownTargetStage, "notes.txt", "publisher notes")
        file(unknownBuild, "game.exe", "unknown installed build")
        assertThrows(IllegalArgumentException::class.java) {
            ItchStaging.commit(unknownTargetStage, unknownBuild,
                ItchStaging.previousInventory(null, target, exactLegacyTarget = false, selectedExe = "game.exe"))
        }
        assertEquals("unknown installed build", File(unknownBuild, "game.exe").readText())
        assertFalse(ItchStaging.recover(unknownBuild))

        val inventoryState = tmp.newFolder("inventory-state")
        assertNull(ItchStaging.readInventoryIfPresent(inventoryState))
        ItchStaging.writeInventory(inventoryState, emptyMap())
        assertEquals(emptyMap<String, String>(), ItchStaging.readInventoryIfPresent(inventoryState))
    }

    @Test fun publisherUserDataPlaceholdersAndEmptyDirectoriesAreInstalledAndManaged() {
        val game = tmp.newFolder("publisher-user-data-game")
        val staged = tmp.newFolder("publisher-user-data-stage")
        file(staged, "profiles/default/config.ini", "publisher defaults")
        file(staged, "saves/default.sav", "")
        File(staged, "data/required-empty").mkdirs()

        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, emptyMap(), afterEntry = { if (it == 2) error("simulated interruption") })
        }
        assertTrue(ItchStaging.recover(game))
        val next = ItchStaging.commit(staged, game, emptyMap())

        assertEquals("publisher defaults", File(game, "profiles/default/config.ini").readText())
        assertTrue(File(game, "saves/default.sav").isFile)
        assertEquals(0L, File(game, "saves/default.sav").length())
        assertTrue(File(game, "data/required-empty").isDirectory)
        assertTrue("profiles/default/config.ini" in next)
        assertTrue("saves/default.sav" in next)
        assertEquals("<directory>", next["data/required-empty/"])
        ItchStaging.writeInventory(game, next)
        assertEquals(next, ItchStaging.readInventory(game))
        assertFalse(ItchStaging.recover(game))
    }

    @Test fun locallyModifiedManagedProfileIsPreservedByRefusingReplacement() {
        val game = tmp.newFolder("modified-save-game")
        val oldPublisher = tmp.newFolder("modified-save-old")
        val staged = tmp.newFolder("modified-save-stage")
        file(oldPublisher, "profiles/settings.ini", "publisher defaults")
        file(game, "profiles/settings.ini", "user settings")
        file(staged, "profiles/settings.ini", "new publisher defaults")
        val previous = ItchStaging.inventory(oldPublisher)

        assertThrows(IllegalArgumentException::class.java) { ItchStaging.commit(staged, game, previous) }

        assertEquals("user settings", File(game, "profiles/settings.ini").readText())
    }

    @Test fun extractedPayloadAndBackupExtraSpaceAreAddedWithoutOverflow() {
        val stage = tmp.newFolder("space-stage")
        file(stage, "profiles/default.ini", "1234567890")
        file(stage, "saves/default.sav", "")
        assertEquals(10L, ItchStaging.regularFileBytes(stage))
        assertEquals(38L, ItchStaging.additionalBytesRequired(10L, 28L))
        assertEquals(Long.MAX_VALUE, ItchStaging.additionalBytesRequired(Long.MAX_VALUE - 1, 2))
    }

    @Test fun aDoneJournalEntryIsRehashedBeforeResumeSkipsIt() {
        val game = tmp.newFolder("corrupt-resume-game")
        val staged = tmp.newFolder("corrupt-resume-stage")
        file(game, "old.txt", "old")
        file(staged, "a.txt", "new-a")
        file(staged, "b.txt", "new-b")
        val previous = ItchStaging.inventory(game)
        assertThrows(IllegalStateException::class.java) {
            ItchStaging.commit(staged, game, previous, afterEntry = { if (it == 2) error("simulated interruption") })
        }
        file(game, "a.txt", "tampered")
        file(staged, "a.txt", "new-a")
        ItchStaging.commit(staged, game, previous)
        assertEquals("new-a", File(game, "a.txt").readText())
        assertEquals("new-b", File(game, "b.txt").readText())
    }

    @Test fun corruptOrSymlinkedCommitJournalsFailClosed() {
        val game = tmp.newFolder("journal-game")
        val staged = tmp.newFolder("journal-stage")
        val metadata = File(game, ".droiddeck-maintenance").also { it.mkdirs() }
        file(game, "user.txt", "keep")
        file(staged, "user.txt", "publisher")
        val journal = File(metadata, "itch-commit.json")
        journal.writeText("{")
        assertThrows(IllegalStateException::class.java) { ItchStaging.commit(staged, game, emptyMap()) }
        assertThrows(IllegalStateException::class.java) { ItchStaging.recover(game) }
        assertEquals("keep", File(game, "user.txt").readText())
        journal.delete()
        val outside = file(tmp.newFolder("journal-outside"), "outside.json", "{}")
        java.nio.file.Files.createSymbolicLink(journal.toPath(), outside.toPath())
        assertThrows(IllegalStateException::class.java) { ItchStaging.commit(staged, game, emptyMap()) }
        assertThrows(IllegalStateException::class.java) { ItchStaging.recover(game) }
        assertEquals("{}", outside.readText())
        assertEquals("keep", File(game, "user.txt").readText())
        journal.delete()
        metadata.delete()
        val outsideMetadata = tmp.newFolder("outside-metadata")
        file(outsideMetadata, "itch-commit.json", "{}")
        java.nio.file.Files.createSymbolicLink(metadata.toPath(), outsideMetadata.toPath())
        assertThrows(IllegalArgumentException::class.java) { ItchStaging.recover(game) }
        assertEquals("{}", File(outsideMetadata, "itch-commit.json").readText())
    }

    @Test fun corruptManagedInventoryFailsClosed() {
        val game = tmp.newFolder("inventory-game")
        val metadata = File(game, ".droiddeck-maintenance").also { it.mkdirs() }
        file(game, "publisher.txt", "keep")
        File(metadata, "itch-inventory.json").writeText("{\"../outside.txt\":\"bad\"}")
        assertThrows(IllegalArgumentException::class.java) { ItchStaging.readInventory(game) }
        assertEquals("keep", File(game, "publisher.txt").readText())
    }

    @Test fun corruptInventoryMakesApplyingRecoveryNeedAttention() {
        val folder = tmp.newFolder("corrupt-recovery-inventory").canonicalFile
        val sidecar = StoreGameSidecar(Store.ITCH, "game", "Example", "game.exe")
        sidecar.write(folder)
        val game = InstalledStoreGame(sidecar, folder)
        StoreMaintenance.register(ItchMaintenanceAdapter)
        val operation = StoreMaintenance.operationFile(folder)
        operation.parentFile.mkdirs()
        operation.writeText(JSONObject().put("schema", 1).put("store", Store.ITCH.id)
            .put("id", sidecar.id).put("folder", folder.path).put("phase", "applying").toString())
        File(operation.parentFile, "itch-inventory.json").writeText("{")

        assertEquals(RecoveryState.NEEDS_ATTENTION,
            StoreMaintenance.recovery(RuntimeEnvironment.getApplication(), game).state)
    }
}
