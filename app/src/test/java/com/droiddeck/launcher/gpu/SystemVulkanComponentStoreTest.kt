package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.ByteArrayInputStream
import java.io.FileOutputStream
import java.io.IOException
import java.lang.reflect.InvocationTargetException
import java.nio.file.Files
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemVulkanComponentStoreTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val profile = SystemVulkanDriver.Profile.POWERVR

    @After fun clean() {
        FileUtils.delete(File(app.filesDir, "components/system-vulkan"))
        FileUtils.delete(File(app.filesDir, "system_vulkan"))
        FileUtils.delete(File(app.filesDir, "repository-sources"))
        FileUtils.delete(File(app.cacheDir, "repository-metadata"))
        FileUtils.delete(File(app.cacheDir, "system-vulkan-directory-fixture.tar.zst"))
        FileUtils.delete(File(app.filesDir, "system-vulkan-remove-outside"))
        FileUtils.delete(File(app.filesDir, "system-vulkan-legacy-outside"))
    }

    @Test fun initialSourcesKeepOnlyTheVisibleOfficialRepository() {
        val rows = SystemVulkanComponentStore.initializeSources(app, profile)
        assertEquals(listOf("droid-deck/droiddeck"), rows.map { it.repository })
        assertTrue(rows.single().builtIn)
        assertFalse(rows.single().hidden)
    }

    @Test fun initializationPreservesExplicitSourcesAndVisibility() {
        val rows = SystemVulkanComponentStore.initializeSources(app, profile)
        val chosen = rows.map { it.copy(hidden = true) } + RepositorySources.source("Example/CustomDrivers")
        RepositorySources.save(app, SystemVulkanComponentStore.scope(profile), SystemVulkanComponentStore.sourceBuiltIns(), chosen)
        assertEquals(chosen, SystemVulkanComponentStore.initializeSources(app, profile))
    }

    @Test fun discoveryKeepsBothReleaseChannelsAndLegacyTagsThroughOfflineRefresh() {
        val repository = "droid-deck/droiddeck"
        val manifests = HashMap<String, ByteArray>()
        fun release(id: Int, tag: String, prerelease: Boolean, variant: String = profile.id): JSONObject {
            val prefix = "https://github.com/$repository/releases/download/$tag/"
            val archiveName = "system-vulkan-$variant.tar.zst"
            val sidecarName = "droiddeck-component-system-vulkan-$variant.json"
            val archiveHash = id.toString(16).repeat(64)
            val sidecar = JSONObject().put("schema", 1).put("component", "system-vulkan")
                .put("variant", variant).put("version", "v0.$id.0")
                .put("requires", JSONObject().put("componentApi", 1).put("abi", "arm64-v8a").put("minSdk", 26))
                .put("artifact", JSONObject().put("name", archiveName).put("size", 12)
                    .put("sha256", archiveHash).put("treeSha256", "e".repeat(64)))
                .put("provenance", JSONObject().put("repository", repository).put("commit", "f".repeat(40))
                    .put("buildUrl", "https://github.com/$repository/actions/runs/$id"))
                .toString().toByteArray()
            manifests[prefix + sidecarName] = sidecar
            val assets = JSONArray()
                .put(JSONObject().put("id", id * 2).put("name", archiveName).put("size", 12)
                    .put("browser_download_url", prefix + archiveName).put("digest", "sha256:$archiveHash"))
                .put(JSONObject().put("id", id * 2 + 1).put("name", sidecarName).put("size", sidecar.size)
                    .put("browser_download_url", prefix + sidecarName)
                    .put("digest", "sha256:${RepositorySources.sha256(sidecar)}"))
            return JSONObject().put("id", id).put("tag_name", tag).put("prerelease", prerelease)
                .put("published_at", "2026-10-0${id}T00:00:00Z").put("assets", assets)
        }
        val stable = release(1, "system-vulkan-powervr-v0.1.0", false)
        val preview = release(2, "system-vulkan-powervr-v0.2.0-rc.1", true)
        val legacy = release(3, "system-vulkan-old-shared-tag", true)
        val releases = JSONArray().put(stable).put(preview).put(legacy)
            .put(release(4, "system-vulkan-mali-v0.1.0", false, "mali"))
            .put(release(5, "system-vulkan-powervr-v0.5.0", false).put("draft", true))
        val transport = GitHubArtifacts.openConnection
        try {
            GitHubArtifacts.openConnection = { url ->
                val body = if (url.host == "api.github.com") {
                    if (url.path.endsWith("/releases")) releases.toString().toByteArray()
                    else """{"id":12345,"full_name":"$repository"}""".toByteArray()
                } else manifests.getValue(url.toString())
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getInputStream() = ByteArrayInputStream(body)
                    override fun getContentLengthLong() = body.size.toLong()
                }
            }
            val result = SystemVulkanComponentStore.refresh(app, profile)
            assertTrue(result.failed.isEmpty())
            assertEquals(mapOf(stable.getString("tag_name") to false, preview.getString("tag_name") to true,
                legacy.getString("tag_name") to true), result.candidates.associate { it.tag to it.prerelease })
            assertEquals(result.candidates, SystemVulkanComponentStore.cached(app, profile).candidates)

            preview.put("prerelease", false)
            val promoted = SystemVulkanComponentStore.refresh(app, profile)
            assertEquals(false, promoted.candidates.single { it.tag == preview.getString("tag_name") }.prerelease)
            GitHubArtifacts.openConnection = { throw IOException("offline") }
            assertEquals(promoted.candidates, SystemVulkanComponentStore.refresh(app, profile).candidates)

            val source = SystemVulkanComponentStore.initializeSources(app, profile).single()
            val cache = File(RepositorySources.cacheDirectory(app, SystemVulkanComponentStore.scope(profile), source),
                "system-vulkan-candidates.json")
            val oldCache = JSONObject(cache.readText())
            val candidates = oldCache.getJSONArray("candidates")
            for (index in 0 until candidates.length()) candidates.getJSONObject(index).remove("prerelease")
            cache.writeText(oldCache.toString())
            assertEquals(promoted.candidates.map { it.copy(prerelease = null) },
                SystemVulkanComponentStore.cached(app, profile).candidates)
        } finally {
            GitHubArtifacts.openConnection = transport
        }
    }

    @Test fun installedReleaseChannelsPreserveUnknownOlderReceipts() {
        listOf(true, false, null).forEachIndexed { index, channel ->
            val directory = component((index + 1).toString().repeat(64), "v0.$index.0")
            val receipt = File(directory, ".droiddeck-receipt.json")
            receipt.writeText(JSONObject(receipt.readText()).put("prerelease", channel).toString())
        }
        assertEquals(mapOf("v0.0.0" to true, "v0.1.0" to false, "v0.2.0" to null),
            SystemVulkanComponentStore.installed(app, profile).associate { it.version to it.prerelease })
    }

    @Test fun rateLimitedRefreshRetainsTheLastSuccessfulCheckTime() {
        val defaults = SystemVulkanComponentStore.sourceBuiltIns()
        val scope = SystemVulkanComponentStore.scope(profile)
        val row = SystemVulkanComponentStore.initializeSources(app, profile).single()
        val source = RepositorySources.bind(app, scope, defaults, row, 12345L,
            RepositorySources.generation(app, scope, defaults))
        val cache = File(RepositorySources.cacheDirectory(app, scope, source), "system-vulkan-candidates.json")
        cache.parentFile!!.mkdirs()
        cache.writeText(JSONObject().put("schema", 1).put("sourceId", source.id)
            .put("repository", source.repository).put("repositoryId", source.repositoryId)
            .put("candidates", JSONArray()).toString())
        assertTrue(cache.setLastModified(123_000L))
        val before = SystemVulkanComponentStore.cached(app, profile).checkedAt
        val transport = GitHubArtifacts.openConnection
        try {
            GitHubArtifacts.openConnection = { throw GitHubArtifacts.RateLimitException(System.currentTimeMillis() + 180_000L) }
            val result = SystemVulkanComponentStore.refresh(app, profile)
            assertTrue(result.rateLimited)
            assertEquals(listOf(source.repository), result.failed)
            assertEquals(before, result.checkedAt)
        } finally {
            GitHubArtifacts.openConnection = transport
        }
    }

    @Test fun activationWaitsForGuestsAndThenPromotesPendingBundle() {
        val first = component("a".repeat(64), "one")
        val second = component("b".repeat(64), "two")
        SystemVulkanComponentStore.activate(app, profile, first.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, second.name, guestsStopped = false)
        assertEquals(first, SystemVulkanComponentStore.capture(app, profile, guestsStopped = false).directory)
        assertTrue(SystemVulkanComponentStore.installed(app, profile).first { it.sha256 == second.name }.pending)
        assertEquals(second, SystemVulkanComponentStore.capture(app, profile, guestsStopped = true).directory)
    }

    @Test fun twoUnreadyStartsRollBackToPreviousCompleteBundle() {
        val previous = component("c".repeat(64), "previous")
        val active = component("d".repeat(64), "active")
        SystemVulkanComponentStore.activate(app, profile, previous.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = true)
        File(active, "READY").delete()
        assertTrue(runCatching { SystemVulkanComponentStore.capture(app, profile, guestsStopped = false) }.exceptionOrNull() is IOException)
        assertEquals(previous, SystemVulkanComponentStore.capture(app, profile, guestsStopped = false).directory)
    }

    @Test fun twoCompleteButNeverReadyLaunchesRollBackOnNextCapture() {
        val previous = component("1".repeat(64), "previous")
        val trial = component("2".repeat(64), "trial")
        SystemVulkanComponentStore.activate(app, profile, previous.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, trial.name, guestsStopped = true)
        for (generation in 1..2) {
            val snapshot = SystemVulkanComponentStore.capture(app, profile, guestsStopped = true)
            assertEquals(trial, snapshot.directory)
            SystemVulkanComponentStore.launchStarting(app, snapshot, generation)
            SystemVulkanComponentStore.sessionStopped(generation)
        }
        assertEquals(previous, SystemVulkanComponentStore.capture(app, profile, guestsStopped = true).directory)
    }

    @Test fun readyTrialIsKeptAcrossLaterCaptures() {
        val previous = component("3".repeat(64), "previous")
        val trial = component("4".repeat(64), "trial")
        SystemVulkanComponentStore.activate(app, profile, previous.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, trial.name, guestsStopped = true)
        val snapshot = SystemVulkanComponentStore.capture(app, profile, guestsStopped = true)
        SystemVulkanComponentStore.launchStarting(app, snapshot, 11)
        SystemVulkanComponentStore.sessionReady(11)
        assertEquals(trial, SystemVulkanComponentStore.capture(app, profile, guestsStopped = true).directory)
        assertFalse(SystemVulkanComponentStore.installed(app, profile).first { it.sha256 == previous.name }.previous)
    }

    @Test fun readinessOnlyPromotesTheCapturedGeneration() {
        val previous = component("0".repeat(64), "previous")
        val trial = component("a".repeat(64), "trial")
        SystemVulkanComponentStore.activate(app, profile, previous.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, trial.name, guestsStopped = true)
        val snapshot = SystemVulkanComponentStore.capture(app, profile, guestsStopped = true)
        SystemVulkanComponentStore.launchStarting(app, snapshot, 41)
        SystemVulkanComponentStore.sessionReady(42)
        assertTrue(SystemVulkanComponentStore.installed(app, profile).first { it.sha256 == previous.name }.previous)
        SystemVulkanComponentStore.sessionReady(41)
        assertFalse(SystemVulkanComponentStore.installed(app, profile).first { it.sha256 == previous.name }.previous)
    }

    @Test fun reselectingActiveDuringSessionDoesNotReplaceRollbackTarget() {
        val previous = component("5".repeat(64), "previous")
        val active = component("6".repeat(64), "active")
        SystemVulkanComponentStore.activate(app, profile, previous.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = false)
        val rows = SystemVulkanComponentStore.installed(app, profile)
        assertFalse(rows.first { it.sha256 == active.name }.pending)
        assertTrue(rows.first { it.sha256 == previous.name }.previous)
        assertEquals(active, SystemVulkanComponentStore.capture(app, profile, guestsStopped = true).directory)
    }

    @Test fun completeLegacyCacheIsUsableWithoutInventedRemoteProvenance() {
        val legacy = component("e".repeat(64), "legacy", legacy = true)
        val snapshot = SystemVulkanComponentStore.capture(app, profile, guestsStopped = true)
        assertEquals(legacy, snapshot.directory)
        assertTrue(snapshot.legacy)
        val row = SystemVulkanComponentStore.installed(app, profile).single()
        assertEquals(null, row.repository)
        assertEquals("Legacy local", row.version)
    }

    @Test fun damagedActivationStateFailsClosed() {
        component("f".repeat(64), "ready")
        val state = File(app.filesDir, "components/system-vulkan/state-powervr.json")
        state.writeText("{broken")
        assertFalse(SystemVulkanComponentStore.isReady(app, profile))
        assertTrue(runCatching { SystemVulkanComponentStore.capture(app, profile, guestsStopped = true) }.isFailure)
    }

    @Test fun hiddenSourceDropsAvailableRowsButKeepsInstalledBundle() {
        val rows = SystemVulkanComponentStore.initializeSources(app, profile)
        val configured = rows.single()
        val generation = RepositorySources.generation(app, SystemVulkanComponentStore.scope(profile), SystemVulkanComponentStore.sourceBuiltIns())
        RepositorySources.bind(
            app, SystemVulkanComponentStore.scope(profile), SystemVulkanComponentStore.sourceBuiltIns(), configured,
            12345L, generation,
        )
        val source = RepositorySources.list(
            app, SystemVulkanComponentStore.scope(profile), SystemVulkanComponentStore.sourceBuiltIns(),
        ).first { it.id == configured.id }
        val hash = "7".repeat(64)
        val candidate = JSONObject().put("key", "${source.id}:$hash").put("sourceId", source.id)
            .put("repository", source.repository).put("repositoryId", source.repositoryId)
            .put("releaseId", 1).put("tag", "v1").put("publishedAt", "2026-01-01T00:00:00Z")
            .put("version", "1").put("variant", profile.id).put("minSdk", 26)
            .put("manifestSha256", "8".repeat(64)).put("assetId", 2)
            .put("assetName", "system-vulkan-powervr.tar.zst")
            .put("assetUrl", "https://github.com/${source.repository}/releases/download/v1/system-vulkan-powervr.tar.zst")
            .put("size", 10).put("sha256", hash).put("treeSha256", "9".repeat(64))
            .put("producerCommit", "a".repeat(40))
            .put("buildUrl", "https://github.com/${source.repository}/actions/runs/1")
        val cache = File(RepositorySources.cacheDirectory(app, SystemVulkanComponentStore.scope(profile), source), "system-vulkan-candidates.json")
        cache.parentFile!!.mkdirs()
        cache.writeText(JSONObject().put("schema", 1).put("sourceId", source.id)
            .put("repository", source.repository).put("repositoryId", source.repositoryId)
            .put("candidates", JSONArray().put(candidate)).toString())
        assertEquals(1, SystemVulkanComponentStore.cached(app, profile).candidates.size)
        val local = component(hash, "installed")
        RepositorySources.save(
            app, SystemVulkanComponentStore.scope(profile), SystemVulkanComponentStore.sourceBuiltIns(),
            RepositorySources.list(app, SystemVulkanComponentStore.scope(profile), SystemVulkanComponentStore.sourceBuiltIns())
                .map { if (it.id == source.id) it.copy(hidden = true) else it },
        )
        assertTrue(SystemVulkanComponentStore.cached(app, profile).candidates.isEmpty())
        assertEquals(local, SystemVulkanComponentStore.installed(app, profile).single().directory)
    }

    @Test fun inactiveManagedComponentCanBeRemovedWithoutFollowingChildSymlinksOrDeletingOrigins() {
        val active = component("8".repeat(64), "active")
        SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = true)
        val removable = component("9".repeat(64), "removable")
        val outside = File(app.filesDir, "system-vulkan-remove-outside").apply { mkdirs() }
        val sentinel = File(outside, "keep").apply { writeText("keep") }
        Files.createSymbolicLink(File(removable, "outside-link").toPath(), outside.toPath())
        val origin = File(app.filesDir, "components/system-vulkan/origins/${removable.name}/source.json")
            .apply { parentFile!!.mkdirs(); writeText("{}") }

        assertTrue(SystemVulkanComponentStore.canRemove(app, profile, removable.name, guestsStopped = true))
        SystemVulkanComponentStore.remove(app, profile, removable.name, guestsStopped = true)

        assertFalse(removable.exists())
        assertTrue(sentinel.isFile)
        assertTrue(origin.isFile)
        assertThrows(IllegalArgumentException::class.java) {
            SystemVulkanComponentStore.activate(app, profile, removable.name, guestsStopped = true)
        }
        assertEquals(active, SystemVulkanComponentStore.capture(app, profile, guestsStopped = true).directory)
    }

    @Test fun inactiveLegacyComponentCanBeRemoved() {
        val active = component("a".repeat(64), "active")
        SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = true)
        val legacy = component("b".repeat(64), "legacy", legacy = true)

        assertTrue(SystemVulkanComponentStore.canRemove(app, profile, legacy.name, guestsStopped = true))
        SystemVulkanComponentStore.remove(app, profile, legacy.name, guestsStopped = true)

        assertFalse(legacy.exists())
        assertEquals(listOf(active.name), SystemVulkanComponentStore.installed(app, profile).map { it.sha256 })
    }

    @Test fun firstManagedActivationRetainsImplicitLegacyFallbackUntilReady() {
        val legacy = component("b".repeat(64), "legacy", legacy = true)
        val managed = component("c".repeat(64), "managed")

        SystemVulkanComponentStore.activate(app, profile, managed.name, guestsStopped = true)

        var rows = SystemVulkanComponentStore.installed(app, profile)
        assertTrue(rows.first { it.sha256 == managed.name }.active)
        assertTrue(rows.first { it.sha256 == legacy.name }.previous)
        assertRemovalRefused(legacy.name)
        val snapshot = SystemVulkanComponentStore.capture(app, profile, guestsStopped = true)
        SystemVulkanComponentStore.launchStarting(app, snapshot, 772)
        SystemVulkanComponentStore.sessionReady(772)
        rows = SystemVulkanComponentStore.installed(app, profile)
        assertFalse(rows.first { it.sha256 == legacy.name }.previous)
        assertTrue(SystemVulkanComponentStore.canRemove(app, profile, legacy.name, guestsStopped = true))
    }

    @Test fun pendingManagedSelectionMaterializesImplicitLegacyActiveState() {
        val legacy = component("d".repeat(64), "legacy", legacy = true)
        val managed = component("e".repeat(64), "managed")

        SystemVulkanComponentStore.activate(app, profile, managed.name, guestsStopped = false)

        var rows = SystemVulkanComponentStore.installed(app, profile)
        assertTrue(rows.first { it.sha256 == legacy.name }.active)
        assertTrue(rows.first { it.sha256 == managed.name }.pending)
        SystemVulkanComponentStore.capture(app, profile, guestsStopped = true)
        rows = SystemVulkanComponentStore.installed(app, profile)
        assertTrue(rows.first { it.sha256 == managed.name }.active)
        assertTrue(rows.first { it.sha256 == legacy.name }.previous)
        assertRemovalRefused(legacy.name)
    }

    @Test fun activationAndFallbackReferencesBlockRemoval() {
        val previous = component("c".repeat(64), "previous")
        val active = component("d".repeat(64), "active")
        val pending = component("e".repeat(64), "pending")
        val inactive = component("f".repeat(64), "inactive")
        SystemVulkanComponentStore.activate(app, profile, previous.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = true)
        SystemVulkanComponentStore.activate(app, profile, pending.name, guestsStopped = false)

        assertRemovalRefused(previous.name)
        assertRemovalRefused(active.name)
        assertRemovalRefused(pending.name)
        assertRemovalRefused(inactive.name, guestsStopped = false)
        assertTrue(SystemVulkanComponentStore.canRemove(app, profile, inactive.name, guestsStopped = true))
    }

    @Test fun effectiveInitialAndSoleFallbackCannotBeRemoved() {
        val older = component("1".repeat(64), "older")
        val initial = component("2".repeat(64), "initial")
        assertTrue(older.setLastModified(100_000L))
        assertTrue(initial.setLastModified(200_000L))

        assertRemovalRefused(initial.name)
        assertTrue(SystemVulkanComponentStore.canRemove(app, profile, older.name, guestsStopped = true))

        FileUtils.delete(older)
        assertRemovalRefused(initial.name)
    }

    @Test fun liveLaunchBlocksRemovalAfterActivationStateMovesOn() {
        val launched = component("3".repeat(64), "launched")
        val next = component("4".repeat(64), "next")
        val active = component("5".repeat(64), "active")
        SystemVulkanComponentStore.activate(app, profile, launched.name, guestsStopped = true)
        val snapshot = SystemVulkanComponentStore.capture(app, profile, guestsStopped = true)
        SystemVulkanComponentStore.launchStarting(app, snapshot, 771)
        try {
            SystemVulkanComponentStore.activate(app, profile, next.name, guestsStopped = true)
            SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = true)
            assertRemovalRefused(launched.name)
        } finally {
            SystemVulkanComponentStore.sessionStopped(771)
        }
        assertTrue(SystemVulkanComponentStore.canRemove(app, profile, launched.name, guestsStopped = true))
    }

    @Test fun damagedStateMalformedIdentityAndSymlinkPathsRefuseRemoval() {
        val active = component("6".repeat(64), "active")
        val inactive = component("7".repeat(64), "inactive")
        SystemVulkanComponentStore.activate(app, profile, active.name, guestsStopped = true)
        val state = File(app.filesDir, "components/system-vulkan/state-${profile.id}.json")
        state.writeText("{broken")
        assertFalse(SystemVulkanComponentStore.canRemove(app, profile, inactive.name, guestsStopped = true))
        assertThrows(IOException::class.java) {
            SystemVulkanComponentStore.remove(app, profile, inactive.name, guestsStopped = true)
        }
        assertFalse(SystemVulkanComponentStore.canRemove(app, profile, "bad", guestsStopped = true))
        assertThrows(IllegalArgumentException::class.java) {
            SystemVulkanComponentStore.remove(app, profile, "bad", guestsStopped = true)
        }

        state.delete()
        val outside = File(app.filesDir, "system-vulkan-remove-outside").apply { mkdirs() }
        val linkedDigest = "0".repeat(64)
        val linkedTarget = File(outside, linkedDigest)
        populate(linkedTarget)
        writeManagedMetadata(linkedTarget, linkedDigest, "linked")
        Files.createSymbolicLink(
            File(app.filesDir, "components/system-vulkan/$linkedDigest").toPath(),
            linkedTarget.toPath(),
        )
        assertRemovalRefused(linkedDigest)

        val legacyOutside = File(app.filesDir, "system-vulkan-legacy-outside").apply { mkdirs() }
        val legacyDigest = "a".repeat(64)
        populate(File(legacyOutside, legacyDigest))
        Files.createSymbolicLink(File(app.filesDir, "system_vulkan").toPath(), legacyOutside.toPath())
        assertRemovalRefused(legacyDigest)
    }

    @Test fun extractorAcceptsProducerDirectoryHeaders() {
        val payload = SystemVulkanManifest.required(profile.id).associateWith { path ->
            if (path == "run-proton.py") "#!/bin/sh\n".toByteArray() else path.toByteArray()
        } + ("licenses/LICENSE" to "license\n".toByteArray())
        val files = payload.map { (path, bytes) ->
            SystemVulkanManifest.Entry(
                path, bytes.size.toLong(), RepositorySources.sha256(bytes), if (path == "run-proton.py") 493 else 420,
            )
        }
        val tree = SystemVulkanManifest.treeDigest(files)
        val repository = "wallentx/droiddeck"
        val commit = "a".repeat(40)
        val buildUrl = "https://github.com/wallentx/DroidDeck/actions/runs/1"
        val inventory = JSONArray()
        files.forEach { file -> inventory.put(JSONObject().put("path", file.path).put("type", "file")
            .put("size", file.size).put("sha256", file.sha256).put("mode", file.mode)) }
        val manifest = JSONObject().put("schema", 1).put("component", "system-vulkan")
            .put("variant", profile.id).put("version", "directory-fixture")
            .put("requires", JSONObject().put("componentApi", 1).put("abi", "arm64-v8a").put("minSdk", 26))
            .put("treeSha256", tree)
            .put("provenance", JSONObject().put("repository", repository).put("commit", commit).put("buildUrl", buildUrl))
            .put("files", inventory).put("licenses", JSONArray().put("licenses/LICENSE"))
            .toString().toByteArray()
        val archive = File(app.cacheDir, "system-vulkan-directory-fixture.tar.zst")
        archive.parentFile!!.mkdirs()
        ZstdOutputStream(FileOutputStream(archive)).use { compressed ->
            TarArchiveOutputStream(compressed).use { tar ->
                addTarFile(tar, "component.json", manifest, 420)
                payload.keys.flatMap { path ->
                    val segments = path.split('/')
                    (1 until segments.size).map { segments.take(it).joinToString("/") }
                }.toSortedSet().forEach { directory ->
                    tar.putArchiveEntry(TarArchiveEntry("$directory/").apply { mode = 493 })
                    tar.closeArchiveEntry()
                }
                payload.toSortedMap().forEach { (path, bytes) ->
                    addTarFile(tar, path, bytes, if (path == "run-proton.py") 493 else 420)
                }
            }
        }
        val digest = RepositorySources.sha256(archive.readBytes())
        val source = RepositorySources.Source("fixture", repository, false, false, repositoryId = 1)
        val candidate = SystemVulkanComponentStore.Candidate(
            "fixture:$digest", source.id, repository, 1, 1, "fixture", "2026-01-01T00:00:00Z",
            "directory-fixture", profile.id, 26, "b".repeat(64), 1,
            "system-vulkan-${profile.id}.tar.zst", "https://example.invalid/component", archive.length(), digest,
            tree, commit, buildUrl,
            prerelease = true,
        )
        val target = File(app.filesDir, "components/system-vulkan/$digest")
        target.parentFile!!.mkdirs()
        val extract = SystemVulkanComponentStore::class.java.getDeclaredMethod(
            "extract", File::class.java, File::class.java,
            SystemVulkanComponentStore.Candidate::class.java, RepositorySources.Source::class.java,
        ).apply { isAccessible = true }
        try {
            extract.invoke(SystemVulkanComponentStore, archive, target, candidate, source)
        } catch (error: InvocationTargetException) {
            throw error.targetException
        }

        assertTrue(File(target, "lib").isDirectory)
        assertTrue(File(target, "lib/libhybris/linker/q.so").isFile)
        assertTrue(File(target, "READY").isFile)
        assertEquals(true, SystemVulkanComponentStore.installed(app, profile).single().prerelease)
    }

    private fun component(hash: String, version: String, legacy: Boolean = false): File {
        val directory = File(app.filesDir, (if (legacy) "system_vulkan/" else "components/system-vulkan/") + hash)
        populate(directory)
        if (!legacy) {
            writeManagedMetadata(directory, hash, version)
        }
        return directory
    }

    private fun populate(directory: File) {
        SystemVulkanManifest.required(profile.id).forEach { path ->
            File(directory, path).apply { parentFile!!.mkdirs(); writeText(path); if (path == "run-proton.py") setExecutable(true, false) }
        }
        File(directory, "icd.json").writeText("{}")
    }

    private fun writeManagedMetadata(directory: File, hash: String, version: String) {
        File(directory, ".droiddeck-receipt.json").writeText(JSONObject()
            .put("schema", 1).put("component", "system-vulkan").put("variant", profile.id)
            .put("version", version).put("sha256", hash).put("repository", "wallentx/droiddeck")
            .put("tag", "test").toString())
        File(directory, "READY").writeText(hash)
    }

    private fun assertRemovalRefused(hash: String, guestsStopped: Boolean = true) {
        assertFalse(SystemVulkanComponentStore.canRemove(app, profile, hash, guestsStopped))
        assertThrows(IOException::class.java) {
            SystemVulkanComponentStore.remove(app, profile, hash, guestsStopped)
        }
    }

    private fun addTarFile(tar: TarArchiveOutputStream, path: String, bytes: ByteArray, mode: Int) {
        tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong(); this.mode = mode })
        tar.write(bytes)
        tar.closeArchiveEntry()
    }
}
