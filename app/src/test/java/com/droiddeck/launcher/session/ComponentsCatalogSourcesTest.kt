package com.droiddeck.launcher.session

import android.content.Context
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.core.Hashes
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertArrayEquals
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
import java.nio.file.Files
import java.nio.file.LinkOption
import java.nio.file.attribute.BasicFileAttributes
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class ComponentsCatalogSourcesTest {
    private lateinit var context: Context
    private val productionRefresher = ComponentsManager.catalogRefresher
    private val productionReleaseRefresher = ComponentsManager.catalogReleaseRefresher
    private val productionSidecarReader = ComponentsManager.catalogSidecarReader
    private val productionDownloader = ComponentsManager.packageDownloader
    private val productionReceiptWriter = ComponentsManager.packageReceiptWriter
    private val productionHasher = ComponentsManager.packageHasher
    private val productionBackupWriter = ComponentsManager.packageBackupWriter
    private val productionCacheCleaner = ComponentsManager.unpackedCacheCleaner

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        File(context.filesDir, "components").deleteRecursively()
        File(context.filesDir, "repository-sources").deleteRecursively()
        File(context.cacheDir, "repository-metadata").deleteRecursively()
        for (comp in ComponentsManager.COMPONENTS) {
            ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map {
                it.copy(hidden = it.repository == "droid-deck/droiddeck-components")
            })
        }
        ComponentsManager.catalogRefresher = productionRefresher
        ComponentsManager.catalogReleaseRefresher = productionReleaseRefresher
        ComponentsManager.catalogSidecarReader = productionSidecarReader
        ComponentsManager.packageDownloader = productionDownloader
        ComponentsManager.packageReceiptWriter = productionReceiptWriter
        ComponentsManager.packageHasher = productionHasher
        ComponentsManager.packageBackupWriter = productionBackupWriter
        ComponentsManager.unpackedCacheCleaner = productionCacheCleaner
    }

    @After
    fun tearDown() {
        ComponentsManager.catalogRefresher = productionRefresher
        ComponentsManager.catalogReleaseRefresher = productionReleaseRefresher
        ComponentsManager.catalogSidecarReader = productionSidecarReader
        ComponentsManager.packageDownloader = productionDownloader
        ComponentsManager.packageReceiptWriter = productionReceiptWriter
        ComponentsManager.packageHasher = productionHasher
        ComponentsManager.packageBackupWriter = productionBackupWriter
        ComponentsManager.unpackedCacheCleaner = productionCacheCleaner
    }

    @Test
    fun cacheOnlyReadsNeverFetchSidecarsAndWarmFailuresKeepTheirRealTimestamp() {
        val sources = bindBuiltIns()
        var calls = 0
        ComponentsManager.catalogRefresher = { _, comp, source, _ ->
            calls++
            if (comp == "dxvk") listOf(item(comp, source, "dxvk.wcp")) else emptyList()
        }
        val fresh = ComponentsManager.catalog(context, true)
        assertEquals(3, calls)
        assertEquals(1, fresh.items.size)
        assertTrue(fresh.fetchedAt > 0)
        assertTrue(fresh.failed.isEmpty())

        calls = 0
        ComponentsManager.catalogRefresher = { _, _, _, _ -> calls++; error("cache-only fetched") }
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
        ComponentsManager.catalogRefresher = { _, _, _, _ -> throw IOException("offline") }
        val result = ComponentsManager.catalog(context, true)
        assertTrue(result.items.isEmpty())
        assertEquals(0L, result.fetchedAt)
        assertEquals(3, result.failed.size)
    }

    @Test
    fun rateLimitSurvivesPerSourceFailuresWithoutDiscardingCachedItems() {
        bindBuiltIns()
        ComponentsManager.catalogRefresher = { _, comp, source, _ -> listOf(item(comp, source, "$comp.wcp")) }
        val cached = ComponentsManager.catalog(context, true)
        ComponentsManager.catalogRefresher = { _, comp, _, _ ->
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
    fun oneRepositoryRefreshMapsEveryComponentAndKeepsBridgePackagesAtTheirRuleNames() {
        var calls = 0
        val repository = RepositorySources.canonicalRepository("The412Banner/Nightlies")
        fun release(id: Long, tag: String, name: String): GitHubArtifacts.Release {
            val digest = id.toString(16).padStart(64, '0')
            val asset = GitHubArtifacts.Asset(
                id, name, GitHubArtifacts.releaseAssetUrl(repository, tag, name), 123, digest,
            )
            return GitHubArtifacts.Release(id, tag, tag, "", "", false, listOf(asset))
        }
        val releases = listOf(
            release(11, "FexCore-Linux", "fex.wcp"),
            release(12, "Dxvk-Linux", ComponentsManager.DXVK_FOR_VULKAN_1_1),
            release(13, "Vkd3d-proton-Linux", "vkd3d.wcp"),
        )
        ComponentsManager.catalogReleaseRefresher = { _, _, source ->
            calls++
            assertEquals(repository, source.repository)
            ComponentsManager.ReleaseRefresh(1234, releases)
        }

        val fresh = ComponentsManager.catalog(context, true)

        assertEquals(1, calls)
        assertEquals(ComponentsManager.COMPONENTS.toSet(), fresh.items.map { it.comp }.toSet())
        val bridge = fresh.items.single { it.file == ComponentsManager.DXVK_FOR_VULKAN_1_1 }
        assertEquals(bridge.file, bridge.localFile)
        assertEquals(fresh.items, ComponentsManager.catalog(context, false).items)
    }

    @Test
    fun reusedReleasesStillRejectAHiddenSourceInALaterScope() {
        var calls = 0
        ComponentsManager.catalogReleaseRefresher = { _, _, _ ->
            calls++
            ComponentsManager.ReleaseRefresh(1234, emptyList())
        }
        val shared = ComponentsManager.CatalogRefreshCache()
        val fex = RepositorySources.visible(context, ComponentsManager.sourceScope("fex"), ComponentsManager.sourceBuiltIns("fex")).single()
        ComponentsManager.refreshCatalogSource(context, "fex", fex, shared)
        val staleDxvk = RepositorySources.visible(
            context, ComponentsManager.sourceScope("dxvk"), ComponentsManager.sourceBuiltIns("dxvk"),
        ).single()
        ComponentsManager.saveSources(context, "dxvk", ComponentsManager.sources(context, "dxvk").map {
            if (it.id == staleDxvk.id) it.copy(hidden = true) else it
        })

        assertTrue(runCatching {
            ComponentsManager.refreshCatalogSource(context, "dxvk", staleDxvk, shared)
        }.isFailure)
        assertEquals(1, calls)
    }

    @Test
    fun genericSidecarBytesAreValidatedOncePerRefreshAndReusedAcrossScopes() {
        val repository = RepositorySources.canonicalRepository("The412Banner/Nightlies")
        val repositoryId = 1234L
        val sidecar = GitHubArtifacts.Asset(
            20, "droiddeck-component.json",
            GitHubArtifacts.releaseAssetUrl(repository, "v1", "droiddeck-component.json"),
            100, "a".repeat(64),
        )
        val archive = GitHubArtifacts.Asset(
            21, "fex.wcp", GitHubArtifacts.releaseAssetUrl(repository, "v1", "fex.wcp"),
            123, "b".repeat(64),
        )
        val release = GitHubArtifacts.Release(10, "v1", "", "", "", false, listOf(sidecar, archive))
        val manifest = JSONObject().put("schema", 1).put("component", "fex").put(
            "artifact", JSONObject().put("name", archive.name).put("size", archive.size).put("sha256", archive.sha256),
        ).toString().toByteArray()
        val bound = ComponentsManager.COMPONENTS.associateWith { comp ->
            val source = ComponentsManager.sources(context, comp).single { it.repository == repository }
            RepositorySources.bind(
                context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp), source,
                repositoryId, RepositorySources.generation(
                    context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp),
                ),
            )
        }
        val cache = ComponentsManager.CatalogRefreshCache().apply {
            releases[repository] = ComponentsManager.ReleaseRefresh(repositoryId, listOf(release))
        }
        var reads = 0
        ComponentsManager.catalogSidecarReader = { _, _, _, _, _, _, _ -> reads++; manifest }

        val rows = listOf("fex", "dxvk").flatMap { comp ->
            ComponentsManager.refreshCatalogSource(context, comp, bound.getValue(comp), cache)
        }
        val stale = bound.getValue("vkd3d")
        ComponentsManager.saveSources(context, "vkd3d", ComponentsManager.sources(context, "vkd3d").map {
            if (it.id == stale.id) it.copy(hidden = true) else it
        })

        assertEquals(1, reads)
        assertEquals(listOf("fex.wcp"), rows.map { it.file })
        assertTrue(runCatching {
            ComponentsManager.refreshCatalogSource(context, "vkd3d", stale, cache)
        }.isFailure)
        assertEquals(1, reads)
        val nextRefresh = ComponentsManager.CatalogRefreshCache().apply {
            releases[repository] = ComponentsManager.ReleaseRefresh(repositoryId, listOf(release))
        }
        ComponentsManager.refreshCatalogSource(context, "fex", bound.getValue("fex"), nextRefresh)
        assertEquals(2, reads)
    }

    @Test
    fun legacyPackageCannotBeReplacedBetweenValidationAndReceiptWrite() {
        val comp = "dxvk"
        val source = ComponentsManager.sources(context, comp).single {
            it.repository == RepositorySources.canonicalRepository("The412Banner/Nightlies")
        }
        val generation = RepositorySources.generation(
            context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp),
        )
        val bound = RepositorySources.bind(
            context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp), source,
            1234L, generation,
        )
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        val installed = File(packages, "adopted.wcp").also { writeWcp(it, "validated package") }
        val validatedBytes = installed.readBytes()
        val item = ComponentsManager.CatalogItem(
            installed.name, comp, "Dxvk-Linux",
            GitHubArtifacts.releaseAssetUrl(bound.repository, "Dxvk-Linux", installed.name),
            installed.length(), "sha256:${Hashes.sha256(installed)}", bound.id, bound.repository,
            bound.repositoryId!!, 44, 55, installed.name,
        )
        ComponentsManager.catalogRefresher = { _, requested, _, _ -> if (requested == comp) listOf(item) else emptyList() }
        val receiptWritten = CountDownLatch(1)
        val releaseReceipt = CountDownLatch(1)
        ComponentsManager.packageReceiptWriter = { file, bytes ->
            RepositorySources.atomicWrite(file, bytes)
            receiptWritten.countDown()
            check(releaseReceipt.await(5, TimeUnit.SECONDS))
        }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val catalog = thread {
            runCatching { ComponentsManager.catalog(context, true, setOf(comp)) }.onFailure(errors::add)
        }
        assertTrue(receiptWritten.await(5, TimeUnit.SECONDS))
        val replacement = File(context.cacheDir, "replacement-import.wcp").also { writeWcp(it, "replacement") }
        val imported = CountDownLatch(1)
        val importer = thread {
            runCatching { ComponentsManager.importPackage(context, replacement, installed.name) }.onFailure(errors::add)
            imported.countDown()
        }
        assertFalse("import replaced a package during provenance adoption", imported.await(150, TimeUnit.MILLISECONDS))
        assertArrayEquals(validatedBytes, installed.readBytes())
        val receipt = JSONObject(File(packages, "${installed.name}.source.json").readText())
        assertEquals(Hashes.sha256(installed), receipt.getString("sha256"))

        releaseReceipt.countDown()
        catalog.join(5_000)
        importer.join(5_000)
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(imported.await(1, TimeUnit.SECONDS))
        assertArrayEquals(replacement.readBytes(), installed.readBytes())
        assertFalse(File(packages, "${installed.name}.source.json").exists())
    }

    @Test
    fun oneScopesRefreshFailureDoesNotPoisonAnotherScopeForTheSameRepository() {
        var calls = 0
        val repository = RepositorySources.canonicalRepository("The412Banner/Nightlies")
        fun release(id: Long, tag: String, name: String): GitHubArtifacts.Release {
            val digest = id.toString(16).padStart(64, '0')
            return GitHubArtifacts.Release(id, tag, "", "", "", false, listOf(GitHubArtifacts.Asset(
                id, name, GitHubArtifacts.releaseAssetUrl(repository, tag, name), 123, digest,
            )))
        }
        val releases = listOf(
            release(12, "Dxvk-Linux", "dxvk.wcp"),
            release(13, "Vkd3d-proton-Linux", "vkd3d.wcp"),
        )
        ComponentsManager.catalogReleaseRefresher = { _, comp, _ ->
            calls++
            if (comp == "fex") throw IOException("source changed")
            ComponentsManager.ReleaseRefresh(1234, releases)
        }

        val result = ComponentsManager.catalog(context, true)

        assertEquals(2, calls)
        assertEquals(listOf("fex:$repository"), result.failed)
        assertEquals(setOf("dxvk", "vkd3d"), result.items.map { it.comp }.toSet())
    }

    @Test
    fun aCustomRepositoryCannotClaimTheFixedBridgePackageFilename() {
        val comp = "dxvk"
        ComponentsManager.COMPONENTS.filter { it != comp }.forEach { other ->
            ComponentsManager.saveSources(context, other, ComponentsManager.sources(context, other).map { it.copy(hidden = true) })
        }
        val custom = RepositorySources.source("example/components")
        ComponentsManager.saveSources(context, comp,
            ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val digest = "a".repeat(64)
        val asset = GitHubArtifacts.Asset(12, ComponentsManager.DXVK_FOR_VULKAN_1_1,
            GitHubArtifacts.releaseAssetUrl(custom.repository, "Dxvk-Linux", ComponentsManager.DXVK_FOR_VULKAN_1_1),
            123, digest)
        ComponentsManager.catalogReleaseRefresher = { _, _, _ -> ComponentsManager.ReleaseRefresh(
            5678, listOf(GitHubArtifacts.Release(11, "Dxvk-Linux", "", "", "", false, listOf(asset))),
        ) }

        val bridge = ComponentsManager.catalog(context, true).items.single()

        assertNotEquals(bridge.file, bridge.localFile)
        assertEquals("$digest-${custom.id}.wcp", bridge.localFile)
        assertFalse(ComponentsManager.fixedPackageName("dxvk", bridge.file, "Dxvk-arm64ec-Linux"))
    }

    @Test
    fun legacyBridgePackagesKeepTheFilenameUsedByAutomaticRules() {
        writeLegacyCatalog(listOf(legacyItem("dxvk", ComponentsManager.DXVK_FOR_VULKAN_1_3_NO_INT64)))

        val bridge = ComponentsManager.catalog(context, false).items.single()

        assertEquals(bridge.file, bridge.localFile)
    }

    @Test
    fun unrelatedAndMalformedSidecarsDoNotHideLaterValidArtifacts() {
        val comp = "dxvk"
        ComponentsManager.COMPONENTS.filter { it != comp }.forEach { other ->
            ComponentsManager.saveSources(context, other, ComponentsManager.sources(context, other).map {
                it.copy(hidden = true)
            })
        }
        val source = testSource(comp)
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
    fun legacyFallbackDoesNotAttachAnotherRepositorysNightlyToNightlies() {
        val name = "DD-dxvk-3.1.1+153-nightly.wcp"
        val repository = "droid-deck/droiddeck-components"
        val rows = ComponentsManager.sources(context, "dxvk")
        val source = ComponentsManager.saveSources(context, "dxvk", rows.map { it.copy(hidden = false) })
            .single { it.repository == repository }
        val bound = RepositorySources.bind(
            context, ComponentsManager.sourceScope("dxvk"), ComponentsManager.sourceBuiltIns("dxvk"),
            source, 1234L,
            RepositorySources.generation(context, ComponentsManager.sourceScope("dxvk"), ComponentsManager.sourceBuiltIns("dxvk")),
        )
        val nightly = item("dxvk", bound, name).copy(
            release = "linux-nightly",
            url = GitHubArtifacts.releaseAssetUrl(repository, "linux-nightly", name),
        )
        writeLegacyCatalog(listOf(
            legacyItem("dxvk", "dxvk.wcp"),
            legacyItem("dxvk", name).put("release", nightly.release).put("url", nightly.url),
        ))
        ComponentsManager.catalogRefresher = { _, comp, configured, _ ->
            if (comp == "dxvk" && configured.id == bound.id) listOf(nightly) else throw IOException("offline")
        }

        val fresh = ComponentsManager.catalog(context, true)
        assertEquals(listOf(nightly), fresh.items.filter { it.file == name })
        assertEquals("the412banner/nightlies", fresh.items.single { it.file == "dxvk.wcp" }.repository)
        assertEquals(fresh.items, ComponentsManager.catalog(context, false).items)
    }

    @Test
    fun verifiedDroidDeckNightliesReuseLegacyFilenamesWithoutAdoptingUnverifiedFiles() {
        val repository = "droid-deck/droiddeck-components"
        val source = ComponentsManager.saveSources(context, "dxvk", ComponentsManager.sources(context, "dxvk").map {
            it.copy(hidden = it.repository != repository)
        }).single { !it.hidden }
        val bound = RepositorySources.bind(
            context, ComponentsManager.sourceScope("dxvk"), ComponentsManager.sourceBuiltIns("dxvk"),
            source, 1234L,
            RepositorySources.generation(context, ComponentsManager.sourceScope("dxvk"), ComponentsManager.sourceBuiltIns("dxvk")),
        )
        val packages = File(context.filesDir, "components/packages")
        val catalogItems = listOf("matching", "digest-mismatch", "component-mismatch").map { scenario ->
            val name = "DD-dxvk-$scenario-nightly.wcp"
            val stored = File(packages, name)
            writeWcp(stored, scenario, if (scenario == "component-mismatch") "VKD3D-Linux" else "DXVK-Linux")
            val digest = if (scenario == "digest-mismatch") "a".repeat(64) else Hashes.sha256(stored)
            item("dxvk", bound, name).copy(
                release = "linux-nightly",
                url = GitHubArtifacts.releaseAssetUrl(repository, "linux-nightly", name),
                size = stored.length(), digest = "sha256:$digest", localFile = "$digest-${bound.id}.wcp",
            )
        }
        ComponentsManager.catalogRefresher = { _, comp, configured, _ ->
            if (comp == "dxvk" && configured.id == bound.id) catalogItems else throw IOException("offline")
        }

        val fresh = ComponentsManager.catalog(context, true)
        val matching = fresh.items.single { it.file == "DD-dxvk-matching-nightly.wcp" }
        assertEquals(matching.file, matching.localFile)
        val receipt = JSONObject(File(packages, matching.file + ".source.json").readText())
        assertEquals(repository, receipt.getString("repository"))
        assertEquals(bound.id, receipt.getString("sourceId"))
        for (item in fresh.items.filter { it != matching }) {
            assertNotEquals(item.file, item.localFile)
            assertFalse(File(packages, item.file + ".source.json").exists())
        }
        assertEquals(fresh.items, ComponentsManager.catalog(context, false).items)
        assertEquals(3, packages.listFiles().orEmpty().count { it.extension == "wcp" })
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

    @Test
    fun unboundLegacyNightliesDownloadRequiresItsCanonicalVisibleSourceAndUrl() {
        val name = "dxvk-legacy-download.wcp"
        val payload = File(context.cacheDir, "legacy-download.wcp")
        writeWcp(payload, "legacy download")
        writeLegacyCatalog(listOf(legacyItem("dxvk", name, payload.length(), Hashes.sha256(payload))))
        val item = ComponentsManager.catalog(context, false).items.single { it.file == name }
        val source = RepositorySources.source("the412banner/nightlies")
        assertEquals(source.id, item.sourceId)
        assertEquals(0L, item.repositoryId)
        var calls = 0
        ComponentsManager.packageDownloader = { _, destination, _, _ ->
            calls++
            payload.copyTo(destination, overwrite = true)
            true
        }

        val installed = ComponentsManager.download(context, item) {}
        assertEquals(item.repository, installed.source)
        assertEquals(1, calls)

        val forged = item.copy(
            repository = "attacker/nightlies",
            url = GitHubArtifacts.releaseAssetUrl("attacker/nightlies", item.release, item.file),
            localFile = "forged-${item.localFile}",
        )
        assertTrue(runCatching { ComponentsManager.download(context, forged) {} }.isFailure)
        assertTrue(runCatching {
            ComponentsManager.download(context, item.copy(
                url = GitHubArtifacts.releaseAssetUrl("attacker/nightlies", item.release, item.file),
                localFile = "forged-url-${item.localFile}",
            )) {}
        }.isFailure)
        assertTrue(runCatching {
            ComponentsManager.download(context, item.copy(
                digest = "sha256:" + "0".repeat(64),
                localFile = "forged-digest-${item.localFile}",
            )) {}
        }.isFailure)

        ComponentsManager.saveSources(context, "dxvk", ComponentsManager.sources(context, "dxvk").map {
            if (it.id == source.id) it.copy(hidden = true) else it
        })
        assertTrue(runCatching {
            ComponentsManager.download(context, item.copy(localFile = "hidden-${item.localFile}")) {}
        }.isFailure)
        assertEquals(2, calls)
    }

    @Test
    fun sourceChangesWaitUntilPackageAdoptionAndProvenanceCommitFinish() {
        val comp = "dxvk"
        val builtIns = ComponentsManager.sourceBuiltIns(comp)
        val source = ComponentsManager.sources(context, comp).single {
            it.repository == RepositorySources.canonicalRepository("the412banner/nightlies")
        }
        val generation = RepositorySources.generation(context, ComponentsManager.sourceScope(comp), builtIns)
        val item = ComponentsManager.CatalogItem(
            "dxvk.wcp", comp, "Dxvk-Linux", "", 1, "sha256:" + "a".repeat(64),
            sourceId = source.id, repository = source.repository,
        )
        val commitEntered = CountDownLatch(1)
        val releaseCommit = CountDownLatch(1)
        val sourceSaveStarted = CountDownLatch(1)
        val sourceSaved = CountDownLatch(1)
        val errors = ConcurrentLinkedQueue<Throwable>()
        var committed = false

        val adopter = thread {
            runCatching {
                ComponentsManager.commitDownloadedPackage(context, item, generation) {
                    commitEntered.countDown()
                    check(releaseCommit.await(2, TimeUnit.SECONDS))
                    committed = true
                }
            }.onFailure { errors.add(it) }
        }
        assertTrue(commitEntered.await(2, TimeUnit.SECONDS))
        val policy = thread {
            sourceSaveStarted.countDown()
            runCatching {
                ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map {
                    if (it.id == source.id) it.copy(hidden = true) else it
                })
            }.onFailure { errors.add(it) }
            sourceSaved.countDown()
        }
        assertTrue(sourceSaveStarted.await(2, TimeUnit.SECONDS))
        assertFalse(sourceSaved.await(150, TimeUnit.MILLISECONDS))
        releaseCommit.countDown()
        adopter.join(2_000)
        policy.join(2_000)

        assertFalse(adopter.isAlive)
        assertFalse(policy.isAlive)
        assertTrue(errors.toString(), errors.isEmpty())
        assertTrue(committed)
        assertTrue(RepositorySources.visible(context, ComponentsManager.sourceScope(comp), builtIns).none {
            it.id == source.id
        })

        var legacyCommitted = false
        ComponentsManager.commitDownloadedPackage(context, item.copy(sourceId = ""), null) {
            legacyCommitted = true
        }
        assertTrue(legacyCommitted)
    }

    @Test
    fun receiptFailurePreservesExistingPackagesAndRemovesNewPackages() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/custom-components")
        ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val generation = RepositorySources.generation(context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp))
        val bound = RepositorySources.bind(context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp), custom, 1234L, generation)
        val payload = File(context.cacheDir, "rollback-download.wcp")
        writeWcp(payload, "new package")
        val digest = Hashes.sha256(payload)
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        ComponentsManager.packageDownloader = { _, destination, _, _ -> payload.copyTo(destination, overwrite = true); true }

        for (scenario in listOf("identical", "different", "new")) {
            val name = "receipt-$scenario.wcp"
            val dest = File(packages, name)
            when (scenario) {
                "identical" -> payload.copyTo(dest, overwrite = true)
                "different" -> writeWcp(dest, "previous package")
            }
            val previous = dest.takeIf { it.isFile }?.readBytes()
            val receipt = File(packages, "$name.source.json")
            receipt.writeText("previous receipt")
            val item = ComponentsManager.CatalogItem(
                file = "custom.wcp", comp = comp, release = "v1",
                url = GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", "custom.wcp"),
                size = payload.length(), digest = "sha256:$digest", sourceId = bound.id,
                repository = bound.repository, repositoryId = bound.repositoryId!!,
                releaseId = 44L, assetId = 55L, localFile = name,
            )
            ComponentsManager.packageReceiptWriter = { _, _ -> throw IOException("receipt write failed") }
            assertTrue("$scenario should fail", runCatching { ComponentsManager.download(context, item) {} }.isFailure)
            if (previous == null) assertFalse(dest.exists()) else assertArrayEquals(previous, dest.readBytes())
            assertEquals("previous receipt", receipt.readText())
            assertFalse(File(packages, "$name.part").exists())
            assertTrue(packages.listFiles().orEmpty().none { it.name.endsWith(".backup") })

            ComponentsManager.packageReceiptWriter = productionReceiptWriter
            ComponentsManager.download(context, item) {}
            assertArrayEquals(payload.readBytes(), dest.readBytes())
            assertEquals(bound.repository, JSONObject(receipt.readText()).getString("repository"))
        }
    }

    @Test
    fun sourceChangesDuringSlowPackageHashingAbortWithoutTouchingTheDestination() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/slow-components")
        ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val scope = ComponentsManager.sourceScope(comp)
        val builtIns = ComponentsManager.sourceBuiltIns(comp)
        val generation = RepositorySources.generation(context, scope, builtIns)
        val bound = RepositorySources.bind(context, scope, builtIns, custom, 1234L, generation)
        val payload = File(context.cacheDir, "slow-download.wcp").also { writeWcp(it, "new package") }
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        val dest = File(packages, "slow.wcp").also { writeWcp(it, "previous package") }
        val previous = dest.readBytes()
        val receipt = File(packages, "slow.wcp.source.json").apply { writeText("previous receipt") }
        val item = ComponentsManager.CatalogItem(
            "slow.wcp", comp, "v1", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", "slow.wcp"),
            payload.length(), "sha256:${Hashes.sha256(payload)}", bound.id, bound.repository,
            bound.repositoryId!!, 44, 55, dest.name,
        )
        ComponentsManager.packageDownloader = { _, output, _, _ -> payload.copyTo(output, overwrite = true); true }
        val hashing = CountDownLatch(1)
        val releaseHash = CountDownLatch(1)
        ComponentsManager.packageHasher = { file ->
            if (file == dest) {
                hashing.countDown()
                check(releaseHash.await(5, TimeUnit.SECONDS))
            }
            Hashes.sha256(file)
        }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val adopter = thread { runCatching { ComponentsManager.download(context, item) {} }.onFailure(errors::add) }
        assertTrue(hashing.await(5, TimeUnit.SECONDS))
        val componentRead = CountDownLatch(1)
        thread {
            ComponentsManager.activeChoices(context)
            componentRead.countDown()
        }
        assertTrue("component read waited for package hashing", componentRead.await(2, TimeUnit.SECONDS))
        val sourceSaved = CountDownLatch(1)
        thread {
            ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map {
                if (it.id == bound.id) it.copy(hidden = true) else it
            })
            sourceSaved.countDown()
        }
        assertTrue("source save waited for package hashing", sourceSaved.await(2, TimeUnit.SECONDS))
        releaseHash.countDown()
        adopter.join(5_000)

        assertFalse(adopter.isAlive)
        assertEquals(1, errors.size)
        assertArrayEquals(previous, dest.readBytes())
        assertEquals("previous receipt", receipt.readText())
        assertFalse(File(packages, "slow.wcp.part").exists())
        assertTrue(packages.listFiles().orEmpty().none { it.name.endsWith(".backup") })
    }

    @Test
    fun packageSelectedDuringHashingIsRecheckedBeforeReplacement() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/selection-components")
        ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val scope = ComponentsManager.sourceScope(comp)
        val builtIns = ComponentsManager.sourceBuiltIns(comp)
        val generation = RepositorySources.generation(context, scope, builtIns)
        val bound = RepositorySources.bind(context, scope, builtIns, custom, 1234L, generation)
        val payload = File(context.cacheDir, "selection-download.wcp").also { writeWcp(it, "new package") }
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        val dest = File(packages, "selected.wcp").also { writeWcp(it, "previous package") }
        val previous = dest.readBytes()
        val item = ComponentsManager.CatalogItem(
            dest.name, comp, "v1", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", dest.name),
            payload.length(), "sha256:${Hashes.sha256(payload)}", bound.id, bound.repository,
            bound.repositoryId!!, 44, 55, dest.name,
        )
        ComponentsManager.packageDownloader = { _, output, _, _ -> payload.copyTo(output, overwrite = true); true }
        val hashing = CountDownLatch(1)
        val releaseHash = CountDownLatch(1)
        ComponentsManager.packageHasher = { file ->
            if (file == dest) {
                hashing.countDown()
                check(releaseHash.await(5, TimeUnit.SECONDS))
            }
            Hashes.sha256(file)
        }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val adopter = thread { runCatching { ComponentsManager.download(context, item) {} }.onFailure(errors::add) }
        assertTrue(hashing.await(5, TimeUnit.SECONDS))
        ComponentsManager.setGameComponent(context, "42", comp, dest.name) { false }
        releaseHash.countDown()
        adopter.join(5_000)

        assertFalse(adopter.isAlive)
        assertEquals(1, errors.size)
        assertArrayEquals(previous, dest.readBytes())
        assertEquals(dest.name, ComponentsManager.gameComponents(context, "42")[comp])
        assertTrue(packages.listFiles().orEmpty().none { it.name.endsWith(".backup") })
    }

    @Test
    fun sourceChangesDoNotWaitForPostPublicationCacheCleanup() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/cache-components")
        ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val scope = ComponentsManager.sourceScope(comp)
        val builtIns = ComponentsManager.sourceBuiltIns(comp)
        val generation = RepositorySources.generation(context, scope, builtIns)
        val bound = RepositorySources.bind(context, scope, builtIns, custom, 1234L, generation)
        val payload = File(context.cacheDir, "cache-download.wcp").also { writeWcp(it, "new package") }
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        val name = "cache.wcp"
        val dest = File(packages, name).also { writeWcp(it, "previous package") }
        val item = ComponentsManager.CatalogItem(
            name, comp, "v1", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", name),
            payload.length(), "sha256:${Hashes.sha256(payload)}", bound.id, bound.repository,
            bound.repositoryId!!, 44, 55, name,
        )
        ComponentsManager.packageDownloader = { _, output, _, _ -> payload.copyTo(output, overwrite = true); true }
        val unpacked = File(
            LinuxRuntime.rootDir(context),
            "root/.local/share/droiddeck-components/store/${ComponentsManager.unpackedCacheKey(name)}",
        ).apply { mkdirs() }
        File(unpacked, ".complete").writeText("1\n")
        val cleaning = CountDownLatch(1)
        val releaseCleanup = CountDownLatch(1)
        ComponentsManager.unpackedCacheCleaner = { file ->
            cleaning.countDown()
            check(releaseCleanup.await(5, TimeUnit.SECONDS))
            file.deleteRecursively()
        }
        val errors = ConcurrentLinkedQueue<Throwable>()
        val adopter = thread { runCatching { ComponentsManager.download(context, item) {} }.onFailure(errors::add) }
        assertTrue(cleaning.await(5, TimeUnit.SECONDS))
        val sourceSaved = CountDownLatch(1)
        thread {
            ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map {
                if (it.id == bound.id) it.copy(hidden = true) else it
            })
            sourceSaved.countDown()
        }
        assertTrue("source save waited for cache cleanup", sourceSaved.await(2, TimeUnit.SECONDS))
        releaseCleanup.countDown()
        adopter.join(5_000)

        assertFalse(adopter.isAlive)
        assertTrue(errors.toString(), errors.isEmpty())
        assertArrayEquals(payload.readBytes(), dest.readBytes())
        assertFalse(unpacked.exists())
    }

    @Test
    fun destinationReplacementAfterPreparationIsNotOverwrittenOrRolledBack() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/replaced-components")
        ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val scope = ComponentsManager.sourceScope(comp)
        val builtIns = ComponentsManager.sourceBuiltIns(comp)
        val generation = RepositorySources.generation(context, scope, builtIns)
        val bound = RepositorySources.bind(context, scope, builtIns, custom, 1234L, generation)
        val payload = File(context.cacheDir, "replacement-download.wcp").also { writeWcp(it, "new package") }
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        val dest = File(packages, "replacement.wcp").also { writeWcp(it, "previous package") }
        val replacement = "concurrent replacement".toByteArray()
        val item = ComponentsManager.CatalogItem(
            dest.name, comp, "v1", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", dest.name),
            payload.length(), "sha256:${Hashes.sha256(payload)}", bound.id, bound.repository,
            bound.repositoryId!!, 44, 55, dest.name,
        )
        ComponentsManager.packageDownloader = { _, output, _, _ -> payload.copyTo(output, overwrite = true); true }
        ComponentsManager.packageBackupWriter = { source, backup ->
            source.copyTo(backup, overwrite = true)
            val next = File(packages, ".replacement").apply { writeBytes(replacement) }
            assertTrue(source.delete())
            assertTrue(next.renameTo(source))
        }

        assertTrue(runCatching { ComponentsManager.download(context, item) {} }.isFailure)
        assertArrayEquals(replacement, dest.readBytes())
        assertTrue(packages.listFiles().orEmpty().none { it.name.endsWith(".backup") })
        assertFalse(File(packages, "replacement.wcp.part").exists())
    }

    @Test
    fun validatedTemporaryPackageReplacementIsRejectedBeforePublication() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/part-replacement-components")
        ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val scope = ComponentsManager.sourceScope(comp)
        val builtIns = ComponentsManager.sourceBuiltIns(comp)
        val generation = RepositorySources.generation(context, scope, builtIns)
        val bound = RepositorySources.bind(context, scope, builtIns, custom, 1234L, generation)
        val payload = File(context.cacheDir, "part-replacement-download.wcp").also { writeWcp(it, "new package") }
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        val dest = File(packages, "part-replacement.wcp").also { writeWcp(it, "previous package") }
        val previous = dest.readBytes()
        val item = ComponentsManager.CatalogItem(
            dest.name, comp, "v1", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", dest.name),
            payload.length(), "sha256:${Hashes.sha256(payload)}", bound.id, bound.repository,
            bound.repositoryId!!, 44, 55, dest.name,
        )
        var downloadedPart: File? = null
        ComponentsManager.packageDownloader = { _, output, _, _ ->
            downloadedPart = output
            payload.copyTo(output, overwrite = true)
            true
        }
        ComponentsManager.packageBackupWriter = { source, backup ->
            source.copyTo(backup, overwrite = true)
            val part = requireNotNull(downloadedPart)
            assertTrue(part.delete())
            payload.copyTo(part)
        }

        assertTrue(runCatching { ComponentsManager.download(context, item) {} }.isFailure)
        assertArrayEquals(previous, dest.readBytes())
        assertTrue(packages.listFiles().orEmpty().none { it.name.endsWith(".backup") || it.name.endsWith(".part") })
    }

    @Test
    fun cacheInvalidationFailureBeforePublicationLeavesDestinationIdentityUntouched() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/cache-failure-components")
        ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom)
        val scope = ComponentsManager.sourceScope(comp)
        val builtIns = ComponentsManager.sourceBuiltIns(comp)
        val generation = RepositorySources.generation(context, scope, builtIns)
        val bound = RepositorySources.bind(context, scope, builtIns, custom, 1234L, generation)
        val payload = File(context.cacheDir, "cache-failure-download.wcp").also { writeWcp(it, "new package") }
        val packages = File(context.filesDir, "components/packages").apply { mkdirs() }
        val dest = File(packages, "cache-failure.wcp").also { writeWcp(it, "previous package") }
        val previous = dest.readBytes()
        val beforeKey = Files.readAttributes(
            dest.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        ).fileKey()
        val item = ComponentsManager.CatalogItem(
            dest.name, comp, "v1", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", dest.name),
            payload.length(), "sha256:${Hashes.sha256(payload)}", bound.id, bound.repository,
            bound.repositoryId!!, 44, 55, dest.name,
        )
        ComponentsManager.packageDownloader = { _, output, _, _ -> payload.copyTo(output, overwrite = true); true }
        val unpacked = File(
            LinuxRuntime.rootDir(context),
            "root/.local/share/droiddeck-components/store/${ComponentsManager.unpackedCacheKey(dest.name)}",
        )
        File(unpacked, ".complete/child").apply { parentFile!!.mkdirs(); writeText("busy") }

        assertTrue(runCatching { ComponentsManager.download(context, item) {} }.isFailure)
        assertArrayEquals(previous, dest.readBytes())
        assertEquals(beforeKey, Files.readAttributes(
            dest.toPath(), BasicFileAttributes::class.java, LinkOption.NOFOLLOW_LINKS,
        ).fileKey())
        assertTrue(packages.listFiles().orEmpty().none { it.name.endsWith(".backup") })
        assertFalse(File(packages, "cache-failure.wcp.part").exists())
    }

    @Test
    fun aBoundCustomSourceCanDownloadButCannotBorrowAnotherRepositoryIdentity() {
        val comp = "dxvk"
        val custom = RepositorySources.source("wallentx/custom-components")
        val rows = ComponentsManager.sources(context, comp).map { it.copy(hidden = true) } + custom
        ComponentsManager.saveSources(context, comp, rows)
        val generation = RepositorySources.generation(
            context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp),
        )
        val bound = RepositorySources.bind(
            context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp), custom,
            1234L, generation,
        )
        val payload = File(context.cacheDir, "custom.wcp")
        writeWcp(payload, "custom source")
        val digest = Hashes.sha256(payload)
        val item = ComponentsManager.CatalogItem(
            file = "custom.wcp", comp = comp, release = "v1",
            url = GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", "custom.wcp"),
            size = payload.length(), digest = "sha256:$digest", sourceId = bound.id,
            repository = bound.repository, repositoryId = bound.repositoryId!!,
            releaseId = 44L, assetId = 55L, localFile = "$digest-${bound.id}-remote.wcp",
        )
        var calls = 0
        ComponentsManager.packageDownloader = { _, destination, expected, progress ->
            calls++
            assertEquals(digest, expected)
            payload.copyTo(destination, overwrite = true)
            progress(100)
            true
        }

        val installed = ComponentsManager.download(context, item) {}
        assertEquals(bound.repository, installed.source)
        assertEquals(1, calls)
        assertTrue(File(context.filesDir, "components/packages/${item.localFile}").isFile)

        val forged = item.copy(
            repository = "attacker/other-components",
            url = GitHubArtifacts.releaseAssetUrl("attacker/other-components", "v1", "custom.wcp"),
        )
        assertTrue(runCatching { ComponentsManager.download(context, forged) {} }.isFailure)
        assertTrue(runCatching { ComponentsManager.download(context, item.copy(sourceId = "forged-source")) {} }.isFailure)
        assertTrue(runCatching { ComponentsManager.download(context, item.copy(repositoryId = bound.repositoryId!! + 1)) {} }.isFailure)
        assertEquals(1, calls)

        val stale = item.copy(assetId = 56L, localFile = "$digest-${bound.id}-stale.wcp")
        ComponentsManager.packageDownloader = { _, destination, _, _ ->
            calls++
            payload.copyTo(destination, overwrite = true)
            ComponentsManager.saveSources(context, comp, ComponentsManager.sources(context, comp).map {
                if (it.id == bound.id) it.copy(hidden = true) else it
            })
            true
        }
        assertTrue(runCatching { ComponentsManager.download(context, stale) {} }.isFailure)
        assertEquals(2, calls)
        val packages = File(context.filesDir, "components/packages")
        assertFalse(File(packages, stale.localFile).exists())
        assertFalse(File(packages, stale.localFile + ".part").exists())
        assertFalse(File(packages, stale.localFile + ".source.json").exists())
    }

    private fun bindBuiltIns(): Map<String, RepositorySources.Source> = ComponentsManager.COMPONENTS.associateWith { comp ->
        val source = testSource(comp)
        RepositorySources.bind(
            context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp), source,
            100L + ComponentsManager.COMPONENTS.indexOf(comp),
            RepositorySources.generation(context, ComponentsManager.sourceScope(comp), ComponentsManager.sourceBuiltIns(comp)),
        )
    }

    private fun testSource(comp: String): RepositorySources.Source {
        val rows = ComponentsManager.sources(context, comp)
        val selected = rows.single {
            it.repository == RepositorySources.canonicalRepository("The412Banner/Nightlies")
        }
        return ComponentsManager.saveSources(
            context, comp, rows.map { it.copy(hidden = it.id != selected.id) },
        ).filterNot { it.hidden }.single()
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

    private fun writeWcp(file: File, description: String, type: String = "DXVK-Linux") {
        file.parentFile!!.mkdirs()
        val profile = JSONObject().put("type", type).put("versionName", "2.0")
            .put("description", description).toString().toByteArray()
        TarArchiveOutputStream(ZstdOutputStream(file.outputStream())).use { tar ->
            tar.putArchiveEntry(TarArchiveEntry("./profile.json").apply { size = profile.size.toLong() })
            tar.write(profile)
            tar.closeArchiveEntry()
        }
    }
}
