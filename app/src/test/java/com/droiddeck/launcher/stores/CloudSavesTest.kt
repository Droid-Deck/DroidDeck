package com.droiddeck.launcher.stores

import java.io.File
import java.util.concurrent.Executor
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33], application = android.app.Application::class)
class CloudSavesTest {
    private val app get() = RuntimeEnvironment.getApplication()

    private class Memory : CloudTransport {
        val files = HashMap<String, Pair<ByteArray, Long>>()
        val puts = ArrayList<String>()
        var onGet: (CloudFile) -> Unit = {}
        var raceAfter = Int.MAX_VALUE
        override fun list() = files.map { (k, v) -> CloudFile(k, v.second, CloudSaves.md5(v.first)) }
        override fun get(file: CloudFile) = files.getValue(file.name).also { onGet(file) }
        override fun put(files: Map<String, ByteArray>, modifiedMs: Map<String, Long>): Map<String, Long> {
            val sent = LinkedHashMap<String, Long>()
            for ((k, v) in files) {
                if (sent.size >= raceAfter) throw CloudChanged(sent, files.keys.filter { it !in sent })
                puts += k; this.files[k] = v to 99_000L; sent[k] = 99_000L
            }
            return sent
        }
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
        CloudSaves.upWith(app, Store.GOG, game, dir, cloud, force = true)
        assertEquals("local v2", String(cloud.files.getValue("slot0.sav").first))
        assertTrue(CloudSaves.conflicts(app, Store.GOG, game).isEmpty())
    }

    @Test fun aSlotTheGameRewritesDuringADownloadIsKeptAndNamedAsAConflict() {
        val game = id; val dir = dir()
        val cloud = Memory().apply { files["slot0.sav"] = "v1".toByteArray() to 1000L }
        CloudSaves.downWith(app, Store.GOG, game, dir, cloud, force = false)
        cloud.files["slot0.sav"] = "cloud v2".toByteArray() to 50_000L
        cloud.onGet = { File(dir, "slot0.sav").writeText("game wrote") }
        val r = CloudSaves.downWith(app, Store.GOG, game, dir, cloud, force = false)
        assertEquals("partial", r.result); assertEquals("changed-locally", r.reason); assertEquals(1, r.conflicts)
        assertTrue(r.detail.contains("slot0.sav"))
        assertEquals("game wrote", File(dir, "slot0.sav").readText())
        assertEquals(listOf("slot0.sav"), CloudSaves.conflicts(app, Store.GOG, game))
    }

    @Test fun aCloudNameOutsideTheSaveFolderIsBlockedNotAConflict() {
        val game = id; val dir = dir()
        val cloud = Memory().apply { files["../escape.sav"] = "x".toByteArray() to 1000L }
        val r = CloudSaves.downWith(app, Store.GOG, game, dir, cloud, force = false)
        assertEquals("partial", r.result); assertEquals("blocked-path", r.reason); assertEquals(0, r.conflicts)
        assertTrue(r.detail.contains("../escape.sav"))
        assertFalse(File(dir.parentFile, "escape.sav").exists())
        assertTrue(CloudSaves.conflicts(app, Store.GOG, game).isEmpty())
    }

    @Test fun anUploadStoppedByANewerCloudWriteKeepsTheRestAsConflicts() {
        val game = id; val dir = dir()
        val cloud = Memory().apply { files["a.sav"] = "a1".toByteArray() to 1000L; files["b.sav"] = "b1".toByteArray() to 1000L }
        CloudSaves.downWith(app, Store.GOG, game, dir, cloud, force = false)
        File(dir, "a.sav").writeText("a2"); File(dir, "b.sav").writeText("b2")
        cloud.raceAfter = 1
        val r = CloudSaves.upWith(app, Store.GOG, game, dir, cloud, force = false)
        assertEquals("partial", r.result); assertEquals("changed-in-cloud", r.reason); assertEquals(1, r.files)
        val unsent = listOf("a.sav", "b.sav").single { it !in cloud.puts }
        assertEquals(listOf(unsent), CloudSaves.conflicts(app, Store.GOG, game))
    }

