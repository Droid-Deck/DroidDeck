package com.droiddeck.launcher.update

import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.artifact.RepositorySources
import com.droiddeck.launcher.artifact.GitHubArtifacts
import com.droiddeck.launcher.R
import com.droiddeck.launcher.update.AppUpdates.Channel
import com.droiddeck.launcher.update.AppUpdates.Follow
import com.droiddeck.launcher.update.AppUpdates.Installed
import com.droiddeck.launcher.update.AppUpdates.Offer
import com.droiddeck.launcher.update.AppUpdates.PreviewChange
import com.droiddeck.launcher.update.AppUpdates.PreviewHistoryKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.IOException
import java.net.HttpURLConnection

@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    private fun apk(versionCode: Int = 9, signer: String = BuildConfig.RELEASE_SIGNER) = AppUpdates.Apk(
        "DroidDeck.apk",
        "https://github.com/Droid-Deck/DroidDeck-CI/releases/download/t/DroidDeck.apk",
        10,
        "a".repeat(64),
        "com.droiddeck.launcher",
        versionCode,
        signer,
    )

    private fun release(
        commit: String,
        pr: Int = 0,
        version: String? = null,
        versionCode: Int = 9,
        withApk: Boolean = true,
        signer: String = BuildConfig.RELEASE_SIGNER,
    ) = AppUpdates.Release(
        "t",
        "title",
        "",
        commit,
        pr,
        version,
        versionCode,
        0L,
        "",
        if (withApk) apk(versionCode, signer) else null,
    )

    private val stable = release(
        "aaaaaaa1111111111111111111111111111111",
        version = "0.2.0",
    )
    private val catalog = AppUpdates.Catalog(
        stable = stable,
        preview = release("bbbbbbb"),
        tests = listOf(release("ccccccc2222222222222222222222222222222", pr = 93)),
        checkedAt = 0L,
    )

    private fun me(
        commit: String,
        pr: Int = 0,
        version: String = "0.2.0",
        versionCode: Int = 9,
        ciBuild: Boolean = true,
        updatable: Boolean = true,
        signers: Set<String> = if (updatable) setOf(BuildConfig.RELEASE_SIGNER) else emptySet(),
        lineage: Set<String> = emptySet(),
    ) = Installed(commit, pr, version, versionCode, updatable, ciBuild = ciBuild,
        signerDigests = signers, signerLineageDigests = lineage)

    private fun change(commit: String, title: String, publishedAt: Long) =
        PreviewChange(commit, title, "Summary for $title", publishedAt)

    private val recentPreview = listOf(
        change("1".repeat(40), "Newest", 300L),
        change("2".repeat(40), "Middle", 200L),
        change("3".repeat(40), "Oldest", 100L),
    )

    @Test
    fun theStableBuildIsCurrentOnStable() {
        assertEquals(Offer.CURRENT, AppUpdates.offer(catalog, Follow(Channel.STABLE), me(stable.commit)))
    }

    @Test
    fun aPreviewPastStableIsAheadNotBehind() {
        assertEquals(Offer.AHEAD, AppUpdates.offer(catalog, Follow(Channel.STABLE), me("ddddddd999")))
    }

    @Test
    fun aNewerStableVersionIsAnUpdate() {
        assertEquals(
            Offer.UPDATE,
            AppUpdates.offer(catalog, Follow(Channel.STABLE), me("eeeeeee999", version = "0.1.9")),
        )
    }

    @Test
    fun previewMatchesItsShortShaAndOffersTheNewest() {
        assertEquals(Offer.CURRENT, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me("bbbbbbb0123456789")))
        assertEquals(Offer.UPDATE, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me(stable.commit)))
    }

    @Test
    fun testBuildsUpdateWithinTheirPrAndSwitchOtherwise() {
        assertEquals(Offer.UPDATE, AppUpdates.offer(catalog, Follow(Channel.TEST, 93), me("fffffff000", pr = 93)))
        assertEquals(Offer.SWITCH, AppUpdates.offer(catalog, Follow(Channel.TEST, 93), me(stable.commit)))
        assertEquals(Offer.SWITCH, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me("ccccccc2", pr = 93)))
        assertEquals(Offer.GONE, AppUpdates.offer(catalog, Follow(Channel.TEST, 12), me("ccccccc2", pr = 12)))
    }

    @Test
    fun aTestBuildIsNeverThePreviewBuildOfTheSameCommit() {
        assertEquals(Offer.SWITCH, AppUpdates.offer(catalog, Follow(Channel.NIGHTLY), me("bbbbbbb", pr = 5)))
    }

    @Test
    fun versionsCompareByNumberNotText() {
        assertTrue(AppUpdates.compareVersions("0.10.0", "0.9.2") > 0)
        assertTrue(AppUpdates.compareVersions("v0.2.1", "0.2") > 0)
        assertEquals(0, AppUpdates.compareVersions("0.2", "0.2.0"))
    }

    @Test
    fun aVersionCodeDowngradeIsBlockedBeforeDownload() {
        val older = release("ddddddd", versionCode = 8)
        assertNotNull(AppUpdates.installBlock(older, me("eeeeeee", versionCode = 9)))
        assertNull(AppUpdates.installBlock(stable, me("eeeeeee", versionCode = 9)))
    }

    @Test
    fun aMissingVariantIsBlockedBeforeDownload() {
        val missing = release("ddddddd", withApk = false)
        assertNotNull(AppUpdates.installBlock(missing, me("eeeeeee")))
    }

    @Test
    fun signerPreflightBlocksLocalBuildsButDefersTrustedRotationToApkVerification() {
        val rotated = "0".repeat(64)
        val candidate = release("ddddddd", signer = rotated)
        val trusted = me("eeeeeee", signers = setOf(BuildConfig.RELEASE_SIGNER))
        assertEquals(AppUpdates.SignerCompatibility.NEEDS_APK_LINEAGE,
            AppUpdates.signerCompatibility(candidate.apk!!, trusted))
        assertNull(AppUpdates.installBlock(candidate, trusted))

        val local = me("eeeeeee", updatable = false, signers = setOf("f".repeat(64)))
        assertEquals(AppUpdates.SignerCompatibility.INCOMPATIBLE,
            AppUpdates.signerCompatibility(candidate.apk!!, local))
        assertNotNull(AppUpdates.installBlock(candidate, local))
        val localCandidate = release("ddddddd", signer = "f".repeat(64))
        assertEquals(AppUpdates.SignerCompatibility.INCOMPATIBLE,
            AppUpdates.signerCompatibility(localCandidate.apk!!, local))
        assertNotNull(AppUpdates.installBlock(localCandidate, local))
    }

    @Test
    fun installedTrustAcceptsTheEmbeddedSignerInLineageButRejectsASibling() {
        val root = "1".repeat(64)
        val embedded = "2".repeat(64)
        val sibling = "3".repeat(64)
        assertTrue(AppUpdates.trustedInstalledIdentity(
            SelfInstaller.VerifiedIdentity(setOf(root), setOf(root, embedded)), embedded,
        ))
        assertFalse(AppUpdates.trustedInstalledIdentity(
            SelfInstaller.VerifiedIdentity(setOf(sibling), setOf(root, sibling)), embedded,
        ))
    }

    @Test
    fun completedSignerVerificationPublishesANewInstalledSnapshot() {
        val before = AppUpdates.signerRevision
        val identity = SelfInstaller.VerifiedIdentity(setOf(BuildConfig.RELEASE_SIGNER), emptySet())
        try {
            AppUpdates.applyInstalledSignerIdentity(identity, true)
            val installed = AppUpdates.installed()
            assertEquals(before + 1, installed.signerRevision)
            assertTrue(installed.updatable)
            assertEquals(identity.current, installed.signerDigests)
        } finally {
            AppUpdates.applyInstalledSignerIdentity(SelfInstaller.VerifiedIdentity(emptySet(), emptySet()), false)
        }
    }

    @Test
    fun previewHistoryCountsOnlyAnUnambiguousPublishedPreviewCommit() {
        val withHistory = AppUpdates.Catalog(stable, release("1111111"), emptyList(), 0L, recentPreview)
        val result = AppUpdates.previewHistory(withHistory, me("2".repeat(40), version = "0.3.0"))!!
        assertEquals(PreviewHistoryKind.BEHIND, result.kind)
        assertEquals(1, result.buildsBehind)
        assertEquals(listOf("Newest"), result.changes.map { it.title })
    }

    @Test
    fun currentPreviewHasNoRedundantChangeRows() {
        val withHistory = AppUpdates.Catalog(stable, release("1111111"), emptyList(), 0L, recentPreview)
        val result = AppUpdates.previewHistory(withHistory, me("1".repeat(40), version = "0.3.0"))!!
        assertEquals(PreviewHistoryKind.CURRENT, result.kind)
        assertTrue(result.changes.isEmpty())
    }

    @Test
    fun missingOrAmbiguousPreviewCommitsUseNeutralRecentHistory() {
        val withHistory = AppUpdates.Catalog(stable, release("1111111"), emptyList(), 0L, recentPreview)
        assertEquals(
            PreviewHistoryKind.RECENT,
            AppUpdates.previewHistory(withHistory, me("9".repeat(40), version = "0.3.0"))!!.kind,
        )

        val ambiguous = listOf(
            change("abcdef0" + "1".repeat(33), "First", 300L),
            change("abcdef0" + "2".repeat(33), "Second", 200L),
        )
        val ambiguousCatalog = AppUpdates.Catalog(stable, release("abcdef0"), emptyList(), 0L, ambiguous)
        val result = AppUpdates.previewHistory(ambiguousCatalog, me("abcdef0", version = "0.3.0"))!!
        assertEquals(PreviewHistoryKind.RECENT, result.kind)
        assertEquals(2, result.changes.size)
    }

    @Test
    fun prStableAndLocalBuildsDoNotGetPreviewDistance() {
        val withHistory = AppUpdates.Catalog(stable, release("1111111"), emptyList(), 0L, recentPreview)
        assertEquals(
            PreviewHistoryKind.RECENT,
            AppUpdates.previewHistory(withHistory, me("2".repeat(40), pr = 93, version = "0.3.0"))!!.kind,
        )
        assertEquals(
            PreviewHistoryKind.RECENT,
            AppUpdates.previewHistory(withHistory, me(stable.commit))!!.kind,
        )
        assertNull(AppUpdates.previewHistory(withHistory, me("2".repeat(40), version = "0.3.0", ciBuild = false)))
        assertEquals(
            PreviewHistoryKind.RECENT,
            AppUpdates.previewHistory(withHistory, me("2".repeat(40), version = "0.1.0"))!!.kind,
        )
    }

    @Test
    fun cachedPreviewHistorySurvivesRoundTrip() {
        val withHistory = AppUpdates.Catalog(stable, release("1111111"), emptyList(), 123L, recentPreview)
        val cached = AppUpdates.readCachedCatalog(AppUpdates.writeCatalog(withHistory))
        assertEquals(123L, cached.checkedAt)
        assertEquals(3, cached.recentPreviews.size)
        assertEquals("Newest", cached.recentPreviews.first().title)
        assertEquals(recentPreview.first().commit, cached.recentPreviews.first().commit)
    }

    @Test
    fun oldCachedCatalogWithoutPreviewHistoryRemainsReadable() {
        val cached = AppUpdates.readCachedCatalog(JSONObject().put("tests", JSONArray()).put("checkedAt", 123L))
        assertEquals(123L, cached.checkedAt)
        assertTrue(cached.recentPreviews.isEmpty())
    }

    private fun publishedCatalog(signer: String = BuildConfig.RELEASE_SIGNER): JSONObject {
        val raw = """
            {
              "schema": 1,
              "sourceRepo": "Droid-Deck/DroidDeck",
              "ciRepo": "Droid-Deck/DroidDeck-CI",
              "stable": null,
              "preview": {
                "tag": "main-deadbee",
                "title": "Preview",
                "summary": "",
                "commit": "deadbee1234567890",
                "pr": 0,
                "version": null,
                "versionCode": 9,
                "publishedAt": 1,
                "url": "https://github.com/Droid-Deck/DroidDeck-CI/releases/tag/main-deadbee",
                "apks": {
                  "com.droiddeck.launcher": {
                    "name": "DroidDeck-main-deadbee.apk",
                    "url": "https://github.com/Droid-Deck/DroidDeck-CI/releases/download/main-deadbee/DroidDeck-main-deadbee.apk",
                    "size": 10,
                    "sha256": "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                    "packageName": "com.droiddeck.launcher",
                    "versionCode": 9,
                    "signerSha256": "__SIGNER__"
                  }
                }
              },
              "tests": []
            }
        """.trimIndent().replace("__SIGNER__", signer)
        return JSONObject(raw)
    }

    @Test
    fun publishedCatalogSelectsThisPackagesApk() {
        val parsed = AppUpdates.readPublishedCatalog(
            publishedCatalog(),
            "com.droiddeck.launcher",
            123L,
        )
        assertEquals("main-deadbee", parsed.preview!!.tag)
        assertEquals("com.droiddeck.launcher", parsed.preview!!.apk!!.packageName)
        assertEquals(9, parsed.preview!!.apk!!.versionCode)
        assertNull(parsed.preview!!.version)
        assertEquals(123L, parsed.checkedAt)
        assertTrue(parsed.recentPreviews.isEmpty())
    }

    @Test
    fun publishedCatalogReadsOptionalPreviewHistoryAndDeduplicates() {
        val first = "a".repeat(40)
        val newest = "b".repeat(40)
        val raw = publishedCatalog().put(
            "recentPreviews",
            JSONArray()
                .put(JSONObject().put("commit", first).put("title", "Older").put("summary", "Old").put("publishedAt", 1L))
                .put(JSONObject().put("commit", newest).put("title", "Newest").put("summary", "New").put("publishedAt", 2L))
                .put(JSONObject().put("commit", first).put("title", "Duplicate").put("summary", "Again").put("publishedAt", 3L))
                .put(JSONObject().put("commit", "shortsha").put("title", "Invalid")),
        )
        val parsed = AppUpdates.readPublishedCatalog(raw, "com.droiddeck.launcher", 123L)
        assertEquals(2, parsed.recentPreviews.size)
        assertEquals("Duplicate", parsed.recentPreviews.first().title)
        assertEquals(newest, parsed.recentPreviews.last().commit)
    }

    @Test
    fun publishedCatalogRejectsMalformedSigner() {
        try {
            AppUpdates.readPublishedCatalog(
                publishedCatalog("not-a-sha256"),
                "com.droiddeck.launcher",
                123L,
            )
            fail("wrong signer should be rejected")
        } catch (_: IOException) {
        }
    }

    @Test
    fun officialCatalogCanDeclareARotatedSignerForApkLineageVerification() {
        val rotated = "0".repeat(64)
        val parsed = AppUpdates.readPublishedCatalog(
            publishedCatalog(rotated),
            "com.droiddeck.launcher",
            123L,
        )
        val release = parsed.preview!!
        assertEquals(rotated, release.apk!!.signerSha256)
        val trusted = me("eeeeeee", signers = setOf(BuildConfig.RELEASE_SIGNER))
        assertEquals(AppUpdates.SignerCompatibility.NEEDS_APK_LINEAGE,
            AppUpdates.signerCompatibility(release.apk!!, trusted))
        assertNull(AppUpdates.installBlock(release, trusted))
    }

    @Test
    fun customCatalogIsBoundToTheSelectedRepositoryAndCarriesItsSource() {
        val raw = publishedCatalog("0".repeat(64))
            .put("sourceRepo", "wallentx/DroidDeck")
            .put("ciRepo", "wallentx/DroidDeck-CI")
        raw.getJSONObject("preview").getJSONObject("apks").getJSONObject("com.droiddeck.launcher")
            .put("url", "https://github.com/wallentx/DroidDeck-CI/releases/download/main-deadbee/DroidDeck-main-deadbee.apk")
        val parsed = AppUpdates.readPublishedCatalog(
            raw, "com.droiddeck.launcher", 123L,
            expectedSourceRepo = null,
            expectedCiRepo = "wallentx/DroidDeck-CI",
            sourceId = "source-1",
        )
        assertEquals("source-1", parsed.sourceId)
        assertEquals("0".repeat(64), parsed.preview!!.apk!!.signerSha256)
    }

    @Test
    fun legacyOfficialCacheCannotBecomeACustomSourcesCatalog() {
        val context = RuntimeEnvironment.getApplication()
        val official = AppUpdates.sources(context).single()
        val custom = RepositorySources.source("wallentx/droiddeck-ci")
        AppUpdates.saveSources(context, listOf(official.copy(hidden = true), custom))
        context.getSharedPreferences("app_updates", 0).edit()
            .putString("catalog", AppUpdates.writeCatalog(catalog).toString())
            .commit()
        assertNull(AppUpdates.cached(context, custom.id))
        AppUpdates.saveSources(context, AppUpdates.sources(context).map {
            if (it.id == official.id) it.copy(hidden = false) else it
        })
        assertNull(AppUpdates.cached(context, official.id))
        assertFalse(context.getSharedPreferences("app_updates", 0).contains("catalog"))

        val main = AppUpdates.saveSources(context, AppUpdates.sources(context).map {
            if (it.id == official.id) it.copy(catalogBranch = "main") else it
        }).single { it.id == official.id }
        context.getSharedPreferences("app_updates", 0).edit()
            .putString("catalog:${official.id}", AppUpdates.writeCatalog(catalog).toString())
            .commit()
        assertNotNull(AppUpdates.cached(context, official.id))
        assertTrue(context.getSharedPreferences("app_updates", 0).contains(AppUpdates.catalogCacheKey(main)))
        assertFalse(context.getSharedPreferences("app_updates", 0).contains("catalog:${official.id}"))
    }

    @Test
    fun catalogCachesAreIsolatedByBranchAndReuseTheSameSelectionWhenReturning() {
        val context = RuntimeEnvironment.getApplication()
        val original = AppUpdates.sources(context).single()
        val preferences = context.getSharedPreferences("app_updates", 0)
        preferences.edit().putString(
            AppUpdates.catalogCacheKey(original), AppUpdates.writeCatalog(catalog).toString(),
        ).commit()
        AppUpdates.setFollow(context, Follow(Channel.TEST, 93, original.id))

        val dev = AppUpdates.saveSources(context, listOf(original.copy(catalogBranch = "dev"))).single()
        assertNull(AppUpdates.cached(context, dev.id))
        assertEquals(Follow(Channel.TEST, 93, original.id), AppUpdates.follow(context, null))
        preferences.edit().putString(
            AppUpdates.catalogCacheKey(dev), AppUpdates.writeCatalog(catalog).toString(),
        ).commit()
        assertNotNull(AppUpdates.cached(context, dev.id))

        val restored = AppUpdates.saveSources(context, listOf(dev.copy(catalogBranch = null))).single()
        assertNotNull(AppUpdates.cached(context, restored.id))
        assertEquals(Follow(Channel.TEST, 93, original.id), AppUpdates.follow(context, null))
    }

    @Test
    fun branchChangeAfterParsingRejectsTheStaleCatalogBeforeCacheCommit() {
        val context = RuntimeEnvironment.getApplication()
        val source = AppUpdates.sources(context).single()
        val preferences = context.getSharedPreferences("app_updates", 0)
        val previous = AppUpdates.writeCatalog(AppUpdates.Catalog(stable, null, emptyList(), 17L)).toString()
        preferences.edit().putString(AppUpdates.catalogCacheKey(source), previous).commit()
        val generation = RepositorySources.generation(context, AppUpdates.SOURCE_SCOPE, AppUpdates.sourceBuiltIns())
        val dev = AppUpdates.saveSources(context, listOf(source.copy(catalogBranch = "dev"))).single()

        assertTrue(runCatching { AppUpdates.cacheCatalog(context, source, generation, catalog) }.isFailure)
        assertEquals(previous, preferences.getString(AppUpdates.catalogCacheKey(source), null))
        assertFalse(preferences.contains(AppUpdates.catalogCacheKey(dev)))
    }

    @Test
    fun missingCatalogAndRateLimitReportDifferentErrorsAndPreserveCachedUpdate() {
        val context = RuntimeEnvironment.getApplication()
        val source = AppUpdates.saveSources(
            context, listOf(AppUpdates.sources(context).single().copy(catalogBranch = "dev")),
        ).single()
        val cachedJson = AppUpdates.writeCatalog(catalog).toString()
        val prefs = context.getSharedPreferences("app_updates", 0)
        prefs.edit().putString(AppUpdates.catalogCacheKey(source), cachedJson).commit()
        val production = GitHubArtifacts.openConnection
        try {
            for (status in listOf(404, 429)) {
                GitHubArtifacts.clearRateLimits()
                GitHubArtifacts.openConnection = { url -> object : HttpURLConnection(url) {
                    override fun connect() {}
                    override fun disconnect() {}
                    override fun usingProxy() = false
                    override fun getResponseCode() = status
                } }
                val error = runCatching { AppUpdates.refresh(context, source.id) }.exceptionOrNull()!!
                if (status == 404) assertEquals(context.getString(R.string.sources_update_catalog_missing), error.message)
                else assertTrue(GitHubArtifacts.isRateLimited(error))
                assertEquals(cachedJson, prefs.getString(AppUpdates.catalogCacheKey(source), null))
                assertEquals(catalog.stable!!.tag, AppUpdates.cached(context, source.id)!!.stable!!.tag)
            }
            GitHubArtifacts.clearRateLimits()
            GitHubArtifacts.openConnection = { throw IOException("offline") }
            assertTrue(runCatching { AppUpdates.refresh(context, source.id) }.exceptionOrNull() is IOException)
            assertEquals(cachedJson, prefs.getString(AppUpdates.catalogCacheKey(source), null))
            assertEquals(catalog.stable!!.tag, AppUpdates.cached(context, source.id)!!.stable!!.tag)
        } finally {
            GitHubArtifacts.openConnection = production
            GitHubArtifacts.clearRateLimits()
        }
    }
}
