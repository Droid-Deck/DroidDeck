package com.droiddeck.launcher.update

import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.update.AppUpdates.Channel
import com.droiddeck.launcher.update.AppUpdates.Follow
import com.droiddeck.launcher.update.AppUpdates.Installed
import com.droiddeck.launcher.update.AppUpdates.Offer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.json.JSONObject
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.IOException

@RunWith(RobolectricTestRunner::class)
class AppUpdatesTest {
    private fun apk(versionCode: Int = 9) = AppUpdates.Apk(
        "DroidDeck.apk",
        "https://github.com/Droid-Deck/DroidDeck-CI/releases/download/t/DroidDeck.apk",
        10,
        "a".repeat(64),
        "com.droiddeck.launcher",
        versionCode,
        BuildConfig.RELEASE_SIGNER,
    )

    private fun release(
        commit: String,
        pr: Int = 0,
        version: String? = null,
        versionCode: Int = 9,
        withApk: Boolean = true,
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
        if (withApk) apk(versionCode) else null,
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
    ) = Installed(commit, pr, version, versionCode, updatable = true)

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
    }

    @Test
    fun publishedCatalogRejectsAnotherSigner() {
        try {
            AppUpdates.readPublishedCatalog(
                publishedCatalog("0".repeat(64)),
                "com.droiddeck.launcher",
                123L,
            )
            fail("wrong signer should be rejected")
        } catch (_: IOException) {
        }
    }
}
