package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.core.Hashes
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.File
import java.io.IOException
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentsCatalogSourcesTest {
    private lateinit var context: Context
    private val productionRefresher = ComponentsManager.catalogRefresher

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        File(context.filesDir, "repository-sources").deleteRecursively()
        File(context.cacheDir, "repository-metadata").deleteRecursively()
        ComponentsManager.catalogRefresher = productionRefresher
    }

    @After
    fun tearDown() {
        ComponentsManager.catalogRefresher = productionRefresher
    }

    @Test
    fun cacheOnlyReadsNeverFetchSidecarsAndWarmFailuresKeepTheirRealTimestamp() {
        val sources = bindBuiltIns()
        var calls = 0
        ComponentsManager.catalogRefresher = { _, comp, source ->
            calls++
            if (comp == "dxvk") listOf(item(comp, source, "dxvk.wcp")) else emptyList()
        }
        val fresh = ComponentsManager.catalog(context, true)
        assertEquals(3, calls)
        assertEquals(1, fresh.items.size)
        assertTrue(fresh.fetchedAt > 0)
        assertTrue(fresh.failed.isEmpty())

        calls = 0
        ComponentsManager.catalogRefresher = { _, _, _ -> calls++; error("cache-only fetched") }
        val cached = ComponentsManager.catalog(context, false)
        assertEquals(0, calls)
        assertEquals(fresh.items.map { it.id }, cached.items.map { it.id })
        assertEquals(fresh.fetchedAt, cached.fetchedAt)

        val warmFailure = ComponentsManager.catalog(context, true)
        assertEquals(3, calls)
        assertEquals(cached.items.map { it.id }, warmFailure.items.map { it.id })
        assertEquals(cached.fetchedAt, warmFailure.fetchedAt)
        assertEquals(sources.keys.map { "$it:the412banner/nightlies" }.sorted(), warmFailure.failed.sorted())
    }

    @Test
    fun coldFailuresStayColdAndAreReported() {
        bindBuiltIns()
        ComponentsManager.catalogRefresher = { _, _, _ -> throw IOException("offline") }
        val result = ComponentsManager.catalog(context, true)
        assertTrue(result.items.isEmpty())
        assertEquals(0L, result.fetchedAt)
        assertEquals(3, result.failed.size)
    }

    @Test
    fun rateLimitSurvivesPerSourceFailuresWithoutDiscardingCachedItems() {
        bindBuiltIns()
        ComponentsManager.catalogRefresher = { _, comp, source -> listOf(item(comp, source, "$comp.wcp")) }
        val cached = ComponentsManager.catalog(context, true)
        ComponentsManager.catalogRefresher = { _, comp, _ ->
            if (comp == ComponentsManager.COMPONENTS.first()) throw IOException("wrapped", GitHubArtifacts.RateLimitException(Long.MAX_VALUE))
            throw IOException("offline")
        }
        val failed = ComponentsManager.catalog(context, true)
        assertTrue(failed.rateLimited)
        assertEquals(cached.items.map { it.id }, failed.items.map { it.id })
        assertEquals(cached.fetchedAt, failed.fetchedAt)
        assertFalse(ComponentsManager.catalog(context, false).rateLimited)
    }

    @Test
    fun unrelatedAndMalformedSidecarsDoNotHideLaterValidArtifacts() {
        val comp = "dxvk"
        ComponentsManager.COMPONENTS.filter { it != comp }.forEach { other ->
            ComponentsManager.saveSources(context, other, ComponentsManager.sources(context, other).map {
                it.copy(hidden = true)
            })
        }
        val source = ComponentsManager.sources(context, comp).single()
        val sha = "a".repeat(64)
        val bodies = linkedMapOf(
            "unrelated" to JSONObject().put("schema", 1).put("component", "fex").toString(),
            "malformed" to "{broken",
            "valid" to JSONObject().put("schema", 1).put("component", comp).put("artifact", JSONObject()
                .put("name", "dxvk.wcp").put("size", 123).put("sha256", sha)).toString(),
        )
        fun asset(id: Long, tag: String, name: String, body: String, digest: String = RepositorySources.sha256(body.toByteArray())) =
            JSONObject().put("id", id).put("name", name).put("size", body.toByteArray().size)
                .put("digest", "sha256:$digest")
                .put("browser_download_url", GitHubArtifacts.releaseAssetUrl(source.repository, tag, name))
        val releases = JSONArray()
        bodies.entries.forEachIndexed { index, (tag, body) ->
            val assets = JSONArray().put(asset(index.toLong() + 1, tag, "droiddeck-component.json", body))
            if (tag == "valid") {
                assets.put(JSONObject().put("id", 99).put("name", "dxvk.wcp").put("size", 123)
                    .put("digest", "sha256:$sha")
                    .put("browser_download_url", GitHubArtifacts.releaseAssetUrl(source.repository, tag, "dxvk.wcp")))
            }
            releases.put(JSONObject().put("id", index + 10).put("tag_name", tag).put("assets", assets))
        }
        val production = GitHubArtifacts.openConnection
        var failValidSidecar = false
        try {
            GitHubArtifacts.openConnection = { url ->
                if (failValidSidecar && url.path.endsWith("/valid/droiddeck-component.json")) {
                    throw IOException("offline")
                }
                val body = when (url.path) {
                    "/repos/${source.repository}" -> JSONObject().put("id", 123).put("full_name", source.repository).toString()
                    "/repositories/123/releases" -> releases.toString()
                    else -> bodies.entries.singleOrNull { url.path.endsWith("/${it.key}/droiddeck-component.json") }?.value
                        ?: error("Unexpected request: $url")
                }
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
                }
            }
            val catalog = ComponentsManager.catalog(context, true)
            assertTrue(catalog.failed.toString(), catalog.failed.isEmpty())
            assertEquals(listOf("dxvk.wcp"), catalog.items.map { it.file })
            failValidSidecar = true
            val warmFailure = ComponentsManager.catalog(context, true)
            assertEquals(listOf("dxvk.wcp"), warmFailure.items.map { it.file })
            assertEquals(listOf("dxvk:${source.repository}"), warmFailure.failed)
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }

    @Test
    fun legacyGlobalCatalogFallsBackPerScopeAndRespectsHiddenBuiltin() {
        writeLegacyCatalog(listOf(legacyItem("fex", "fex.wcp"), legacyItem("dxvk", "dxvk.wcp")))
        ComponentsManager.saveSources(context, "fex", ComponentsManager.sources(context, "fex"))

        val before = ComponentsManager.catalog(context, false)
        assertEquals(setOf("fex", "dxvk"), before.items.map { it.comp }.toSet())
        assertEquals(2, before.items.map { it.id }.distinct().size)
        assertTrue(before.items.all { it.sourceId == RepositorySources.source("the412banner/nightlies").id })

        ComponentsManager.saveSources(context, "dxvk", ComponentsManager.sources(context, "dxvk").map {
            it.copy(hidden = true)
        })
        val after = ComponentsManager.catalog(context, false)
        assertEquals(listOf("fex"), after.items.map { it.comp })
    }

    @Test
    fun verifiedLegacyPackageSuppressesDuplicateUntilALocalImportReplacesItsBytes() {
        val oldName = "dxvk-legacy.wcp"
        val stored = File(context.filesDir, "components/packages/$oldName")
        writeWcp(stored, "remote")
        val legacy = legacyItem("dxvk", oldName, stored.length(), Hashes.sha256(stored))
        writeLegacyCatalog(listOf(legacy))

        val migrated = ComponentsManager.catalog(context, false).items.single()
        assertEquals(oldName, migrated.localFile)
        assertTrue(File(stored.parentFile, "$oldName.source.json").isFile)

        val imported = File(context.cacheDir, "imported.wcp")
        writeWcp(imported, "local replacement")
        ComponentsManager.importPackage(context, imported, oldName)
        assertFalse(File(stored.parentFile, "$oldName.source.json").exists())
        val availableAgain = ComponentsManager.catalog(context, false).items.single()
        assertNotEquals(oldName, availableAgain.localFile)
    }

    private fun bindBuiltIns(): Map<String, RepositorySources.Source> = ComponentsManager.COMPONENTS.associateWith { comp ->
        val source = ComponentsManager.sources(context, comp).single()
        RepositorySources.bind(
            context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp), source,
            100L + ComponentsManager.COMPONENTS.indexOf(comp),
            RepositorySources.generation(context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp)),
        )
    }

    private fun item(comp: String, source: RepositorySources.Source, name: String): ComponentsManager.CatalogItem {
        val digest = (comp.first().code % 16).toString(16).repeat(64)
        return ComponentsManager.CatalogItem(
            name, comp, "${comp.replaceFirstChar { it.uppercase() }}-Linux",
            "https://github.com/${source.repository}/releases/download/${comp.replaceFirstChar { it.uppercase() }}-Linux/$name",
            123, "sha256:$digest", source.id, source.repository, source.repositoryId!!, 10, 20,
            "$digest-${source.id}.wcp",
        )
    }

    private fun legacyItem(comp: String, name: String, size: Long = 123, sha: String = "a".repeat(64)) =
        JSONObject().put("file", name).put("comp", comp).put("release", "Dxvk-Linux")
            .put("url", "https://github.com/The412Banner/Nightlies/releases/download/Dxvk-Linux/$name")
            .put("size", size).put("digest", "sha256:$sha")

    private fun writeLegacyCatalog(items: List<JSONObject>) {
        val file = File(context.filesDir, "components/catalog.json")
        file.parentFile!!.mkdirs()
        file.writeText(JSONObject().put("items", JSONArray().apply { items.forEach { put(it) } })
            .put("fetchedAt", 123L).toString())
    }

    private fun writeWcp(file: File, description: String) {
        file.parentFile!!.mkdirs()
        val profile = JSONObject().put("type", "DXVK-Linux").put("versionName", "2.0")
            .put("description", description).toString().toByteArray()
        TarArchiveOutputStream(ZstdOutputStream(file.outputStream())).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("./profile.json").apply { size = profile.size.toLong() })
            tar.write(profile)
            tar.closeArchiveEntry()
        }
    }
}
