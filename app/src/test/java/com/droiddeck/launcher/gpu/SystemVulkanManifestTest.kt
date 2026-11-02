package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.artifact.RepositorySources
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class SystemVulkanManifestTest {
    private val repository = "wallentx/droiddeck"
    private val provenance = JSONObject()
        .put("repository", repository)
        .put("commit", "a".repeat(40))
        .put("buildUrl", "https://github.com/wallentx/DroidDeck/actions/runs/1")

    @Test fun sidecarBindsProducerArtifactAndRequirements() {
        val parsed = SystemVulkanManifest.sidecar(sidecar().toString().toByteArray(), repository)
        assertEquals("powervr", parsed.variant)
        assertEquals("system-vulkan-powervr.tar.zst", parsed.artifact.name)
        assertEquals(26, parsed.minSdk)
        assertTrue(runCatching { SystemVulkanManifest.sidecar(sidecar().toString().toByteArray(), "other/repo") }.isFailure)
    }

    @Test fun embeddedManifestRequiresExactInventoryTreeAndRequiredPayload() {
        val files = SystemVulkanManifest.required("powervr").sorted().map { path ->
            SystemVulkanManifest.Entry(path, 1, RepositorySources.sha256(path.toByteArray()), if (path == "run-proton.py") 493 else 420)
        } + SystemVulkanManifest.Entry(
            "licenses/LICENSE", 1, RepositorySources.sha256("license".toByteArray()), 420,
        )
        val tree = SystemVulkanManifest.treeDigest(files)
        val expected = SystemVulkanManifest.Sidecar(
            "powervr", "1", 26,
            SystemVulkanManifest.Artifact("system-vulkan-powervr.tar.zst", 100, "b".repeat(64), tree),
            SystemVulkanManifest.Provenance(repository, "a".repeat(40), provenance.getString("buildUrl")),
        )
        val inventory = JSONArray()
        files.forEach { file -> inventory.put(JSONObject().put("path", file.path).put("type", "file")
            .put("size", file.size).put("sha256", file.sha256).put("mode", file.mode)) }
        val manifest = identity().put("treeSha256", tree).put("provenance", provenance)
            .put("files", inventory).put("licenses", JSONArray().put("licenses/LICENSE"))
        assertEquals(files.size, SystemVulkanManifest.bundle(manifest.toString().toByteArray(), expected).files.size)
        inventory.getJSONObject(0).put("path", "../escape")
        assertTrue(runCatching { SystemVulkanManifest.bundle(manifest.toString().toByteArray(), expected) }.isFailure)
    }

    @Test fun fileCannotAlsoBeAnotherInventoryEntryParent() {
        val entries = listOf(
            SystemVulkanManifest.Entry("licenses", 1, "a".repeat(64), 420),
            SystemVulkanManifest.Entry("licenses/LICENSE", 1, "b".repeat(64), 420),
        )
        val tree = SystemVulkanManifest.treeDigest(entries)
        val expected = SystemVulkanManifest.Sidecar(
            "powervr", "1", 26,
            SystemVulkanManifest.Artifact("system-vulkan-powervr.tar.zst", 100, "c".repeat(64), tree),
            SystemVulkanManifest.Provenance(repository, "a".repeat(40), provenance.getString("buildUrl")),
        )
        val rows = JSONArray()
        entries.forEach { rows.put(JSONObject().put("path", it.path).put("type", "file")
            .put("size", it.size).put("sha256", it.sha256).put("mode", it.mode)) }
        val manifest = identity().put("treeSha256", tree).put("provenance", provenance)
            .put("files", rows).put("licenses", JSONArray().put("licenses/LICENSE"))
        assertTrue(runCatching { SystemVulkanManifest.bundle(manifest.toString().toByteArray(), expected) }.isFailure)
    }

    @Test fun archiveDirectoriesAcceptProducerTrailingSlashWithoutWeakeningPathValidation() {
        assertEquals("dxvk-mali", SystemVulkanManifest.archivePath("dxvk-mali/", isDirectory = true))
        assertEquals(
            "dxvk-mali/aarch64-windows",
            SystemVulkanManifest.archivePath("dxvk-mali/aarch64-windows/", isDirectory = true),
        )
        assertEquals("lib", SystemVulkanManifest.archivePath("lib", isDirectory = true))

        listOf(
            "../escape/",
            "/absolute/",
            "dxvk-mali//",
            "dxvk-mali/../escape/",
            "dxvk-mali\\escape/",
            "./dxvk-mali/",
        ).forEach { path ->
            assertTrue(path, runCatching { SystemVulkanManifest.archivePath(path, isDirectory = true) }.isFailure)
        }
        assertTrue(runCatching { SystemVulkanManifest.archivePath("dxvk-mali/", isDirectory = false) }.isFailure)
    }

    private fun identity() = JSONObject().put("schema", 1).put("component", "system-vulkan")
        .put("variant", "powervr").put("version", "1")
        .put("requires", JSONObject().put("componentApi", 1).put("abi", "arm64-v8a").put("minSdk", 26))

    private fun sidecar() = identity().put("artifact", JSONObject()
        .put("name", "system-vulkan-powervr.tar.zst").put("size", 100)
        .put("sha256", "b".repeat(64)).put("treeSha256", "c".repeat(64)))
        .put("provenance", provenance)
}
