package com.droiddeck.launcher.session

import com.droiddeck.launcher.runtime.LinuxRuntime
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
        assertEquals("Droid-Deck/DroidDeck-Proton", droiddeck.repo)
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
    fun removingABuildDropsItsStoredOriginalsAndSelections() {
        val context = RuntimeEnvironment.getApplication()
        val tools = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
        File(LinuxRuntime.rootDir(context), "root/.local/share/droiddeck-components").mkdirs()
        installTool(tools, "GE-Proton11-7")
        val kept = installTool(tools, "GE-Proton11-8").apply {
            File(this, "files/lib/wine").mkdirs()
            File(this, "proton").writeText("")
        }
        val originals = File(context.filesDir, "components/originals")
        for (id in listOf("GE-Proton11-7", "GE-Proton11-8")) File(originals, "$id/$id/dxvk.wcp").apply { parentFile?.mkdirs(); writeText("x") }
        val pick = JSONObject().put("dxvk", JSONObject().put("file", "dxvk.wcp").put("protonVersion", "GE-Proton11-7"))
        File(context.filesDir, "components/state.json").writeText(JSONObject()
            .put("active", JSONObject().put("GE-Proton11-7", pick).put("GE-Proton11-8", pick))
            .put("queued", JSONObject().put("GE-Proton11-7", pick)).toString())
        ProtonExtras.remove(context, tool("ge"), "GE-Proton11-7")
        assertFalse(File(originals, "GE-Proton11-7").exists())
        assertTrue(File(originals, "GE-Proton11-8/GE-Proton11-8/dxvk.wcp").isFile)
        val state = JSONObject(File(context.filesDir, "components/state.json").readText())
        assertFalse(state.getJSONObject("active").has("GE-Proton11-7"))
        assertFalse(state.getJSONObject("queued").has("GE-Proton11-7"))
        assertTrue(state.getJSONObject("active").has("GE-Proton11-8"))
        ComponentsManager.forgetProton(context, kept.name)
        assertTrue(File(originals, "GE-Proton11-8/GE-Proton11-8/dxvk.wcp").isFile)
    }

    @Test
    fun anUpdateKeepsTheUsersProtonSettingsFile() {
        val context = RuntimeEnvironment.getApplication()
        val tools = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
        tools.deleteRecursively()
        File(installTool(tools, "DroidDeck-Proton 11-11.1"), "user_settings.py").writeText("old")
        File(installTool(tools, "DroidDeck-Proton 11-11.4"), "user_settings.py").writeText("mine")
        installTool(tools, "DroidDeck-Proton 11-11.5")
        ProtonExtras.tidy(context)
        assertEquals(listOf("DroidDeck-Proton 11-11.5"), ProtonExtras.installedAll(context, tool("droiddeck")))
        assertEquals("mine", File(tools, "DroidDeck-Proton 11-11.5/user_settings.py").readText())
    }

    @Test
    fun droidDeckProtonIsFetchedWhenMissingUnlessTheUserRemovedIt() {
        val context = RuntimeEnvironment.getApplication()
        val tools = File(LinuxRuntime.rootDir(context), "root/.local/share/Steam/compatibilitytools.d")
        tools.deleteRecursively()
        File(context.filesDir, "proton-latest.json").writeText(
            """{"droiddeck":{"tag":"DroidDeck-Proton-11-11.4","at":1},"ge":{"tag":"GE-Proton11-7","at":1}}""")
        ProtonExtras.setDeclined(context, false)
        assertEquals("DroidDeck-Proton-11-11.4", ProtonExtras.missing(context, tool("droiddeck")))
        assertNull(ProtonExtras.missing(context, tool("ge")))
        ProtonExtras.setDeclined(context, true)
        assertNull(ProtonExtras.missing(context, tool("droiddeck")))
        ProtonExtras.setDeclined(context, false)
        installTool(tools, "DroidDeck-Proton 11-11.2")
        assertNull(ProtonExtras.missing(context, tool("droiddeck")))
        assertEquals("DroidDeck-Proton-11-11.4", ProtonExtras.update(context, tool("droiddeck")))
    }
}
