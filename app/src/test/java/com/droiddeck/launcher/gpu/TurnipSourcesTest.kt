package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.json.JSONArray
import org.json.JSONObject
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE)
class TurnipSourcesTest {
    @Test
    fun partialAndWarmFailuresKeepDriverRowsAndReportTheRateLimit() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TurnipReleases.sourceBuiltIns().first()
        val production = GitHubArtifacts.openConnection
        var calls = 0
        try {
            GitHubArtifacts.clearRateLimits()
            GitHubArtifacts.openConnection = { url ->
                calls++
                val body = when (url.path) {
                    "/repos/$repo" -> JSONObject().put("id", 1).put("full_name", repo).toString()
                    "/repositories/1/releases" -> JSONArray().put(JSONObject().put("id", 2).put("tag_name", "r1")
                        .put("assets", JSONArray().put(JSONObject().put("id", 3).put("name", "Turnip-r1.zip")
                            .put("browser_download_url", "https://github.com/$repo/releases/download/r1/Turnip-r1.zip")
                            .put("size", 10).put("digest", "sha256:${"a".repeat(64)}")))).toString()
                    else -> null
                }
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = if (body == null) 429 else 200
                    override fun getInputStream() = ByteArrayInputStream(body.orEmpty().toByteArray())
                }
            }
            val partial = TurnipReleases.refresh(context)
            assertTrue(partial.rateLimited)
            assertEquals(1, partial.assets.size)
            assertEquals(2, partial.failed.size)
            assertEquals(4, calls)
            val warmFailure = TurnipReleases.refresh(context)
            assertTrue(warmFailure.rateLimited)
            assertEquals(partial.assets.single().id, warmFailure.assets.single().id)
            assertEquals(partial.checkedAt, warmFailure.checkedAt)
            assertEquals(4, calls)
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }

    @Test
    fun autoPicksUseOnlyTheFixedOfficialUrlAndKeepTheLastValidManifest() {
        val context = RuntimeEnvironment.getApplication()
        val repo = TurnipReleases.sourceBuiltIns().first()
        TurnipReleases.saveSources(context, TurnipReleases.sources(context).map { it.copy(hidden = it.repository != repo) })
        val manifest = """{"schemaVersion":1,"rules":[{"families":["A7XX"],"prefer":["wn-p"]}]}"""
        val production = GitHubArtifacts.openConnection
        var manifestFails = false
        val requested = ArrayList<String>()
        try {
            GitHubArtifacts.clearRateLimits()
            GitHubArtifacts.openConnection = { url ->
                requested.add(url.toString())
                val body = when {
                    url.path == "/repos/$repo" -> JSONObject().put("id", 1).put("full_name", repo).toString()
                    url.path == "/repositories/1/releases" -> "[]"
                    url.toString() == AutoPicks.URL -> manifest
                    else -> error("Unexpected URL $url")
                }
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = if (manifestFails && url.toString() == AutoPicks.URL) 500 else 200
                    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
                }
            }

            TurnipReleases.refresh(context)
            assertEquals(listOf(DriverPairs.WN_PERFORMANCE), TurnipReleases.autoPicks(context).single().prefer)
            assertTrue(AutoPicks.URL in requested)

            manifestFails = true
            TurnipReleases.refresh(context)
            assertEquals(listOf(DriverPairs.WN_PERFORMANCE), TurnipReleases.autoPicks(context).single().prefer)
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }

    @Test
    fun hidingEveryDriverSourceSkipsAllMetadataRequests() {
        val context = RuntimeEnvironment.getApplication()
        TurnipReleases.saveSources(context, TurnipReleases.sources(context).map { it.copy(hidden = true) })
        val production = GitHubArtifacts.openConnection
        var requested = false
        try {
            GitHubArtifacts.openConnection = { url -> requested = true; error("Unexpected metadata request: $url") }
            assertTrue(TurnipReleases.refresh(context).assets.isEmpty())
            assertFalse(requested)
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }

    @Test
    fun customForkPairsCannotMixWithBuiltInOrAnotherFork() {
        val first = RepositorySources.source("owner/turnip-one")
        val second = RepositorySources.source("owner/turnip-two")
        val builtin = TurnipReleases.sourceBuiltIns().first()
        assertEquals("banner", TurnipReleases.qualifiedPair(builtin, RepositorySources.source(builtin).id, "banner"))
        assertNotEquals(
            TurnipReleases.qualifiedPair(first.repository, first.id, "banner"),
            TurnipReleases.qualifiedPair(second.repository, second.id, "banner"),
        )
    }

    @Test
    fun configuredSourceDoesNotAcceptAnOversizedDdTurnipVersion() {
        val context = RuntimeEnvironment.getApplication()
        val source = RepositorySources.source("owner/turnip")
        TurnipReleases.saveSources(context, TurnipReleases.sources(context).map { it.copy(hidden = true) } + source)
        val tag = "DD-Turnip-v9223372036854775808.0.0"
        val name = "$tag.zip"
        val production = GitHubArtifacts.openConnection
        try {
            GitHubArtifacts.openConnection = { url ->
                val body = when (url.path) {
                    "/repos/${source.repository}" -> JSONObject().put("id", 1).put("full_name", source.repository).toString()
                    "/repositories/1/releases" -> JSONArray().put(JSONObject().put("id", 2).put("tag_name", tag)
                        .put("assets", JSONArray().put(JSONObject().put("id", 3).put("name", name)
                            .put("browser_download_url", GitHubArtifacts.releaseAssetUrl(source.repository, tag, name))
                            .put("size", 10).put("digest", "sha256:${"a".repeat(64)}")))).toString()
                    else -> error("Unexpected URL $url")
                }
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = 200
                    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
                }
            }
            val check = TurnipReleases.refresh(context)
            assertTrue(check.assets.isEmpty())
            assertTrue(TurnipReleases.cached(context)!!.assets.isEmpty())
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }

    @Test
    fun refreshedCustomA8xxPairsKeepTheirClassificationThroughCacheAndFailure() {
        val context = RuntimeEnvironment.getApplication()
        val source = RepositorySources.source("owner/turnip")
        TurnipReleases.saveSources(context, TurnipReleases.sources(context).map { it.copy(hidden = true) } + source)
        val production = GitHubArtifacts.openConnection
        var rateLimited = false
        try {
            GitHubArtifacts.clearRateLimits()
            GitHubArtifacts.openConnection = { url ->
                val body = when (url.path) {
                    "/repos/${source.repository}" -> JSONObject().put("id", 1).put("full_name", source.repository).toString()
                    "/repositories/1/releases" -> JSONArray().put(JSONObject().put("id", 2).put("tag_name", "r1")
                        .put("assets", JSONArray().apply {
                            listOf("Turnip-r1-A8xx.zip", "Turnip-r1-A8xx-Linux.zip").forEachIndexed { index, name ->
                                put(JSONObject().put("id", index + 3).put("name", name)
                                    .put("browser_download_url", "https://github.com/${source.repository}/releases/download/r1/$name")
                                    .put("size", 10).put("digest", "sha256:${"a".repeat(64)}"))
                            }
                        })).toString()
                    else -> error("Unexpected URL $url")
                }
                object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = if (rateLimited) 429 else 200
                    override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
                }
            }
            val fresh = TurnipReleases.refresh(context)
            val cached = TurnipReleases.cached(context)!!
            rateLimited = true
            val failed = TurnipReleases.refresh(context)
            assertTrue(failed.rateLimited)
            val cachedFailure = TurnipReleases.cached(context)!!
            for (check in listOf(fresh, cached, failed, cachedFailure)) {
                assertTrue(check.assets.all { it.pairKind == DriverPairs.BANNER_A8XX })
                val pair = DriverPairs.from(check).single()
                assertEquals("${DriverPairs.BANNER_A8XX}:${source.id}", pair.key)
                assertTrue(pair.complete)
                assertEquals(setOf(GpuInfo.Family.A8XX), pair.families)
            }
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }

    @Test
    fun legacyCustomCacheRecoversClassificationOnlyFromMatchingAdapterIdentity() {
        val context = RuntimeEnvironment.getApplication()
        val source = RepositorySources.source("owner/turnip")
        TurnipReleases.saveSources(context, TurnipReleases.sources(context).map { it.copy(hidden = true) } + source)
        fun row(name: String, pair: String, linux: Boolean) = JSONObject()
            .put("source", source.repository).put("repository", source.repository).put("sourceId", source.id)
            .put("tag", "r1").put("name", name).put("url", "https://example/$name")
            .put("size", 10).put("linux", linux).put("label", "Adreno").put("pair", pair)
        val key = "${DriverPairs.BANNER_A8XX}:${source.id}"
        val wrongSource = "${DriverPairs.BANNER_A8XX}:unrelated-source"
        val wrongVariant = "${DriverPairs.BANNER}:${source.id}"
        val assets = JSONArray()
            .put(row("Turnip-r1-A8xx.zip", key, false))
            .put(row("Turnip-r1-A8xx-Linux.zip", key, true))
            .put(row("Turnip-r1-A8xx.zip", wrongSource, false))
            .put(row("Turnip-r1-A8xx.zip", wrongVariant, false))
            .put(row("unrecognized.zip", "dd-turnip:unrecognized:${source.id}", false))
        context.getSharedPreferences("turnip_releases", 0).edit().putString("latest",
            JSONObject().put("assets", assets).put("latest", JSONArray()).toString()).commit()

        val pairs = DriverPairs.from(TurnipReleases.cached(context)).associateBy { it.key }
        assertTrue(pairs.getValue(key).complete)
        assertEquals(setOf(GpuInfo.Family.A8XX), pairs.getValue(key).families)
        for ((pairKey, pair) in pairs) if (pairKey != key) {
            assertTrue(pair.families.isEmpty())
            assertFalse(pair.suits(GpuInfo("Adreno 650", 650, GpuInfo.Family.A6XX, "", false)))
        }
    }

    @Test
    fun legacyFilenameDownloadMigratesOnlyToItsUniqueBuiltinAsset() {
        val context = RuntimeEnvironment.getApplication()
        val repository = TurnipReleases.sourceBuiltIns().first()
        val source = RepositorySources.source(repository)
        val asset = TurnipReleases.Asset(
            "Banners-Turnip", "r1", "Turnip-r1.zip", "https://github.com/$repository/releases/download/r1/Turnip-r1.zip",
            10, false, "Adreno", "a".repeat(64), "banner", sourceId = source.id, repository = source.repository,
            repositoryId = 1, releaseId = 2, assetId = 3,
        )
        val release = JSONObject().put("assets", JSONArray().put(JSONObject()
            .put("source", asset.source).put("tag", asset.tag).put("name", asset.name).put("url", asset.url)
            .put("size", asset.size).put("linux", asset.linux).put("label", asset.label).put("sha256", asset.sha256)
            .put("pair", asset.pair).put("bundle", false).put("sourceId", asset.sourceId)
            .put("repository", asset.repository).put("repositoryId", 1).put("releaseId", 2).put("assetId", 3)))
            .put("latest", JSONArray()).put("failed", JSONArray()).put("checkedAt", 1)
        val prefs = context.getSharedPreferences("turnip_releases", 0)
        prefs.edit().putString("latest", release.toString())
            .putString("downloads", JSONObject().put(asset.name, "installed-1").toString()).commit()

        assertEquals("installed-1", TurnipReleases.installedId(context, asset) { true })
        val migrated = JSONObject(prefs.getString("downloads", "{}")!!)
        assertEquals("installed-1", migrated.optString(asset.id))
        assertTrue(!migrated.has(asset.name))

        val custom = RepositorySources.source("owner/turnip")
        val forkAsset = TurnipReleases.Asset(asset.source, asset.tag, asset.name, asset.url, asset.size, false,
            asset.label, asset.sha256, asset.pair, sourceId = custom.id, repository = custom.repository, assetId = 4)
        prefs.edit().putString("downloads", JSONObject().put(asset.name, "installed-2").toString()).commit()
        assertNull(TurnipReleases.installedId(context, forkAsset) { true })
    }
}
