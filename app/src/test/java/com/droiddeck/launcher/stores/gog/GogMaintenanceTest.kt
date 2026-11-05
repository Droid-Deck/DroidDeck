package com.droiddeck.launcher.stores.gog

import com.droiddeck.launcher.stores.Store
import com.droiddeck.launcher.stores.StoreGameSidecar
import com.droiddeck.launcher.stores.StoreLanguage
import com.droiddeck.launcher.stores.CatalogItem
import com.droiddeck.launcher.stores.StoreInstallRoot
import com.droiddeck.launcher.stores.download.DownloadQueue
import com.droiddeck.launcher.stores.download.DownloadState
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.nio.file.Files
import java.util.concurrent.atomic.AtomicBoolean

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GogMaintenanceTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun selectsPublicBranchAndTreatsBuildIdsAsOpaque() {
        val builds = JSONObject("""{"items":[
          {"os":"windows","branch":"beta","build_id":"99","generation":2,"link":"https://example.invalid/beta"},
          {"os":"windows","branch":null,"build_id":"0002","generation":2,"version_name":"RC-final","link":"https://example.invalid/current"},
          {"os":"windows","branch":null,"build_id":"10","generation":2,"link":"https://example.invalid/old"}]}""")
        assertEquals("0002", GogBuild.select(builds)!!.id)
        assertEquals("99", GogBuild.select(builds, branch = "beta")!!.id)
        assertEquals("10", GogBuild.select(builds, pinned = "10")!!.id)
        assertNull(GogBuild.select(builds, pinned = "missing"))
        assertNull(GogBuild.select(builds, branch = "private"))
    }

    @Test fun migrationRetainsKnownBuildLanguageAndInstalledProducts() {
        val folder = temporary.newFolder()
        File(folder, "_gog_manifest.json").writeText("""{"gameId":"1","buildId":"000123"}""")
        File(folder, "goggame-2.info").writeText("{}")
        val sidecar = StoreGameSidecar(Store.GOG, "1", "Game", "game.exe", installVersion = "000123",
            args = listOf("--custom"), env = mapOf("DD_TEST" to "1"), cloud = false, achievements = false,
            extra = mapOf(StoreLanguage.EXTRA to "pl-PL"))
        val record = GogInstallRecord.migrate(folder, sidecar)
        assertEquals("000123", record.build)
        assertEquals("pl-PL", record.language)
        assertEquals(listOf("1", "2"), record.products)
        assertEquals(record.json().toString(), GogInstallRecord.read(folder)!!.json().toString())
        assertEquals(listOf("--custom"), sidecar.args)
        assertFalse(sidecar.cloud)
    }

    @Test fun missingManifestNeverInventsLatestBuild() {
        val folder = temporary.newFolder()
        val record = GogInstallRecord.migrate(folder, StoreGameSidecar(Store.GOG, "1", "Game", "game.exe"))
        assertEquals("offline", record.source)
        assertEquals("", record.build)
        assertEquals(0, record.files.length())
    }

    @Test fun offlineRefreshWithDlcRequiresAnAuthenticMatchingDepotBuild() {
        val extracted = temporary.newFolder()
        val app = File(extracted, "app").apply { mkdirs() }
        val target = GogInstallRecord("1", "depot", build = "0000123", products = listOf("1", "2"))
        assertThrows(IllegalArgumentException::class.java) { GogOffline.validateDepotInstaller(extracted, target) }
        File(app, "goggame-1.info").writeText("""{"buildId":"0000999"}""")
        assertThrows(IllegalArgumentException::class.java) { GogOffline.validateDepotInstaller(extracted, target) }
        File(app, "goggame-1.info").writeText("""{"buildId":"0000123"}""")
        GogOffline.validateDepotInstaller(extracted, target)
        File(app, "goggame-1.info").delete()
        GogOffline.validateDepotInstaller(extracted, target.copy(source = "offline", build = ""))
    }

    @Test fun offlineRefreshRetainsTheExistingExecutableLayout() {
        val installed = temporary.newFolder()
        val extracted = temporary.newFolder()
        val app = File(extracted, "app").apply { mkdirs() }
        File(app, "game.exe").writeText("replacement")
        File(installed, "goggame-1.info").writeText("{}")
        assertEquals(extracted, GogOffline.layout(extracted, installed, "1", "game.exe").source)
        assertEquals("", GogOffline.layout(extracted, installed, "1", "game.exe").prefix)
        File(installed, "goggame-1.info").delete()
        assertEquals(extracted, GogOffline.layout(extracted, installed, "1", "app/game.exe").source)
        val flat = temporary.newFolder()
        val legacy = GogOffline.layout(flat, installed, "1", "app/game.exe")
        assertEquals(flat, legacy.source)
        assertEquals("app/", legacy.prefix)
    }

    @Test fun offlineInstallerMatchesRegionalLanguageWithoutChoosingAnotherLanguage() {
        val downloads = JSONObject("""{"installers":[
          {"id":"de","os":"windows","language":"de","files":[{"downlink":"https://example.invalid/de"}]},
          {"id":"en","os":"windows","language":"en","files":[{"downlink":"https://example.invalid/en"}]}]}""")
        assertEquals("en", GogMaintenance.offlineInstaller(downloads, "en-US").id)
        assertThrows(IllegalStateException::class.java) { GogMaintenance.offlineInstaller(downloads, "pl-PL") }
        downloads.getJSONArray("installers").put(0, JSONObject("""{"id":"neutral","os":"windows","language":"Neutral","files":[{"downlink":"https://example.invalid/neutral"}]}"""))
        assertEquals("en", GogMaintenance.offlineInstaller(downloads, "en-US").id)
        assertEquals("neutral", GogMaintenance.offlineInstaller(downloads, "pl-PL").id)
    }

    @Test fun incompleteOfflineExtractionCannotReplaceOrRemoveInstalledFiles() {
        val folder = temporary.newFolder()
        StoreGameSidecar(Store.GOG, "1", "Game", "game.exe").write(folder)
        val game = File(folder, "game.exe").apply { writeText("installed game") }
        val old = GogInstallRecord("1", "depot", files = JSONArray().put(JSONObject().put("path", game.name)
            .put("size", game.length()).put("md5", GogFiles.hash(game))))
        old.write(folder)
        val extracted = temporary.newFolder()
        File(extracted, "goggame-1.ico").writeText("ancillary file")
        assertThrows(IllegalStateException::class.java) {
            GogOffline.applyRefresh(GogMaintenance.Proposal(folder, old, old.copy(source = "offline"), GogOperation.REFRESH, 0, 0), extracted, null, AtomicBoolean(false))
        }
        assertEquals("installed game", game.readText())
        assertFalse(File(folder, "goggame-1.ico").exists())
        assertEquals("depot", GogInstallRecord.read(folder)!!.source)
        val migrated = old.copy(files = JSONArray())
        migrated.write(folder)
        assertThrows(IllegalStateException::class.java) {
            GogOffline.applyRefresh(GogMaintenance.Proposal(folder, migrated, migrated.copy(source = "offline"), GogOperation.REFRESH, 0, 0), extracted, null, AtomicBoolean(false))
        }
        assertEquals("installed game", game.readText())
        assertFalse(File(folder, "goggame-1.ico").exists())
        assertEquals("depot", GogInstallRecord.read(folder)!!.source)
        assertEquals(0, GogInstallRecord.read(folder)!!.files.length())
    }

    @Test fun offlineRefreshCombinesRootPayloadAndAppPlaceholdersInBothLayouts() {
        for (nested in listOf(false, true)) {
            val folder = temporary.newFolder()
            val executable = if (nested) "app/game.exe" else "game.exe"
            StoreGameSidecar(Store.GOG, "1", "Game", executable).write(folder)
            val game = File(folder, executable).apply { parentFile!!.mkdirs(); writeText("old game") }
            val old = GogInstallRecord("1", "offline", files = JSONArray().put(JSONObject().put("path", executable)
                .put("size", game.length()).put("md5", GogFiles.hash(game))))
            old.write(folder)
            val extracted = temporary.newFolder()
            File(extracted, "game.exe").writeText("complete new game")
            File(extracted, "Data").mkdirs()
            File(extracted, "app/Data/SaveGame").mkdirs()
            File(extracted, "app/Data/SaveGame/dummy.txt").writeBytes(byteArrayOf())
            File(extracted, "tmp").mkdirs()
            File(extracted, "tmp/installer.exe").writeText("installer support")
            GogOffline.applyRefresh(GogMaintenance.Proposal(folder, old, old, GogOperation.REFRESH, 0, 0), extracted, null, AtomicBoolean(false))
            assertEquals("complete new game", game.readText())
            assertTrue(File(folder, (if (nested) "app/" else "") + "Data/SaveGame/dummy.txt").isFile)
            assertFalse(File(folder, "tmp").exists())
            assertTrue(GogInstallRecord.read(folder)!!.files.objects().any { it.getString("path") == executable })
        }
    }

    @Test fun offlineRefreshDefersDlcCleanupAndRecordPublicationUntilDepotCompletion() {
        val folder = temporary.newFolder()
        StoreGameSidecar(Store.GOG, "1", "Game", "base.exe").write(folder)
        val base = File(folder, "base.exe").apply { writeText("old base") }
        val dlc = File(folder, "dlc.bin").apply { writeText("old DLC") }
        val obsolete = File(folder, "old.bin").apply { writeText("obsolete") }
        val save = File(folder, "save.dat").apply { writeText("player progress") }
        fun entry(file: File, product: String) = JSONObject().put("path", file.name).put("size", file.length())
            .put("md5", GogFiles.hash(file)).put("product", product).put("offset", 0).put("remotePath", file.name)
        val old = GogInstallRecord("1", "depot", build = "old", products = listOf("1", "2"),
            files = JSONArray().put(entry(base, "1")).put(entry(dlc, "2")).put(entry(obsolete, "1")))
        old.write(folder)
        val recordFile = File(GogFiles.dir(folder), "installed.json")
        val record = recordFile.readText()
        val extracted = temporary.newFolder()
        val app = File(extracted, "app").apply { mkdirs() }
        File(app, "goggame-1.info").writeText("""{"buildId":"new"}""")
        val replacement = File(app, "base.exe").apply { writeText("new base") }
        val expectedDlc = File(temporary.newFolder(), "dlc.bin").apply { writeText("new DLC") }
        val target = old.copy(build = "new", files = JSONArray().put(entry(replacement, "1")).put(entry(expectedDlc, "2")))
        GogOffline.applyRefresh(GogMaintenance.Proposal(folder, old, target, GogOperation.REFRESH, 0, 0), extracted, null, AtomicBoolean(false))
        assertEquals("new base", base.readText())
        assertEquals("old DLC", dlc.readText())
        assertTrue(obsolete.isFile)
        assertEquals(record, recordFile.readText())
        val server = java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1"))
        val worker = Thread {
            server.accept().use { client ->
                val reader = client.getInputStream().bufferedReader()
                while (!reader.readLine().isNullOrEmpty()) Unit
                val content = expectedDlc.readBytes()
                client.getOutputStream().apply {
                    write("HTTP/1.1 206 Partial Content\r\nContent-Length: ${content.size}\r\nConnection: close\r\n\r\n".toByteArray())
                    write(content); flush()
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            GogFallback.transfer(folder, JSONObject().put("files", target.files).put("oldFiles", old.files)
                .put("links", JSONObject().put("2", JSONArray().put("http://127.0.0.1:${server.localPort}"))), null, AtomicBoolean(false))
            assertEquals("new DLC", dlc.readText())
            assertFalse(obsolete.exists())
            assertEquals("player progress", save.readText())
            assertEquals(record, recordFile.readText())
        } finally { server.close(); worker.join(2000) }
    }

    @Test fun authenticIdentityOverridesAStaleLegacyMarkerWithoutTreatingSchemaAsVersion() {
        val folder = temporary.newFolder()
        File(folder, "_gog_manifest.json").writeText("""{"buildId":"old"}""")
        File(folder, "goggame-1.id").writeText("""{"buildId":"patched"}""")
        File(folder, "goggame-1.info").writeText("""{"version":1}""")
        val record = GogInstallRecord.migrate(folder, StoreGameSidecar(Store.GOG, "1", "Game", "game.exe", installVersion = "old"))
        assertEquals("patched", record.build)
        assertEquals("", record.version)
    }

    @Test fun authenticGenerationOneResolvesProductDepotsAndRetainsHashesAndBuild() {
        fun fixture(name: String) = JSONObject(javaClass.classLoader!!.getResource("gog/$name")!!.readText())
        val build = GogBuild("3301", "", 1, "", "https://example.invalid/repository.json")
        val requested = ArrayList<String>()
        val snapshot = GogContent.snapshot(GogInstallRecord("1207658940", "depot"), build, "unused") { url, _ ->
            requested.add(url)
            when {
                url == build.link -> fixture("rayman-gen1-repository.json")
                url.endsWith("a01f6fb8-55a8-4f33-bc5b-5f5d2f591775.json") -> fixture("rayman-gen1-neutral.json")
                url.endsWith("ec88140c-2589-41a0-bbf3-8fc1f215fe5a.json") -> fixture("rayman-gen1-english-excerpt.json")
                else -> error("Unexpected metadata: $url")
            }
        }
        assertEquals("3301", snapshot.build)
        assertEquals(1, snapshot.generation)
        assertEquals(4, snapshot.files.length())
        val support = snapshot.files.getJSONObject(0)
        assertEquals("support/1207658940/galaxy_rayman2_2.1.0.39.exe", support.getString("path"))
        assertEquals("ee2c247373733f29782395f1f92622f6", support.getString("md5"))
        val game = snapshot.files.getJSONObject(1)
        assertEquals("Data/World/Levels/Raycap/Raycap.snd", game.getString("path"))
        assertEquals(384206096L, game.getLong("offset"))
        assertEquals("1207658940/main.bin", game.getString("remotePath"))
        assertTrue(requested.drop(1).all { "/1207658940/windows/31847977/" in it })
        GogFiles.validate(snapshot.files, temporary.newFolder())
    }

    @Test fun multipartPatchNamesVersionsLanguageAndChecksumsSurviveParsing() {
        val downloads = JSONObject("""{"patches":[
          {"id":"patch-1","name":"Patch A to B","version":"B","language":"pl","os":"windows","files":[
            {"id":"part0","filename":"patch_original.exe","downlink":"https://example.invalid/0","size":3,"md5":"abc"},
            {"id":"part1","filename":"patch_original-1.bin","downlink":"https://example.invalid/1","size":4}]},
          {"id":"linux","os":"linux","files":[{"downlink":"https://example.invalid/linux"}]}]}""")
        val patch = GogOfflineDownload.parse(downloads, "patches").single()
        assertEquals("Patch A to B", patch.name)
        assertEquals("B", patch.version)
        assertEquals("pl", patch.language)
        assertEquals(7, patch.size)
        assertEquals(listOf("patch_original.exe", "patch_original-1.bin"), patch.parts.map { it.name })
        assertEquals("abc", patch.parts.first().md5)
        assertEquals("patch_(52404)+special.exe", GogContent.downloadFilename("https://example.invalid/patch_%2852404%29+special.exe?token=temporary"))
    }

    @Test fun pathsRejectTraversalSymlinksCaseCollisionsAndMetadataOverlap() {
        val folder = temporary.newFolder()
        for (path in listOf("../escape", "/absolute", "C:/file", "a/./file", "a//file")) {
            assertThrows(IllegalArgumentException::class.java) { GogFiles.inside(folder, path) }
        }
        val files = JSONArray("""[{"path":"Game.exe"},{"path":"game.exe"}]""")
        assertThrows(IllegalArgumentException::class.java) { GogFiles.validate(files, folder) }
        assertThrows(IllegalArgumentException::class.java) { GogFiles.validate(JSONArray("""[{"path":"Data/a"},{"path":"data/b"}]"""), folder) }
        File(folder, "Game.bin").writeText("original")
        assertThrows(IllegalArgumentException::class.java) { GogFiles.validate(JSONArray("""[{"path":"game.bin"}]"""), folder) }
        assertThrows(IllegalArgumentException::class.java) { GogFiles.validate(JSONArray("""[{"path":".droiddeck-store.json"}]"""), folder) }
        val outside = temporary.newFolder()
        try { Files.createSymbolicLink(File(folder, "link").toPath(), outside.toPath()) }
        catch (_: java.nio.file.FileSystemException) { return }
        assertThrows(IllegalArgumentException::class.java) { GogFiles.inside(folder, "link/game.exe") }
    }

    @Test fun sameSizeCorruptionFailsVerificationAndLanguageVariantsRemainDistinct() {
        val folder = temporary.newFolder()
        val file = File(folder, "game.bin").apply { writeText("original") }
        val expected = JSONObject().put("path", file.name).put("size", file.length()).put("md5", GogFiles.hash(file))
        file.writeText("modified")
        assertFalse(GogFiles.matches(folder, expected))
        assertTrue(GogContent.languageSelected(listOf("*"), "zh-Hant"))
        assertFalse(GogContent.languageSelected(listOf("zh-Hans"), "zh-Hant"))
        assertTrue(GogContent.languageSelected(listOf("Polish"), "pl"))
    }

    @Test fun persistedManifestsContainNoCredentialsOrTemporaryLinks() {
        val input = JSONObject("""{"buildId":"1","clientSecret":"secret","urls":[{"url":"https://example.invalid/?token=secret"}],
          "products":[{"productId":"2","clientSecret":"nested"}],"dependencies":["ISI"]}""")
        val text = GogContent.sanitize(input).toString()
        assertFalse(text.contains("secret"))
        assertFalse(text.contains("https://"))
        assertTrue(text.contains("ISI"))
    }

    @Test fun interruptedExternalPatchRestoresRecoveryWithoutExecutingAgain() {
        val app = RuntimeEnvironment.getApplication()
        val folder = File(StoreInstallRoot.storeDir(StoreInstallRoot.internalRoot(app), Store.GOG), "Patch recovery").apply { mkdirs() }
        try {
            val sidecar = StoreGameSidecar(Store.GOG, "recovery-test", "Patch recovery", "game.exe",
                args = listOf("--custom"), env = mapOf("SETTING" to "value"), cloud = false,
                achievements = false, state = StoreGameSidecar.STATE_UPDATING)
            sidecar.write(folder)
            val previous = sidecar.toJson().toString()
            GogFiles.write(GogFiles.journal(folder), JSONObject().put("operation", "PATCH").put("phase", "patch-applying"))
            val item = CatalogItem(Store.GOG, sidecar.id, sidecar.title, imageUrl = null, extra = mapOf(
                GogMaintenance.OPERATION to "PATCH", GogMaintenance.FOLDER to folder.path))
            GogMaintenance.restore(app, item)
            assertEquals(DownloadState.FAILED, DownloadQueue.entry(item.key)?.state)
            assertTrue(DownloadQueue.entry(item.key)!!.error!!.contains("recovery"))
            assertEquals(previous, StoreGameSidecar.read(folder)!!.toJson().toString())
            assertEquals("patch-applying", GogFiles.read(GogFiles.journal(folder))!!.getString("phase"))
            DownloadQueue.clear(app, item.key)
        } finally { folder.deleteRecursively() }
    }

    @Test fun signedOutCheckRemainsAFailureInsteadOfCurrent() {
        val app = RuntimeEnvironment.getApplication()
        com.droiddeck.launcher.stores.StoreAccounts.clear(app, Store.GOG)
        GogMaintenance.reset()
        val folder = temporary.newFolder()
        val game = com.droiddeck.launcher.stores.InstalledStoreGame(
            StoreGameSidecar(Store.GOG, "signed-out-check", "Check", "game.exe", installVersion = "opaque-build"), folder)
        GogMaintenance.check(app, game, force = true)
        val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            org.robolectric.shadows.ShadowLooper.idleMainLooper()
            if (GogMaintenance.checks[game.sidecar.id]?.state == GogMaintenance.State.FAILED) break
            Thread.sleep(10)
        }
        assertEquals(GogMaintenance.State.FAILED, GogMaintenance.checks[game.sidecar.id]?.state)
        assertTrue(GogMaintenance.checks[game.sidecar.id]!!.reason.contains("Sign in"))
    }

    @Test fun pauseBeforeInstallerClaimCannotStartExternalApplication() {
        val app = RuntimeEnvironment.getApplication()
        val ready = java.util.concurrent.CountDownLatch(1)
        val proceed = java.util.concurrent.CountDownLatch(1)
        val finished = java.util.concurrent.CountDownLatch(1)
        val applied = AtomicBoolean(false)
        val item = com.droiddeck.launcher.stores.download.DownloadEntry(Store.GOG, "installer-claim", "Installer claim")
        DownloadQueue.enqueue(app, item) {
            object : DownloadQueue.DownloadJob {
                override fun run(handle: DownloadQueue.JobHandle): String? {
                    ready.countDown()
                    try {
                        check(proceed.await(5, java.util.concurrent.TimeUnit.SECONDS))
                        handle.interruptible(false)
                        applied.set(true)
                        return null
                    } finally { finished.countDown() }
                }
                override fun cancel(deleteFiles: Boolean) {}
            }
        }
        try {
            assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS))
            DownloadQueue.pause(item.key)
            proceed.countDown()
            assertTrue(finished.await(5, java.util.concurrent.TimeUnit.SECONDS))
            assertFalse(applied.get())
        } finally {
            proceed.countDown()
            val deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(5)
            while (DownloadQueue.entry(item.key)?.state == DownloadState.RUNNING && System.nanoTime() < deadline) Thread.sleep(10)
            DownloadQueue.clear(app, item.key)
        }
    }

    @Test fun fileFallbackVerifiesRemoteBytesAndPreservesOriginalOnCorruption() {
        val folder = temporary.newFolder()
        val content = "verified remote content".toByteArray()
        val original = File(folder, "game.bin").apply { writeText("local data") }
        val server = java.net.ServerSocket(0, 10, java.net.InetAddress.getByName("127.0.0.1"))
        var reply = content
        var transferred = 0
        val worker = Thread {
            while (!server.isClosed) {
                val client = try { server.accept() } catch (_: java.io.IOException) { break }
                client.use {
                    val reader = it.getInputStream().bufferedReader()
                    while (!reader.readLine().isNullOrEmpty()) Unit
                    it.getOutputStream().apply {
                        write("HTTP/1.1 206 Partial Content\r\nContent-Length: ${reply.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(reply); flush(); transferred += reply.size
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val expected = File(folder, "expected").apply { writeBytes(content) }
            val file = JSONObject().put("path", "game.bin").put("size", content.size).put("md5", GogFiles.hash(expected))
                .put("product", "1").put("offset", 0).put("remotePath", "data")
            val plan = JSONObject().put("files", JSONArray().put(file)).put("links", JSONObject().put("1", JSONArray().put("http://127.0.0.1:${server.localPort}")))
            reply = ByteArray(content.size) { 65 }
            assertThrows(IllegalStateException::class.java) { GogFallback.transfer(folder, plan, null, AtomicBoolean(false)) }
            assertEquals("local data", original.readText())
            reply = content
            GogFallback.transfer(folder, plan, null, AtomicBoolean(false))
            assertArrayEquals(content, original.readBytes())
            val before = transferred
            GogFallback.transfer(folder, plan, null, AtomicBoolean(false))
            assertEquals(before, transferred)
        } finally { server.close(); worker.join(2000) }
    }
}
