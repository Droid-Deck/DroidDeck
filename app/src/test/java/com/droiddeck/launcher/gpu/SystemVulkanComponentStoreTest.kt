package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.core.FileUtils
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.artifact.RepositorySources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException

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

    private fun component(hash: String, version: String, legacy: Boolean = false): File {
        val directory = File(app.filesDir, (if (legacy) "system_vulkan/" else "components/system-vulkan/") + hash)
        SystemVulkanManifest.required(profile.id).forEach { path ->
            File(directory, path).apply { parentFile!!.mkdirs(); writeText(path); if (path == "run-proton.py") setExecutable(true, false) }
        }
        File(directory, "icd.json").writeText("{}")
        if (!legacy) {
            File(directory, ".droiddeck-receipt.json").writeText(JSONObject()
                .put("schema", 1).put("component", "system-vulkan").put("variant", profile.id)
                .put("version", version).put("sha256", hash).put("repository", "wallentx/droiddeck")
                .put("tag", "test").toString())
            File(directory, "READY").writeText(hash)
        }
        return directory
    }
}
