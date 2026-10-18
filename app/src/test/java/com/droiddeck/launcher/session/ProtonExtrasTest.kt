package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ProtonExtrasTest {
    @get:Rule val temp = TemporaryFolder()

    private fun tool(id: String) = ProtonExtras.tools.single { it.id == id }

    private fun asset(name: String, digest: String? = null) = JSONObject()
        .put("name", name)
        .put("browser_download_url", "https://github.com/o/r/releases/download/t/$name")
        .put("size", 625_775_670L)
        .apply { if (digest != null) put("digest", digest) }

    private fun release(tag: String, vararg assets: JSONObject, prerelease: Boolean = false, draft: Boolean = false) = JSONObject()
        .put("tag_name", tag).put("prerelease", prerelease).put("draft", draft)
        .put("assets", JSONArray().apply { assets.forEach { put(it) } })

    private fun droiddeckRelease(ver: String, prerelease: Boolean = false) = release(
        "DroidDeck-Proton-$ver",
        asset("DroidDeck-Proton-$ver-aarch64.build-info.json"),
        asset("DroidDeck-Proton-$ver-aarch64.sha512sum"),
        asset("DroidDeck-Proton-$ver-aarch64.tar.gz"),
        prerelease = prerelease,
    )

    private fun installTool(parent: File, name: String) = File(parent, name).apply {
        mkdirs()
        File(this, "toolmanifest.vdf").writeText("\"manifest\" { \"commandline\" \"/proton %verb%\" }")
    }

    @Test
    fun theThreeSourcesInOrder() {
        assertEquals(listOf("ge", "cachyos", "droiddeck"), ProtonExtras.tools.map { it.id })
        val droiddeck = tool("droiddeck")
        assertEquals("DroidDeck-Proton", droiddeck.name)
        assertEquals("DroidDeck-Proton", droiddeck.prefix)
        assertEquals("droid-deck/droiddeck-proton", droiddeck.repo)
        assertTrue(droiddeck.assetPattern.containsMatchIn("DroidDeck-Proton-11-7.5-aarch64.tar.gz"))
        assertTrue(droiddeck.assetPattern.containsMatchIn("DroidDeck-Proton-11-8-aarch64.tar.xz"))
        assertFalse(droiddeck.assetPattern.containsMatchIn("DroidDeck-Proton-11-7.5-aarch64.sha512sum"))
        assertFalse(droiddeck.assetPattern.containsMatchIn("DroidDeck-Proton-11-7.5-aarch64.build-info.json"))
        assertFalse(tool("cachyos").assetPattern.containsMatchIn("proton-cachyos-11.0-20261005-slr-x86_64.tar.xz"))
    }

    @Test
    fun theTarballAndItsSha512FileArePicked() {
        val picked = ProtonExtras.pickAsset(tool("droiddeck"), JSONArray().put(droiddeckRelease("11-7.5")))!!
        assertEquals("DroidDeck-Proton-11-7.5", picked.tag)
        assertEquals("DroidDeck-Proton-11-7.5-aarch64.tar.gz", picked.name)
        assertEquals("https://github.com/o/r/releases/download/t/DroidDeck-Proton-11-7.5-aarch64.sha512sum", picked.sha512)
        assertEquals(625_775_670L, picked.size)
        assertNull(picked.sha256)
    }

    @Test
    fun githubsDigestIsKeptBesideTheSha512File() {
        val sum = "ab".repeat(32)
        val release = release("GE-Proton11-7", asset("GE-Proton11-7-x86_64.tar.gz"), asset("GE-Proton11-7-aarch64.sha512sum"),
            asset("GE-Proton11-7-aarch64.tar.gz", "sha256:$sum"))
        val picked = ProtonExtras.pickAsset(tool("ge"), JSONArray().put(release))!!
        assertEquals("GE-Proton11-7-aarch64.tar.gz", picked.name)
        assertEquals(sum, picked.sha256)
        assertTrue(picked.sha512!!.endsWith("GE-Proton11-7-aarch64.sha512sum"))
    }

    @Test
    fun prereleasesDraftsAndReleasesWithoutAnArm64BuildAreSkipped() {
        val releases = JSONArray()
            .put(droiddeckRelease("11-8", prerelease = true))
            .put(release("DroidDeck-Proton-11-7.9", asset("DroidDeck-Proton-11-7.9-aarch64.tar.gz"), draft = true))
            .put(release("DroidDeck-Proton-11-7.8", asset("DroidDeck-Proton-11-7.8-x86_64.tar.gz")))
            .put(droiddeckRelease("11-7.5"))
        assertEquals("DroidDeck-Proton-11-7.5", ProtonExtras.pickAsset(tool("droiddeck"), releases)?.tag)
        assertNull(ProtonExtras.pickAsset(tool("droiddeck"), JSONArray().put(droiddeckRelease("11-8", prerelease = true))))
    }

    @Test
    fun versionsCompareByTheirNumbers() {
        val sorted = listOf("GE-Proton11-10", "GE-Proton10-25", "GE-Proton11-9", "GE-Proton11-1")
            .sortedWith { a, b -> ProtonExtras.compareVersions(a, b) }
        assertEquals(listOf("GE-Proton10-25", "GE-Proton11-1", "GE-Proton11-9", "GE-Proton11-10"), sorted)
        assertTrue(ProtonExtras.compareVersions("DroidDeck-Proton 11-7.5", "DroidDeck-Proton 11-7.10") < 0)
        assertTrue(ProtonExtras.compareVersions("DroidDeck-Proton 11-7.10", "DroidDeck-Proton 11-8") < 0)
        assertTrue(ProtonExtras.compareVersions("DroidDeck-Proton 11-7", "DroidDeck-Proton 11-7.1") < 0)
        assertTrue(ProtonExtras.compareVersions("cachyos-11.0-20260703-slr", "cachyos-11.0-20261005-slr") < 0)
        assertTrue(ProtonExtras.compareVersions("cachyos-10.0-sunset-slr", "cachyos-11.0-20260601-slr") < 0)
        assertEquals(listOf(11L, 7L), ProtonExtras.versionKey("GE-Proton11-7-aarch64"))
        assertEquals(listOf(11L, 0L, 20261005L), ProtonExtras.versionKey("proton-cachyos-11.0-20261005-slr-arm64"))
        assertEquals(listOf(11L, 7L, 5L), ProtonExtras.versionKey("DroidDeck-Proton 11-7.5"))
        assertEquals(ProtonExtras.versionKey("DroidDeck-Proton-11-7.5"), ProtonExtras.versionKey("DroidDeck-Proton 11-7.5"))
    }

    @Test
    fun sourceHashesDoNotChangeVersionOrderOrWhichOlderBuildIsRemoved() {
        val context = RuntimeEnvironment.getApplication()
        val directory = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
        val older = "GE-Proton11-7--dd-${"9a".repeat(32)}-${"8c".repeat(32)}"
        val newer = "GE-Proton11-7.1--dd-${"1b".repeat(32)}-${"2d".repeat(32)}"
        installTool(directory, older)
        installTool(directory, newer)
        assertEquals(listOf(11L, 7L), ProtonExtras.versionKey(older))
        assertEquals(listOf(older, newer), ProtonExtras.installedAll(context, tool("ge")))
        ProtonExtras.removeOlder(context, tool("ge"))
        assertFalse(File(directory, older).exists())
        assertTrue(File(directory, newer).isDirectory)
    }

    @Test
    fun installedBuildsAreFoundByPrefixNewestLast() {
        val tools = temp.newFolder("compatibilitytools.d")
        for (name in listOf("GE-Proton11-10", "GE-Proton11-9", "DroidDeck-Proton 11-7.10", "DroidDeck-Proton 11-7.5",
                "droiddeck-proton-arm64", "droiddeck-proton-11-arm64", "proton-cachyos-11.0-20261005-slr-arm64")) installTool(tools, name)
        File(tools, "DroidDeck-Proton 11-6").mkdirs()
        assertEquals(listOf("GE-Proton11-9", "GE-Proton11-10"), ProtonExtras.installedIn(tools, tool("ge")))
        assertEquals(listOf("DroidDeck-Proton 11-7.5", "DroidDeck-Proton 11-7.10"), ProtonExtras.installedIn(tools, tool("droiddeck")))
        assertEquals(listOf("proton-cachyos-11.0-20261005-slr-arm64"), ProtonExtras.installedIn(tools, tool("cachyos")))
        assertEquals(emptyList<String>(), ProtonExtras.installedIn(File(tools, "missing"), tool("ge")))
    }

    @Test
    fun anUpdateIsANewerReleaseThanTheNewestInstalledBuild() {
        assertEquals("DroidDeck-Proton-11-7.6", ProtonExtras.updateFor(listOf("DroidDeck-Proton 11-7.5"), "DroidDeck-Proton-11-7.6"))
        assertEquals("DroidDeck-Proton-11-7.10", ProtonExtras.updateFor(listOf("DroidDeck-Proton-11-7.9"), "DroidDeck-Proton-11-7.10"))
        assertNull(ProtonExtras.updateFor(listOf("DroidDeck-Proton 11-7.5"), "DroidDeck-Proton-11-7.5"))
        assertNull(ProtonExtras.updateFor(listOf("DroidDeck-Proton 11-7.5", "DroidDeck-Proton-11-7.6"), "DroidDeck-Proton-11-7.6"))
        assertNull(ProtonExtras.updateFor(listOf("GE-Proton11-10"), "GE-Proton11-9"))
        assertNull(ProtonExtras.updateFor(listOf("GE-Proton11-7-aarch64"), "GE-Proton11-7"))
        assertEquals("GE-Proton11-10", ProtonExtras.updateFor(listOf("GE-Proton11-7-aarch64", "GE-Proton11-9"), "GE-Proton11-10"))
        assertEquals("cachyos-11.0-20261101-slr", ProtonExtras.updateFor(listOf("proton-cachyos-11.0-20261005-slr-arm64"), "cachyos-11.0-20261101-slr"))
        assertNull(ProtonExtras.updateFor(listOf("proton-cachyos-11.0-20261005-slr-arm64"), "cachyos-10.0-sunset-slr"))
        assertNull(ProtonExtras.updateFor(emptyList(), "GE-Proton11-10"))
        assertNull(ProtonExtras.updateFor(listOf("GE-Proton11-7"), null))
    }

    @Test
    fun aRecentCheckIsReusedAndComparedWithTheInstalledBuild() {
        val context = RuntimeEnvironment.getApplication()
        val tools = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
        installTool(tools, "DroidDeck-Proton 11-7.5")
        installTool(tools, "GE-Proton11-7")
        val now = 1_800_000_000_000L
        val latest = JSONObject()
            .put("ge", JSONObject().put("tag", "GE-Proton11-7").put("at", now - 60_000))
            .put("cachyos", JSONObject().put("tag", "cachyos-11.0-20261005-slr").put("at", now - 60_000))
            .put("droiddeck", JSONObject().put("tag", "DroidDeck-Proton-11-7.6").put("at", now - ProtonExtras.CHECK_EVERY_MS + 60_000))
        File(context.filesDir, "proton-latest.json").writeText(latest.toString())
        assertFalse(ProtonExtras.checkLatest(context, now = now))
        assertEquals("DroidDeck-Proton-11-7.6", ProtonExtras.update(context, tool("droiddeck")))
        assertNull(ProtonExtras.update(context, tool("ge")))
        assertNull(ProtonExtras.update(context, tool("cachyos")))
        assertEquals(listOf("DroidDeck-Proton 11-7.5"), ProtonExtras.installedTags(context, tool("droiddeck")))
        File(context.filesDir, "proton-extras.json").writeText(JSONObject().put("DroidDeck-Proton 11-7.5", "DroidDeck-Proton-11-7.6").toString())
        assertEquals(listOf("DroidDeck-Proton-11-7.6"), ProtonExtras.installedTags(context, tool("droiddeck")))
        assertNull(ProtonExtras.update(context, tool("droiddeck")))
    }

    @Test
    fun forcedRefreshReportsRateLimitAndKeepsLastGoodVersions() {
        val context = RuntimeEnvironment.getApplication()
        val latest = File(context.filesDir, "proton-latest.json")
        val cache = JSONObject().put("ge", JSONObject().put("tag", "GE-Proton11-7").put("at", 123L)).toString()
        latest.writeText(cache)
        val production = GitHubArtifacts.openConnection
        var calls = 0
        try {
            GitHubArtifacts.clearRateLimits()
            GitHubArtifacts.openConnection = { url ->
                calls++
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 429
                }
            }
            val error = runCatching { ProtonExtras.checkLatest(context, force = true) }.exceptionOrNull()!!
            assertTrue(GitHubArtifacts.isRateLimited(error))
            assertEquals(1, calls)
            assertEquals(cache, latest.readText())
            assertEquals("GE-Proton11-7", ProtonExtras.cachedLatest(context, tool("ge")))
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }

    @Test
    fun removingDeletesOneBuildOrEveryOlderOne() {
        val context = RuntimeEnvironment.getApplication()
        val tools = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
        for (name in listOf("DroidDeck-Proton 11-7.5", "DroidDeck-Proton 11-7.6", "DroidDeck-Proton 11-7.10", "GE-Proton11-7")) installTool(tools, name)
        ProtonExtras.remove(context, tool("droiddeck"), "DroidDeck-Proton 11-7.6")
        assertEquals(listOf("DroidDeck-Proton 11-7.5", "DroidDeck-Proton 11-7.10"), ProtonExtras.installedAll(context, tool("droiddeck")))
        ProtonExtras.remove(context, tool("droiddeck"), "GE-Proton11-7")
        assertTrue(File(tools, "GE-Proton11-7").isDirectory)
        assertEquals(listOf("DroidDeck-Proton 11-7.5", "DroidDeck-Proton 11-7.10"), ProtonExtras.installedAll(context, tool("droiddeck")))
        ProtonExtras.removeOlder(context, tool("droiddeck"))
        assertEquals(listOf("DroidDeck-Proton 11-7.10"), ProtonExtras.installedAll(context, tool("droiddeck")))
        assertEquals("DroidDeck-Proton 11-7.10", ProtonExtras.installed(context, tool("droiddeck")))
    }

    @Test
    fun hiddenSourceStopsChecksButItsInstalledBuildRemainsVisible() {
        val context = RuntimeEnvironment.getApplication()
        val ge = tool("ge")
        val toolsDir = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
        installTool(toolsDir, "GE-Proton11-7")
        val rows = ProtonExtras.sources(context).map {
            if (it.repository == ge.repo) it.copy(hidden = true) else it
        }
        ProtonExtras.saveSources(context, rows)
        assertTrue(ProtonExtras.availableTools(context).none { it.id == ge.id })
        assertEquals(listOf("GE-Proton11-7"), ProtonExtras.installedAll(context, ge))
    }

    @Test
    fun compatibleForkToolKeepsRepositoryIdentityInItsRow() {
        val context = RuntimeEnvironment.getApplication()
        val custom = RepositorySources.source("wallentx/proton-ge-custom")
        ProtonExtras.saveSources(context, ProtonExtras.sources(context) + custom)
        val id = "${custom.id.take(12)}-ge"
        File(context.filesDir, "proton-latest.json").writeText(JSONObject()
            .put(id, JSONObject().put("tag", "GE-Proton11-8").put("at", 1L)
                .put("sourceId", custom.id).put("baseId", "ge").put("name", "wallentx/proton-ge-custom · GE-Proton"))
            .toString())
        val fork = ProtonExtras.availableTools(context).single { it.id == id }
        assertEquals("wallentx/proton-ge-custom", fork.repo)
        assertTrue(fork.name.contains(fork.repo))
    }

    @Test
    fun receiptToolIdentityOwnsInstallEvenWhenItsPrefixChanges() {
        val directory = temp.newFolder("receipt-tools")
        val installed = installTool(directory, "Foo-Proton-1--dd-${"a".repeat(64)}-${"b".repeat(64)}")
        File(installed, ".droiddeck-source.json").writeText(JSONObject()
            .put("sourceId", "source-1").put("toolId", "manifest-one").toString())
        val owner = ProtonExtras.Tool("manifest-one", "One", "Bar-Proton", "owner/proton", Regex("a^"), "source-1", "manifest")
        val sibling = ProtonExtras.Tool("known-one", "Known", "Foo-Proton", "owner/proton", Regex("a^"), "source-1", "ge")
        assertEquals(listOf(installed.name), ProtonExtras.installedIn(directory, owner))
        assertTrue(ProtonExtras.installedIn(directory, sibling).isEmpty())
    }
}
