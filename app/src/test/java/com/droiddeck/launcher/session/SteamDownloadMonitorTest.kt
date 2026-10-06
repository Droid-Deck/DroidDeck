package com.droiddeck.launcher.session

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamDownloadMonitorTest {
    @Test fun reportsOnlyProgressingIncompleteManifests() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val steam = File(root, "root/.local/share/Steam")
            val manifest = manifest(steam, "42", downloaded = 10, total = 100, flags = 6)
            val monitor = SteamDownloadMonitor()

            assertFalse(monitor.poll(root, now = 1_000L))
            manifest.writeText(appManifest(downloaded = 20, total = 100, flags = 6))
            assertTrue(monitor.poll(root, now = 3_000L))
            assertTrue(monitor.poll(root, now = 9_000L))
            assertFalse(monitor.poll(root, now = 11_001L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun findsDownloadsInAdditionalSteamLibraries() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val library = File(root, "mnt/droiddeck-sd").apply { mkdirs() }
            File(root, "root/.local/share/Steam/steamapps").apply { mkdirs() }
                .resolve("libraryfolders.vdf")
                .writeText("\"libraryfolders\" { \"1\" { \"path\" \"/mnt/droiddeck-sd\" } }")
            val manifest = manifest(library, "43", downloaded = 1, total = 2, flags = 6)
            val monitor = SteamDownloadMonitor()

            assertFalse(monitor.poll(root, now = 1_000L))
            manifest.writeText(appManifest(downloaded = 2, total = 2, flags = 4))
            assertFalse(monitor.poll(root, now = 3_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    private fun manifest(root: File, appId: String, downloaded: Long, total: Long, flags: Int): File {
        val file = File(root, "steamapps/appmanifest_$appId.acf")
        check(file.parentFile?.mkdirs() != false)
        file.writeText(appManifest(downloaded, total, flags))
        return file
    }

    private fun appManifest(downloaded: Long, total: Long, flags: Int) =
        "\"AppState\" { \"StateFlags\" \"$flags\" \"BytesDownloaded\" \"$downloaded\" \"BytesToDownload\" \"$total\" }"
}
