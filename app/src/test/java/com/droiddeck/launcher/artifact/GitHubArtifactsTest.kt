package com.droiddeck.launcher.artifact

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URI
import java.net.URL
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Before
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GitHubArtifactsTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val key = "gpu/system-vulkan/mali"
    private val defaults = listOf("Example/Drivers")
    private val source get() = RepositorySources.list(app, key, defaults).single()
    private val productionConnection = GitHubArtifacts.openConnection
    private val productionClock = GitHubArtifacts.currentTimeMillis
    private val sha = "a".repeat(64)

    @Before fun clearRateLimits() { GitHubArtifacts.clearRateLimits() }
    @After fun restoreTransport() {
        GitHubArtifacts.openConnection = productionConnection
        GitHubArtifacts.currentTimeMillis = productionClock
        GitHubArtifacts.clearRateLimits()
    }

    private class Response(url: URL, private val status: Int = 200, private val body: String = "",
        private val location: String? = null, private val headers: Map<String, String> = emptyMap()) : HttpURLConnection(url) {
        override fun connect() {}
        override fun disconnect() {}
        override fun usingProxy() = false
        override fun getResponseCode() = status
        override fun getHeaderField(name: String?) = if (name == "Location") location
            else headers.entries.firstOrNull { it.key.equals(name, ignoreCase = true) }?.value
        override fun getInputStream() = ByteArrayInputStream(body.toByteArray())
        override fun getErrorStream() = ByteArrayInputStream(body.toByteArray())
        override fun getContentLengthLong() = -1L
    }

    private fun release(digest: String? = "sha256:$sha", assetUrl: String = "https://github.com/Example/Drivers/releases/download/v1/bundle.zip"): JSONObject {
        val asset = JSONObject().put("id", 9).put("name", "bundle.zip").put("size", 12)
            .put("browser_download_url", assetUrl).put("digest", digest ?: JSONObject.NULL)
        return JSONObject().put("id", 5).put("tag_name", "v1").put("name", "Driver 1")
            .put("assets", JSONArray().put(asset))
    }

    private fun successfulTransport(release: JSONObject = release()) {
        GitHubArtifacts.openConnection = { url ->
            Response(url, body = if (url.path.endsWith("/releases")) JSONArray().put(release).toString()
                else """{"id":123,"full_name":"Example/Drivers"}""")
        }
    }

    private fun repositoryFileBody(value: String = "x") = JSONObject()
        .put("type", "file")
        .put("path", "catalog.json")
        .put("encoding", "base64")
        .put("size", value.toByteArray().size)
        .put("content", android.util.Base64.encodeToString(value.toByteArray(), android.util.Base64.NO_WRAP))
        .toString()

    @Test fun repositoryFilesUseTheDefaultBranchUnlessAnOverrideIsConfigured() {
        val requests = ArrayList<URI>()
        GitHubArtifacts.openConnection = { url ->
            requests.add(url.toURI())
            Response(url, body = if (url.path.endsWith("/catalog.json")) repositoryFileBody()
                else """{"id":123,"full_name":"Example/Drivers"}""")
        }

        assertEquals("x", GitHubArtifacts.readRepositoryFile(
            app, key, defaults, source, "catalog.json", null,
        ).toString(Charsets.UTF_8))
        assertEquals(null, requests.single { it.path.endsWith("/catalog.json") }.rawQuery)

        requests.clear()
        val dev = RepositorySources.save(app, key, defaults, listOf(source.copy(catalogBranch = "dev"))).single()
        GitHubArtifacts.readRepositoryFile(app, key, defaults, dev, "catalog.json", dev.catalogBranch)
        assertEquals("ref=dev", requests.single { it.path.endsWith("/catalog.json") }.rawQuery)

        requests.clear()
        val punctuation = RepositorySources.save(
            app, key, defaults, listOf(dev.copy(catalogBranch = "Feature/dev+100%")),
        ).single()
        GitHubArtifacts.readRepositoryFile(app, key, defaults, punctuation, "catalog.json", punctuation.catalogBranch)
        assertEquals("ref=Feature%2Fdev%2B100%25", requests.single { it.path.endsWith("/catalog.json") }.rawQuery)
    }

    @Test fun branchChangeDuringRepositoryFileFetchRejectsTheStaleResult() {
        val dev = RepositorySources.save(app, key, defaults, listOf(source.copy(catalogBranch = "dev"))).single()
        GitHubArtifacts.openConnection = { url ->
            if (url.path.endsWith("/catalog.json")) {
                RepositorySources.save(app, key, defaults, listOf(dev.copy(catalogBranch = "release")))
                Response(url, body = repositoryFileBody())
            } else Response(url, body = """{"id":123,"full_name":"Example/Drivers"}""")
        }

        assertTrue(runCatching {
            GitHubArtifacts.readRepositoryFile(app, key, defaults, dev, "catalog.json", dev.catalogBranch)
        }.isFailure)
    }

    @Test fun missingExplicitBranchFileDoesNotFallBackToDefault() {
        val requests = ArrayList<URI>()
        val dev = RepositorySources.save(app, key, defaults, listOf(source.copy(catalogBranch = "dev"))).single()
        GitHubArtifacts.openConnection = { url ->
            requests.add(url.toURI())
            if (url.path.endsWith("/catalog.json")) Response(url, status = 404)
            else Response(url, body = """{"id":123,"full_name":"Example/Drivers"}""")
        }

        val error = runCatching {
            GitHubArtifacts.readRepositoryFile(app, key, defaults, dev, "catalog.json", dev.catalogBranch)
        }.exceptionOrNull()
        assertTrue(error is GitHubArtifacts.HttpException && error.status == 404)
        assertEquals(1, requests.count { it.path.endsWith("/catalog.json") })
        assertEquals("ref=dev", requests.single { it.path.endsWith("/catalog.json") }.rawQuery)
    }

    @Test fun refreshBindsIdentityAndRetainsLastGoodCatalogAfterFailure() {
        successfulTransport()
        val results = GitHubArtifacts.refreshReleases(app, key, defaults, source)
        assertEquals(123L, source.repositoryId)
        assertEquals(sha, results.single().assets.single().sha256)
        GitHubArtifacts.openConnection = { throw IOException("offline") }
        assertTrue(runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, source) }.isFailure)
        assertEquals(results, GitHubArtifacts.cachedReleases(app, key, defaults, source))
    }

    @Test fun primaryLimitHonorsResetAndAvoidsRequestsUntilTheReset() {
        var now = 1_000_000L
        var calls = 0
        GitHubArtifacts.currentTimeMillis = { now }
        GitHubArtifacts.openConnection = { url ->
            calls++
            if (calls == 1) Response(url, 403, headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to "1600"))
            else Response(url, body = "ok")
        }
        val url = "https://api.github.com/repos/example/drivers"
        val first = runCatching { GitHubArtifacts.fetch(url, 128) { true } }.exceptionOrNull()
        assertTrue(first is GitHubArtifacts.RateLimitException)
        assertEquals(1_600_000L, (first as GitHubArtifacts.RateLimitException).retryAtMillis)
        assertTrue(GitHubArtifacts.isRateLimited(IOException("wrapped", first)))
        now = 1_599_999L
        assertTrue(runCatching { GitHubArtifacts.fetch(url, 128) { true } }.exceptionOrNull() is GitHubArtifacts.RateLimitException)
        assertEquals(1, calls)
        now = 1_600_000L
        assertEquals("ok", GitHubArtifacts.fetch(url, 128) { true }.toString(Charsets.UTF_8))
        assertEquals(2, calls)
    }

    @Test fun secondaryLimitWithRemainingQuotaHonorsRetryAfter() {
        GitHubArtifacts.currentTimeMillis = { 1_000_000L }
        GitHubArtifacts.openConnection = { url -> Response(url, 403,
            body = """{"message":"You have exceeded a secondary rate limit."}""",
            headers = mapOf("X-RateLimit-Remaining" to "15", "Retry-After" to "120", "X-RateLimit-Reset" to "9999")) }
        val error = runCatching { GitHubArtifacts.fetch("https://api.github.com/repos/example/drivers", 128) { true } }.exceptionOrNull()
        assertTrue(error is GitHubArtifacts.RateLimitException)
        assertEquals(1_120_000L, (error as GitHubArtifacts.RateLimitException).retryAtMillis)
    }

    @Test fun headerlessSecondaryLimitAnd429UseBriefCooldown() {
        GitHubArtifacts.currentTimeMillis = { 1_000_000L }
        for ((status, body) in listOf(403 to """{"message":"API rate limit exceeded"}""", 429 to "not JSON")) {
            GitHubArtifacts.clearRateLimits()
            GitHubArtifacts.openConnection = { url -> Response(url, status, body) }
            val error = runCatching { GitHubArtifacts.fetch("https://api.github.com/repos/example/drivers", 128) { true } }.exceptionOrNull()
            assertTrue(error is GitHubArtifacts.RateLimitException)
            assertEquals(1_180_000L, (error as GitHubArtifacts.RateLimitException).retryAtMillis)
        }
    }

    @Test fun normalForbiddenAndMissingResponsesAreNotRateLimits() {
        for (status in listOf(403, 404, 500)) {
            GitHubArtifacts.openConnection = { url -> Response(url, status,
                body = """{"message":"Resource not accessible by integration"}""", headers = mapOf("X-RateLimit-Remaining" to "42")) }
            val error = runCatching { GitHubArtifacts.fetch("https://api.github.com/repos/example/drivers", 128) { true } }.exceptionOrNull()!!
            assertFalse(GitHubArtifacts.isRateLimited(error))
            assertEquals(status, (error as GitHubArtifacts.HttpException).status)
        }
    }

    @Test fun throttledRefreshPreservesLastGoodCatalogAndDoesNotBlockReleaseAssets() {
        successfulTransport()
        val cached = GitHubArtifacts.refreshReleases(app, key, defaults, source)
        GitHubArtifacts.openConnection = { url ->
            if (url.host == "api.github.com") Response(url, 429)
            else Response(url, body = "asset")
        }
        val error = runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, source) }.exceptionOrNull()!!
        assertTrue(GitHubArtifacts.isRateLimited(error))
        assertEquals(cached, GitHubArtifacts.cachedReleases(app, key, defaults, source))
        assertEquals("asset", GitHubArtifacts.fetch("https://github.com/example/drivers/releases/download/v1/bundle.zip", 128) { true }.toString(Charsets.UTF_8))
    }

    @Test fun hiddenSourceHasNoCachedResultsAndMakesNoHttpRequests() {
        successfulTransport()
        GitHubArtifacts.refreshReleases(app, key, defaults, source)
        val previous = source
        RepositorySources.save(app, key, defaults, listOf(previous.copy(hidden = true)))
        var called = false
        GitHubArtifacts.openConnection = { called = true; throw IOException("must not run") }
        assertTrue(GitHubArtifacts.cachedReleases(app, key, defaults, previous).isEmpty())
        assertTrue(runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, previous) }.isFailure)
        assertFalse(called)
    }

    @Test fun hidingDuringFetchRejectsResultsAndLeavesCacheUntouched() {
        successfulTransport()
        val before = GitHubArtifacts.refreshReleases(app, key, defaults, source)
        GitHubArtifacts.openConnection = { url ->
            if (url.path.endsWith("/releases")) {
                RepositorySources.save(app, key, defaults, listOf(source.copy(hidden = true)))
                Response(url, body = "[]")
            } else Response(url, body = """{"id":123,"full_name":"Example/Drivers"}""")
        }
        assertTrue(runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, source) }.isFailure)
        RepositorySources.save(app, key, defaults, listOf(source.copy(hidden = false)))
        assertEquals(before, GitHubArtifacts.cachedReleases(app, key, defaults, source))
    }

    @Test fun reassignedRepositoryNameFailsClosed() {
        successfulTransport()
        GitHubArtifacts.refreshReleases(app, key, defaults, source)
        GitHubArtifacts.openConnection = { url -> Response(url, body = """{"id":999,"full_name":"Example/Drivers"}""") }
        assertTrue(runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, source) }.isFailure)
        assertEquals(123L, source.repositoryId)
    }

    @Test fun missingDigestIsNotDownloadableAndForeignAssetUrlsAreRejected() {
        successfulTransport(release(digest = null))
        assertTrue(GitHubArtifacts.refreshReleases(app, key, defaults, source).single().assets.isEmpty())
        successfulTransport(release(assetUrl = "https://github.com/evil/Drivers/releases/download/v1/bundle.zip"))
        assertTrue(runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, source) }.isFailure)
    }

    @Test fun slashTaggedReleaseDoesNotAbortTheOtherComponentCatalogs() {
        assertEquals("https://github.com/example/drivers/releases/download/Proton/wine/bundle.zip",
            GitHubArtifacts.releaseAssetUrl("Example/Drivers", "Proton/wine", "bundle.zip"))
        for (encodedTag in listOf("Proton/wine", "Proton%2Fwine", "Proton%2fwine")) {
            val url = "https://github.com/Example/Drivers/releases/download/$encodedTag/bundle.zip"
            val proton = release(assetUrl = url).put("tag_name", "Proton/wine")
            val dxvk = release(assetUrl = "https://github.com/Example/Drivers/releases/download/Dxvk-Linux/bundle.zip")
                .put("id", 6).put("tag_name", "Dxvk-Linux")
            GitHubArtifacts.openConnection = { request -> Response(request, body =
                if (request.path.endsWith("/releases")) JSONArray().put(proton).put(dxvk).toString()
                else """{"id":123,"full_name":"Example/Drivers"}""") }

            val results = GitHubArtifacts.refreshReleases(app, key, defaults, source)
            assertEquals(listOf("Proton/wine", "Dxvk-Linux"), results.map { it.tag })
            assertEquals(url, results.first().assets.single().url)
            assertEquals(results, GitHubArtifacts.cachedReleases(app, key, defaults, source))
        }
    }

    @Test fun releaseUrlEncodingPreservesLiteralPlusesAndPercentEscapes() {
        val base = "https://github.com/example/drivers/releases/download/"
        for (url in listOf("release/v1+2/manifest+patched.json", "release%2Fv1%2B2/manifest%2bpatched.json")) {
            assertTrue(GitHubArtifacts.releaseAssetUrlMatches("Example/Drivers", "release/v1+2", "manifest+patched.json", base + url))
        }
        assertFalse(GitHubArtifacts.releaseAssetUrlMatches("Example/Drivers", "release/v1+2", "manifest+patched.json",
            base + "release/v1%202/manifest+patched.json"))
        val literalPercent = GitHubArtifacts.releaseAssetUrl("Example/Drivers", "Proton%2Fwine", "bundle%2Fname.zip")
        assertTrue(GitHubArtifacts.releaseAssetUrlMatches("Example/Drivers", "Proton%2Fwine", "bundle%2Fname.zip", literalPercent))
        assertFalse(GitHubArtifacts.releaseAssetUrlMatches("Example/Drivers", "Proton%2Fwine", "bundle%2Fname.zip",
            base + "Proton%2Fwine/bundle%2Fname.zip"))
    }

    @Test fun slashTagNormalizationStillRejectsForeignOrAmbiguousAssetIdentity() {
        val good = "https://github.com/Example/Drivers/releases/download/Proton/wine/bundle.zip"
        for (url in listOf(
            good.replace("Example/Drivers", "Other/Drivers"),
            good.replace("Proton/wine", "Proton/other"),
            good.replace("Proton/wine", "Proton%252Fwine"),
            good.replace("Proton/wine", "Proton/../wine"),
            good.replace("bundle.zip", "other.zip"),
            good.replace("bundle.zip", "folder%2Fbundle.zip"),
            good.replace("github.com", "github.com.evil.example"),
            good.replace("github.com", "user@github.com"),
            good.replace("https:", "http:"),
            "$good?download=1", "$good#fragment",
        )) assertFalse(url, GitHubArtifacts.releaseAssetUrlMatches("Example/Drivers", "Proton/wine", "bundle.zip", url))

        for (tag in listOf("Proton/../wine", "Proton/./wine", "Proton//wine", "/Proton/wine", "Proton/wine/")) {
            assertTrue(tag, runCatching { GitHubArtifacts.releaseAssetUrl("Example/Drivers", tag, "bundle.zip") }.isFailure)
        }
    }

    @Test fun releaseQueriesUseTheBoundNumericIdentityAndRejectNameRedirects() {
        val requests = ArrayList<String>()
        GitHubArtifacts.openConnection = { url ->
            requests.add(url.path)
            if (url.path.endsWith("/releases")) Response(url, 302, location = "https://api.github.com/repos/example/drivers/releases")
            else Response(url, body = """{"id":123,"full_name":"Example/Drivers"}""")
        }
        assertTrue(runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, source) }.isFailure)
        assertEquals(listOf("/repos/example/drivers", "/repositories/123/releases"), requests)
    }

    @Test fun pinnedReleaseUsesExactTagWithoutScanningRecentPages() {
        val requests = ArrayList<String>()
        GitHubArtifacts.openConnection = { url ->
            requests += url.path
            when (url.path) {
                "/repos/example/drivers" -> Response(url, body = """{"id":123,"full_name":"Example/Drivers"}""")
                "/repositories/123/releases/tags/v1" -> Response(url, body = release().toString())
                else -> throw IOException("Unexpected latest-release lookup")
            }
        }
        assertEquals("v1", GitHubArtifacts.release(app, key, defaults, source, "v1").tag)
        assertEquals(listOf("/repos/example/drivers", "/repositories/123/releases/tags/v1"), requests)
    }

    @Test fun pinnedReleaseRejectsDifferentTagAndHiddenSource() {
        GitHubArtifacts.openConnection = { url ->
            Response(url, body = if (url.path.contains("/releases/tags/")) release().toString()
                else """{"id":123,"full_name":"Example/Drivers"}""")
        }
        assertTrue(runCatching { GitHubArtifacts.release(app, key, defaults, source, "v2") }.isFailure)
        RepositorySources.save(app, key, defaults, listOf(source.copy(hidden = true)))
        GitHubArtifacts.openConnection = { throw AssertionError("Hidden source must not make a request") }
        assertTrue(runCatching { GitHubArtifacts.release(app, key, defaults, source, "v1") }.isFailure)
    }

    @Test fun redirectsCannotEscapeHttpsOrRepositoryAndHaveAHopLimit() {
        val initial = "https://api.github.com/repos/example/drivers"
        for (destination in listOf("http://api.github.com/repos/example/drivers", "https://evil.example/payload", "https://api.github.com/repos/evil/drivers")) {
            var calls = 0
            GitHubArtifacts.openConnection = { url -> calls++; Response(url, 302, location = destination) }
            assertTrue(runCatching { GitHubArtifacts.refreshReleases(app, key, defaults, source) }.isFailure)
            assertEquals(1, calls)
        }
        var calls = 0
        GitHubArtifacts.openConnection = { url -> calls++; Response(url, 302, location = initial) }
        assertTrue(runCatching { GitHubArtifacts.fetch(initial, 128) { it == URI(initial) } }.isFailure)
        assertEquals(4, calls)
    }

    @Test fun unknownLengthMetadataCannotExceedByteLimit() {
        GitHubArtifacts.openConnection = { url -> Response(url, body = "123456789") }
        assertTrue(runCatching { GitHubArtifacts.fetch("https://api.github.com/repos/example/drivers", 8) { true } }.isFailure)
    }

    @Test fun releaseMetadataMustMatchDigestAndSizeAfterCdnRedirect() {
        val data = "{}"
        val bound = RepositorySources.bind(app, key, defaults, source, 123, RepositorySources.generation(app, key, defaults))
        val asset = GitHubArtifacts.Asset(9, "manifest.json", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", "manifest.json"), 2,
            RepositorySources.sha256(data.toByteArray()))
        GitHubArtifacts.openConnection = { url ->
            if (url.host == "github.com") Response(url, 302, location = "https://release-assets.githubusercontent.com/asset?id=9")
            else Response(url, body = data)
        }
        assertEquals(data, GitHubArtifacts.readReleaseAsset(app, key, defaults, bound, "v1", asset).toString(Charsets.UTF_8))
        assertTrue(runCatching { GitHubArtifacts.readReleaseAsset(app, key, defaults, bound, "v1", asset.copy(sha256 = sha)) }.isFailure)
    }

    @Test fun failedStreamingDownloadNeverReplacesExistingBytesOrLeavesPartialFiles() {
        val bound = RepositorySources.bind(app, key, defaults, source, 123, RepositorySources.generation(app, key, defaults))
        val data = "verified archive"
        val asset = GitHubArtifacts.Asset(9, "bundle.zip", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", "bundle.zip"),
            data.length.toLong(), RepositorySources.sha256(data.toByteArray()))
        val folder = File(app.cacheDir, "payloads").apply { mkdirs() }
        val destination = File(folder, "archive.zip").apply { writeText("old") }
        GitHubArtifacts.openConnection = { url -> Response(url, body = "wrong archive") }
        assertTrue(runCatching { GitHubArtifacts.downloadReleaseAsset(app, key, defaults, bound, "v1", asset, destination, 100) }.isFailure)
        assertEquals("old", destination.readText())
        assertEquals(listOf("archive.zip"), folder.list()!!.toList())
        GitHubArtifacts.openConnection = { url -> Response(url, body = data) }
        GitHubArtifacts.downloadReleaseAsset(app, key, defaults, bound, "v1", asset, destination, 100)
        assertEquals(data, destination.readText())
        GitHubArtifacts.openConnection = { throw IOException("must reuse verified download") }
        assertEquals(destination, GitHubArtifacts.downloadReleaseAsset(app, key, defaults, bound, "v1", asset, destination, 100))
    }

    @Test fun hidingDuringStreamingDownloadCannotPublishBytes() {
        val bound = RepositorySources.bind(app, key, defaults, source, 123, RepositorySources.generation(app, key, defaults))
        val asset = GitHubArtifacts.Asset(9, "bundle.zip", GitHubArtifacts.releaseAssetUrl(bound.repository, "v1", "bundle.zip"), 2,
            RepositorySources.sha256("{}".toByteArray()))
        val destination = File(app.cacheDir, "hidden/archive.zip")
        GitHubArtifacts.openConnection = { url ->
            RepositorySources.save(app, key, defaults, listOf(bound.copy(hidden = true)))
            Response(url, body = "{}")
        }
        assertTrue(runCatching { GitHubArtifacts.downloadReleaseAsset(app, key, defaults, bound, "v1", asset, destination, 100) }.isFailure)
        assertFalse(destination.exists())
        assertTrue(destination.parentFile!!.list()!!.isEmpty())
    }
}
