package com.droiddeck.launcher.stores.itch

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

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

    @Test fun aFileReplacingADirectoryAndTheOtherWayRound() {
        val game = tmp.newFolder("game")
        val staged = tmp.newFolder("staged")
        file(game, "a/inner.txt", "old")
        file(game, "b", "old")
        file(staged, "a", "new")
        file(staged, "b/inner.txt", "new")
        ItchStaging.merge(staged, game)
        assertTrue(File(game, "a").isFile)
        assertEquals("new", File(game, "a").readText())
        assertEquals("new", File(game, "b/inner.txt").readText())
        assertFalse(File(game, "a/inner.txt").exists())
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
}
