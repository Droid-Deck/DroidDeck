package com.droiddeck.launcher.storage

import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SystemCachesTest {
    @get:Rule val tmp = TemporaryFolder()

    private val now = 10L * 24 * 60 * 60 * 1000
    private val old = now - SystemCaches.TEMP_IDLE_MS - 1000

    private fun file(path: String, modified: Long) = File(tmp.root, path).apply { parentFile!!.mkdirs(); writeText("x"); setLastModified(modified) }

    @Test fun onlyIdleVisibleTemporaryFilesAreCleared() {
        file("rootfs/tmp/old.deb", old)
        file("rootfs/tmp/oldDir/a", old).parentFile!!.setLastModified(old)
        file("rootfs/tmp/busyDir/a", old)
        file("rootfs/tmp/busyDir/b", now - 1000).parentFile!!.setLastModified(old)
        file("rootfs/tmp/.X11-unix/X0", old).parentFile!!.setLastModified(old)
        file("rootfs/tmp/fresh.log", now)
        val found = SystemCaches.find(File(tmp.root, "rootfs"), emptyList(), now)
        assertEquals(listOf("old.deb", "oldDir"), found.getValue(SystemCache.TEMP).map { it.name }.sorted())
    }

    @Test fun packagesAndSteamCachesAreFound() {
        file("rootfs/var/cache/apt/archives/git_1_arm64.deb", now)
        file("rootfs/var/cache/apt/archives/lock", now)
        file("rootfs/var/cache/apt/archives/partial/perl.deb", now)
        val steam = File(tmp.root, "rootfs/root/.local/share/Steam")
        file("rootfs/root/.local/share/Steam/config/htmlcache/Cache/data_0", now)
        file("rootfs/root/.local/share/Steam/steamapps/corrupt/1_2_ab_corrupt.chunk", now)
        val found = SystemCaches.find(File(tmp.root, "rootfs"), listOf(steam, File(tmp.root, "missing")), now)
        assertEquals(listOf("git_1_arm64.deb", "perl.deb"), found.getValue(SystemCache.PACKAGES).map { it.name }.sorted())
        assertEquals(listOf(File(steam, "config/htmlcache")), found.getValue(SystemCache.STEAM_WEB))
        assertEquals(listOf(File(steam, "steamapps/corrupt")), found.getValue(SystemCache.STEAM_CORRUPT))
    }
}
