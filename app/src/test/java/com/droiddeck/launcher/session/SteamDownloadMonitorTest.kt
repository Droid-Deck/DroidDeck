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

    @Test fun followsTheX8664ClientsOwnLibrary() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val steam = File(root, "root/.droiddeck-x64/.local/share/Steam")
            val manifest = manifest(steam, "49520", downloaded = 10, total = 100, flags = 6)
            val monitor = SteamDownloadMonitor()

            assertFalse(monitor.poll(root, now = 1_000L))
            manifest.writeText(appManifest(downloaded = 20, total = 100, flags = 6))
            assertTrue(monitor.poll(root, now = 3_000L))
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

    @Test fun readsBoundCardLibrariesFromTheirHostPath() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val card = File(root, "card").apply { mkdirs() }
            File(root, "root/.local/share/Steam/steamapps").apply { mkdirs() }
                .resolve("libraryfolders.vdf")
                .writeText("\"libraryfolders\" { \"1\" { \"path\" \"/mnt/droiddeck-sd\" } }")
            val manifest = manifest(card, "44", downloaded = 10, total = 100, flags = 6)
            val monitor = SteamDownloadMonitor()
            val mounts = mapOf("/mnt/droiddeck-sd" to card)

            assertFalse(monitor.poll(root, now = 1_000L, mounts = mounts))
            manifest.writeText(appManifest(downloaded = 20, total = 100, flags = 6))
            assertTrue(monitor.poll(root, now = 3_000L, mounts = mounts))
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
            assertTrue(monitor.poll(root, now = 90_000L))
            assertFalse(monitor.poll(root, now = 91_001L))

            contentLog.appendText("[2026-10-06 14:45:02] Current download rate: 27.178 Mbps\n")
            assertTrue(monitor.poll(root, now = 92_000L))

            contentLog.appendText("[2026-10-06 14:45:03] AppID 44 App update changed : None\n")
            assertFalse(monitor.poll(root, now = 93_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun aLongLogIsFollowedFromWhereTheLastPollStopped() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val contentLog = File(root, "root/.local/share/Steam/logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText(buildString {
                    repeat(5_000) { append("[2026-10-06 14:40:00] Detected write gap 64 MB in file \"data_$it.dat\"\r\n") }
                    append("[2026-10-06 14:44:17] AppID 44 App update changed : Running Update,Downloading,\r\n")
                })
            }
            val monitor = SteamDownloadMonitor()

            assertTrue(monitor.poll(root, now = 1_000L))
            contentLog.appendText("[2026-10-06 14:45:03] AppID 44 App update changed : None\r\n")
            assertFalse(monitor.poll(root, now = 2_000L))
            contentLog.appendText("[2026-10-06 14:46:00] AppID 45 App update changed : Running Update,Downloading,\r\n")
            assertTrue(monitor.poll(root, now = 3_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun theOtherClientsLogIsReadFromItsStart() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            File(root, "root/.local/share/Steam/logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText("[2026-10-06 14:00:00] Client version: 1\n" + "[2026-10-06 14:00:01] nothing to download\n".repeat(20))
                setLastModified(10_000L)
            }
            val monitor = SteamDownloadMonitor()
            assertFalse(monitor.poll(root, now = 1_000L))

            File(root, "root/.droiddeck-x64/.local/share/Steam/logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText("[2026-10-06 14:44:17] AppID 44 App update changed : Running Update,Downloading,Staging,\n" +
                    "[2026-10-06 14:44:18] Current download rate: 0.000 Mbps\n".repeat(30))
                setLastModified(20_000L)
            }
            assertTrue(monitor.poll(root, now = 2_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun theRunningClientsLogIsFollowedWhenTheOtherOneIsNewer() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            File(root, "root/.local/share/Steam/logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText("[2026-10-06 14:44:17] AppID 44 App update changed : Running Update,Downloading,Staging,\n")
                setLastModified(10_000L)
            }
            File(root, "root/.droiddeck-x64/.local/share/Steam/logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText("[2026-10-06 14:00:00] Client version: 1\n")
                setLastModified(20_000L)
            }
            val monitor = SteamDownloadMonitor()
            assertTrue(monitor.poll(root, now = 1_000L, client = "arm64"))
            assertTrue(monitor.poll(root, now = 2_000L, client = "arm64"))
            assertFalse(SteamDownloadMonitor().poll(root, now = 1_000L, client = "x86_64"))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun receivedTrafficBridgesSteamRateLines() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val contentLog = File(root, "root/.local/share/Steam/logs/content_log.txt").apply {
                check(parentFile?.mkdirs() != false)
                writeText("[2026-10-06 16:06:30] AppID 44 App update changed : Running Update,Downloading,\n")
            }
            val monitor = SteamDownloadMonitor()
            var received = 1_000_000L
            var now = 1_000L

            assertTrue(monitor.poll(root, now, received))
            while (now < 300_000L) {
                now += 2_000L
                received += 8_000_000L
                assertTrue(monitor.poll(root, now, received))
            }
            assertTrue(monitor.poll(root, now + 90_000L, received))
            assertFalse(monitor.poll(root, now + 92_001L, received))

            contentLog.appendText("[2026-10-06 16:12:00] AppID 44 App update changed : None\n")
            assertFalse(monitor.poll(root, now + 94_000L, received + 8_000_000L))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun trafficAloneIsNotADownload() {
        val root = Files.createTempDirectory("droiddeck-steam-download-").toFile()
        try {
            val monitor = SteamDownloadMonitor()
            assertFalse(monitor.poll(root, now = 1_000L, receivedBytes = 0L))
            assertFalse(monitor.poll(root, now = 3_000L, receivedBytes = 50_000_000L))
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

    @Test fun theClientIsPendingUntilItIsInstalledAndStamped() {
        val root = Files.createTempDirectory("droiddeck-steam-client-").toFile()
        try {
            val steam = File(root, "root/.local/share/Steam")
            assertTrue(SteamDownloadMonitor.clientInstallPending(root))
            File(steam, "steamrtarm64").mkdirs()
            File(steam, "steamrtarm64/steam").writeText("client")
            assertTrue(SteamDownloadMonitor.clientInstallPending(root))
            File(steam, "package").mkdirs()
            File(steam, "package/droiddeck-installed").writeText("1791686101\n")
            assertFalse(SteamDownloadMonitor.clientInstallPending(root))
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun anOlderStampCountsAndARepairRequestIsPending() {
        val root = Files.createTempDirectory("droiddeck-steam-client-").toFile()
        try {
            val steam = File(root, "root/.local/share/Steam")
            File(steam, "steamrtarm64").mkdirs()
            File(steam, "steamrtarm64/steam").writeText("client")
            File(steam, "package").mkdirs()
            File(steam, "package/bannerlator-installed").writeText("1\n")
            assertFalse(SteamDownloadMonitor.clientInstallPending(root))
            File(root, "root/.bl-steam-repair").writeText("1\n")
            assertTrue(SteamDownloadMonitor.clientInstallPending(root))
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