    @Test fun aForcedUploadKeepsConflictsForSlotsItDidNotSend() {
        val game = id; val dir = dir()
        val cloud = Memory().apply { files["slot0.sav"] = "v1".toByteArray() to 1000L; files["slot1.sav"] = "w1".toByteArray() to 1000L }
        CloudSaves.downWith(app, Store.GOG, game, dir, cloud, force = false)
        File(dir, "slot0.sav").writeText("local v2"); File(dir, "slot1.sav").delete()
        cloud.files["slot0.sav"] = "cloud v2".toByteArray() to 50_000L
        com.droiddeck.launcher.stores.gog.GogPrefs.get(app).edit().putString("cloud_conflicts_$game", "slot0.sav\nslot1.sav").apply()
        CloudSaves.upWith(app, Store.GOG, game, dir, cloud, force = true)
        assertEquals("local v2", String(cloud.files.getValue("slot0.sav").first))
        assertEquals(listOf("slot1.sav"), CloudSaves.conflicts(app, Store.GOG, game))
    }

    @Test fun aDirtyMarkStaysUntilAnUploadGoesThroughOrIsSkippedForGood() {
        val gameId = "1207664643"
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.internalRoot(app), Store.GOG), "ELDERBORN-mark").apply { mkdirs() }
        File(folder, "ELDERBORN.exe").writeText("MZ")
        StoreGameSidecar(Store.GOG, gameId, "ELDERBORN", "ELDERBORN.exe").write(folder)
        com.droiddeck.launcher.stores.gog.GogPrefs.get(app).edit().putString("cloud_template_$gameId", "<?SAVED_GAMES?>/ELDERBORN").apply()
        CloudSaves.markDirty(app, Store.GOG, gameId)
        assertTrue(CloudSaves.dirty(app).contains(Store.GOG to gameId))
        val first = CloudSaves.uploadDirty(app, Store.GOG, gameId, "session-end")!!
        assertEquals("no-prefix", first.reason)
        assertTrue(CloudSaves.dirty(app).contains(Store.GOG to gameId))
        CloudSaves.uploadAllDirty(app, "recovery", running = true)
        assertTrue(CloudSaves.dirty(app).contains(Store.GOG to gameId))
        com.droiddeck.launcher.stores.gog.GogPrefs.get(app).edit()
            .putString("cloud_template_$gameId", "none")
            .putLong("cloud_template_at_$gameId", System.currentTimeMillis())
            .apply()
        assertEquals("no-cloud-saves", CloudSaves.uploadDirty(app, Store.GOG, gameId, "exit")!!.reason)
        assertTrue(CloudSaves.dirty(app).none { it.second == gameId })
        assertEquals(null, CloudSaves.uploadDirty(app, Store.GOG, gameId, "session-end"))
    }

    @Test fun aNegativeCloudTemplateIsRetriedAfterADay() {
        val now = 1_000_000L
        assertTrue(CloudSaves.noneTemplateIsCurrent(now, now + 60_000L))
        assertFalse(CloudSaves.noneTemplateIsCurrent(now, now + CloudSaves.TEMPLATE_NONE_TTL_MS))
        assertFalse(CloudSaves.noneTemplateIsCurrent(0L, now))
    }

    @Test fun aFirstLaunchWithoutAPrefixIsDeferredNotBlocked() {
        val deferred = ArrayList<Runnable>()
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.internalRoot(app), Store.GOG), "ELDERBORN").apply { mkdirs() }
        File(folder, "ELDERBORN.exe").writeText("MZ")
        StoreGameSidecar(Store.GOG, "1207664643", "ELDERBORN", "ELDERBORN.exe").write(folder)
        assertEquals("deferred", CloudSaves.downloadOrDefer(app, Store.GOG, "1207664643", Executor { deferred += it }).result)
        assertEquals(1, deferred.size)
    }
}
