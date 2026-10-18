package com.droiddeck.launcher.gpu

import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import org.junit.Assert.assertEquals
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
            assertEquals(3, calls)
            val warmFailure = TurnipReleases.refresh(context)
            assertTrue(warmFailure.rateLimited)
            assertEquals(partial.assets.single().id, warmFailure.assets.single().id)
            assertEquals(partial.checkedAt, warmFailure.checkedAt)
            assertEquals(3, calls)
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
