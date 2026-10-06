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

    @Test fun followsSteamContentActivityWhenManifestProgressIsNotFlushed() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val steam = File(root, "root/.local/share/Steam")
            manifest(steam, "44", downloaded = 0, total = 10_000, flags = 10)
            val contentLog = File(steam, "logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText("[2026-10-06 14:44:17] AppID 44 App update changed : Running Update,Downloading,Staging,\n")
            }
            val monitor = SteamDownloadMonitor()

            assertTrue(monitor.poll(root, now = 1_000L))
            assertTrue(monitor.poll(root, now = 45_000L))
            assertFalse(monitor.poll(root, now = 46_001L))

            contentLog.appendText("[2026-10-06 14:45:02] Current download rate: 27.178 Mbps\n")
            assertTrue(monitor.poll(root, now = 47_000L))

            contentLog.appendText("[2026-10-06 14:45:03] AppID 44 App update changed : None\n")
            assertFalse(monitor.poll(root, now = 48_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun newSteamSessionClearsStaleContentActivity() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val log = File(root, "root/.local/share/Steam/logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText("[old] AppID 44 App update changed : Running Update,Downloading,\n")
                appendText("[new] Client version: 1\n")
            }

            val monitor = SteamDownloadMonitor()
            assertFalse(monitor.poll(root, now = 1_000L))

            log.appendText("[new] AppID 44 App update changed : Running Update,Downloading,\n")
            assertTrue(monitor.poll(root, now = 2_000L))
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
