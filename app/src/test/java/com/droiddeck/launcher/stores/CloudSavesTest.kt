package com.droiddeck.launcher.stores

import java.io.File
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/** A sync against a cloud held in memory: the baseline gate, conflicts, backups and the no-prefix deferral. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class CloudSavesTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private class Memory : CloudTransport {
        val files = HashMap<String, Pair<ByteArray, Long>>()
        val puts = ArrayList<String>()
        override fun list() = files.map { (k, v) -> CloudFile(k, v.second, CloudSaves.md5(v.first)) }
        override fun get(file: CloudFile) = files.getValue(file.name)
        override fun put(files: Map<String, ByteArray>): Map<String, Long> =
            files.mapValues { (k, v) -> puts += k; this.files[k] = v to 99_000L; 99_000L }
    }

    private fun dir() = File(app.filesDir, "saves-${System.nanoTime()}").apply { mkdirs() }
    private val id get() = "g${System.nanoTime()}"

    @Test fun anUploadBeforeAnyDownloadIsRefused() {
        val dir = dir(); File(dir, "slot0.sav").writeText("fresh profile")
        val cloud = Memory().apply { files["slot0.sav"] = "real save".toByteArray() to 1000L }
        val r = CloudSaves.upWith(app, Store.GOG, id, dir, cloud, force = false)
        assertEquals("skipped", r.result); assertEquals("no-baseline", r.reason)
        assertArrayEquals("real save".toByteArray(), cloud.files.getValue("slot0.sav").first)
    }

    @Test fun afterADownloadALocalChangeGoesUpAndTheCloudCopyIsKeptFirst() {
        val game = id; val dir = dir()
        val cloud = Memory().apply { files["slot0.sav"] = "v1".toByteArray() to 1000L }
        assertEquals("ok", CloudSaves.downWith(app, Store.GOG, game, dir, cloud, force = false).result)
        assertEquals("v1", File(dir, "slot0.sav").readText())
        File(dir, "slot0.sav").writeText("v2 played")
        val r = CloudSaves.upWith(app, Store.GOG, game, dir, cloud, force = false)
        assertEquals(1, r.files)
        assertEquals("v2 played", String(cloud.files.getValue("slot0.sav").first))
        val kept = File(app.filesDir, "stores/cloud-backups/gog-$game").listFiles { f -> f.name.startsWith("cloud-") }!!.single()
        assertEquals("v1", File(kept, "slot0.sav").readText())
    }

    @Test fun aFileChangedOnBothSidesIsLeftForTheUser() {
        val game = id; val dir = dir()
        val cloud = Memory().apply { files["slot0.sav"] = "v1".toByteArray() to 1000L }
        CloudSaves.downWith(app, Store.GOG, game, dir, cloud, force = false)
        File(dir, "slot0.sav").writeText("local v2")
        cloud.files["slot0.sav"] = "cloud v2".toByteArray() to 50_000L
        val up = CloudSaves.upWith(app, Store.GOG, game, dir, cloud, force = false)
        assertEquals(1, up.conflicts)
        assertEquals("cloud v2", String(cloud.files.getValue("slot0.sav").first))
        assertEquals(listOf("slot0.sav"), CloudSaves.conflicts(app, Store.GOG, game))
        // Keep local resolves it.
        CloudSaves.upWith(app, Store.GOG, game, dir, cloud, force = true)
        assertEquals("local v2", String(cloud.files.getValue("slot0.sav").first))
        assertTrue(CloudSaves.conflicts(app, Store.GOG, game).isEmpty())
    }

    @Test fun aDirtyMarkStaysUntilAnUploadGoesThroughOrIsSkippedForGood() {
        val gameId = "1207664643"
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.internalRoot(app), Store.GOG), "ELDERBORN-mark").apply { mkdirs() }
        File(folder, "ELDERBORN.exe").writeText("MZ")
        StoreGameSidecar(Store.GOG, gameId, "ELDERBORN", "ELDERBORN.exe").write(folder)
        // A save location the store gave, but no prefix yet: the upload cannot run, the mark stays.
        com.droiddeck.launcher.stores.gog.GogPrefs.get(app).edit().putString("cloud_template_$gameId", "<?SAVED_GAMES?>/ELDERBORN").apply()
        CloudSaves.markDirty(app, Store.GOG, gameId)
        assertTrue(CloudSaves.dirty(app).contains(Store.GOG to gameId))
        val first = CloudSaves.uploadDirty(app, Store.GOG, gameId, "session-end")!!
        assertEquals("no-prefix", first.reason)
        assertTrue(CloudSaves.dirty(app).contains(Store.GOG to gameId))
        // Recovery waits while a session runs.
        CloudSaves.uploadAllDirty(app, "recovery", running = true)
        assertTrue(CloudSaves.dirty(app).contains(Store.GOG to gameId))
        // The store has no cloud saves for it: a final skip, the mark goes; a second trigger finds nothing.
        com.droiddeck.launcher.stores.gog.GogPrefs.get(app).edit().putString("cloud_template_$gameId", "").apply()
        assertEquals("no-cloud-saves", CloudSaves.uploadDirty(app, Store.GOG, gameId, "exit")!!.reason)
        assertTrue(CloudSaves.dirty(app).none { it.second == gameId })
        assertEquals(null, CloudSaves.uploadDirty(app, Store.GOG, gameId, "session-end"))
    }

    @Test fun aFirstLaunchWithoutAPrefixIsDeferredNotBlocked() {
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.internalRoot(app), Store.GOG), "ELDERBORN").apply { mkdirs() }
        File(folder, "ELDERBORN.exe").writeText("MZ")
        StoreGameSidecar(Store.GOG, "1207664643", "ELDERBORN", "ELDERBORN.exe").write(folder)
        assertEquals("deferred", CloudSaves.downloadOrDefer(app, Store.GOG, "1207664643").result)
    }
}
