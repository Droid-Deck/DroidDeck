package com.droiddeck.launcher.stores

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CloudSavesMigrationTest {
    private val dir = Files.createTempDirectory("cloud-migrate").toFile()
    private fun entry(bytes: ByteArray) = CloudPlan.Entry(CloudSaves.md5(bytes), null, 1000L)

    private fun nested(name: String, path: String, text: String): File =
        File(dir, "$name/$path").apply { parentFile?.mkdirs(); writeText(text) }

    @Test fun movesOnlyFilesTheOldBaselineOwns() {
        val a = "old save".toByteArray()
        val game = "game wrote this".toByteArray()
        nested("saves", "a.dat", String(a))
        nested("saves", "game.dat", String(game))
        val base = mapOf("saves/a.dat" to entry(a))
        val result = CloudSaves.migrateNested(dir, "saves", base)!!
        assertEquals(1, result.moved)
        assertEquals(0, result.cleaned)
        assertEquals(String(a), File(dir, "a.dat").readText())
        assertEquals(String(game), File(dir, "saves/game.dat").readText())
        assertTrue("saves/a.dat" !in result.baseline)
        assertEquals(entry(a), result.baseline["a.dat"])
    }

    @Test fun anIdenticalLeftoverIsRemovedAndItsBaselineKeyRewritten() {
        val a = "old save".toByteArray()
        File(dir, "a.dat").writeText(String(a))
        nested("saves", "a.dat", String(a))
        val result = CloudSaves.migrateNested(dir, "saves", mapOf("saves/a.dat" to entry(a)))!!
        assertEquals(0, result.moved)
        assertEquals(1, result.cleaned)
        assertFalse(File(dir, "saves").exists())
        assertEquals(entry(a), result.baseline["a.dat"])
        assertTrue("saves/a.dat" !in result.baseline)
    }

    @Test fun aDifferentDestinationKeepsTheGameFileAndTheBaseline() {
        val a = "old save".toByteArray()
        File(dir, "a.dat").writeText("newer game save")
        nested("saves", "a.dat", String(a))
        val base = mapOf("saves/a.dat" to entry(a))
        val result = CloudSaves.migrateNested(dir, "saves", base)!!
        assertEquals(0, result.moved)
        assertEquals(0, result.cleaned)
        assertEquals("newer game save", File(dir, "a.dat").readText())
        assertEquals(String(a), File(dir, "saves/a.dat").readText())
        assertEquals(base, result.baseline)
    }

    @Test fun aSymlinkedAncestorIsNotMigrated() {
        val outside = Files.createTempDirectory("cloud-outside").toFile()
        File(outside, "a.dat").writeText("outside")
        File(dir, "saves").mkdirs()
        Files.createSymbolicLink(File(dir, "saves/link").toPath(), outside.toPath())
        val result = CloudSaves.migrateNested(dir, "saves",
            mapOf("saves/link/a.dat" to entry("outside".toByteArray())))!!
        assertEquals(0, result.moved)
        assertEquals(0, result.cleaned)
        assertTrue(File(outside, "a.dat").isFile)
    }

    @Test fun aLocationNameThatCanLeaveTheFolderIsRefused() {
        nested("saves", "a.dat", "x")
        assertNull(CloudSaves.migrateNested(dir, "../saves", emptyMap()))
        assertNull(CloudSaves.migrateNested(dir, "..", emptyMap()))
        assertNull(CloudSaves.migrateNested(dir, "saves/..", emptyMap()))
    }
}
