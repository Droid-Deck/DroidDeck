package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UserAppsFeedTest {
    private val feed = "https://example.com/apps/linux/latest-linux.yml"
    private val sha = "ajcSve2Wq1CgocS5zWlmdfb0pI0C+JqNvbFx6Vm9j9/fW1Q/u2dnnNvNGmi0gulEXp7tnRkYCCc7rfwtjKcoNw=="

    private fun body(path: String = "App-1.2.0-7.AppImage", hash: String = sha) = """
        version: 1.2.0-7
        files:
          - url: App-1.2.0-7.AppImage
            sha512: $hash
            size: 100
          - url: App_1.2.0-7_amd64.deb
            sha512: other
        path: $path
        sha512: $hash
        releaseDate: '2026-11-15T18:08:09.118Z'
    """.trimIndent()

    @Test fun feedGivesTheAppImageRelease() {
        val release = UserApps.parseFeed(feed, body())!!
        assertEquals("1.2.0-7", release.tag)
        assertEquals("App-1.2.0-7.AppImage", release.asset)
        assertEquals("https://example.com/apps/linux/App-1.2.0-7.AppImage", release.url)
        assertEquals("2026-11-15T18:08:09.118Z", release.published)
        assertEquals(128, release.sha512!!.length)
        assertEquals("6a3712bded96ab50a0a1c4b9cd696675f6f4a48d02f89a8dbdb171e959bd8fdfdf5b543fbb67679cdbcd1a68b482e9445e9eed9d191808273badfc2d8ca72837", release.sha512)
        assertNull(release.sha256)
    }

    @Test fun feedWithoutAnAppImageOrHashIsRefused() {
        assertNull(UserApps.parseFeed(feed, body(path = "App_1.2.0-7_amd64.deb")))
        assertNull(UserApps.parseFeed(feed, body(path = "../App.AppImage")))
        assertNull(UserApps.parseFeed(feed, body(path = "sub/App.AppImage")))
        assertNull(UserApps.parseFeed(feed, body(hash = "c2hvcnQ=")))
        assertNull(UserApps.parseFeed("http://example.com/latest-linux.yml", body()))
        assertNull(UserApps.parseFeed(feed, "path: App.AppImage\n"))
    }

    @Test fun curseForgeIsFoundByItsFeed() {
        val other = UserApps.App("appimage:a", UserApps.Kind.APPIMAGE, "CurseForge", null, null, AppImageManager.LAUNCHER, emptyList())
        val official = UserApps.App("appimage:b", UserApps.Kind.APPIMAGE, "Mods", null, null, AppImageManager.LAUNCHER, emptyList(), feed = CurseForgeApp.FEED)
        assertEquals("appimage:b", CurseForgeApp.find(listOf(other, official))?.key)
        assertNull(CurseForgeApp.find(listOf(other)))
    }

    @Test fun olderOrEqualFeedReleasesAreNotOffered() {
        val release = UserApps.parseFeed(feed, body())!!
        assertTrue(UserApps.isNewer(release, "2026-11-01T00:00:00.000Z", "1.0"))
        assertFalse(UserApps.isNewer(release, "2099-01-01T00:00:00.000Z", "9.9"))
        assertFalse(UserApps.isNewer(release, "", release.tag))
        assertTrue(UserApps.isNewer(release, "", "0.1"))
    }
}
