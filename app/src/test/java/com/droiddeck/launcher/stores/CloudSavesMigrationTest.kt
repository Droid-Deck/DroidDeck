package com.droiddeck.launcher.stores

import com.droiddeck.launcher.stores.epic.EpicPrefs
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CloudSavesMigrationTest {
    private val dir = Files.createTempDirectory("cloud-migrate").toFile()
    private fun entry(bytes: ByteArray) = CloudPlan.Entry(CloudSaves.md5(bytes), null, 1000L)
    private val app get() = RuntimeEnvironment.getApplication()
    private fun stubCloud() = object : CloudTransport {
        override fun list() = emptyList<CloudFile>()
        override fun get(file: CloudFile): Pair<ByteArray, Long> = throw java.io.IOException("unused in this test")
        override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>) = emptyMap<String, Long>()
    }

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

    private fun epicBaseline(vararg keys: Pair<String, String>): Map<String, CloudPlan.Entry> =
        keys.associate { (k, v) -> k to entry(v.toByteArray()) }

    @Test fun anEpicManifestLayoutIsDeletedFromTheSaveFolderAndTheBaseline() {
        val gameId = "g${System.nanoTime()}"
        nested("manifests", "a.manifest", "m")
        nested("ChunksV4", "ab/c0.chunk", "c")
        File(dir, "slot0.sav").writeText("save")
        val base = epicBaseline("manifests/a.manifest" to "m", "ChunksV4/ab/c0.chunk" to "c", "slot0.sav" to "save")
        EpicPrefs.get(app).edit().putString("cloud_baseline_$gameId", CloudPlan.toJson(base)).apply()
        CloudSaves.migrateEpicManifest(app, Store.EPIC, gameId, dir, stubCloud())
        assertFalse(File(dir, "manifests").exists())
        assertFalse(File(dir, "ChunksV4").exists())
        assertTrue(File(dir, "slot0.sav").isFile)
        val after = CloudSaves.baseline(app, Store.EPIC, gameId)!!
        assertEquals(setOf("slot0.sav"), after.keys)
        assertEquals(entry("save".toByteArray()), after.getValue("slot0.sav"))
        assertEquals(1, EpicPrefs.get(app).getInt("cloud_epic_layout_$gameId", 0))
    }

    @Test fun aFolderThatCannotBeDeletedLeavesTheEpicMigrationForTheNextTry() {
        val gameId = "g${System.nanoTime()}"
        nested("manifests", "a.manifest", "m")
        nested("ChunksV4", "ab/c0.chunk", "c")
        val chunks = File(dir, "ChunksV4")
        val base = epicBaseline("ChunksV4/ab/c0.chunk" to "c", "keep.sav" to "k")
        EpicPrefs.get(app).edit().putString("cloud_baseline_$gameId", CloudPlan.toJson(base)).apply()
        chunks.setWritable(false)
        Assume.assumeFalse(chunks.canWrite())
        try {
            CloudSaves.migrateEpicManifest(app, Store.EPIC, gameId, dir, stubCloud())
            assertEquals(0, EpicPrefs.get(app).getInt("cloud_epic_layout_$gameId", 0))
            assertEquals(base, CloudSaves.baseline(app, Store.EPIC, gameId))
            assertTrue(chunks.isDirectory)
        } finally {
            chunks.setWritable(true)
        }
    }
}
