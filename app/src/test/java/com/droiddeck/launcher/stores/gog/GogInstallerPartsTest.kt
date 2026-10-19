package com.droiddeck.launcher.stores.gog

import java.io.File
import java.io.IOException
import java.nio.file.Files
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class GogInstallerPartsTest {
    private fun downloads(json: String) = JSONObject("""{"downloads":$json}""").getJSONObject("downloads")

    @Test fun everyFileOfTheWindowsInstallerIsKeptAndThePartsAreNamedAfterItsSetup() {
        val parts = GogDownloadManager.installerParts(
            downloads(
                """{"installers":[
                    {"os":"windows","language":"en","files":[
                        {"id":"en1installer0","size":1048576,"downlink":"https://api.gog.com/products/1947927225/downlink/installer/en1installer0"},
                        {"id":"en1installer1","size":3786407936,"downlink":"https://api.gog.com/products/1947927225/downlink/installer/en1installer1"}
                    ]},
                    {"os":"linux","files":[{"downlink":"https://example.invalid/linux"}]}
                ]}""",
            ),
            "1947927225",
        )
        assertEquals(listOf("setup_1947927225.exe", "setup_1947927225-1.bin"), parts.map { it.filename })
        assertEquals(listOf(1048576L, 3786407936L), parts.map { it.size })
        assertTrue(parts[1].url.endsWith("en1installer1"))
    }

    @Test fun explicitNamesAreKeptAndUnnamedPartsFollowTheFirstSetupExe() {
        val parts = GogDownloadManager.installerParts(
            downloads(
                """{"installers":[{"os":"windows","files":[
                    {"filename":"setup_witcher_2.1.0.exe","downlink":"https://example.invalid/setup"},
                    {"downlink":"https://example.invalid/data"},
                    {"filename":"readme.bin","downlink":"https://example.invalid/readme"}
                ]}]}""",
            ),
            "1207658924",
        )
        assertEquals(listOf("setup_witcher_2.1.0.exe", "setup_witcher_2.1.0-1.bin", "readme.bin"), parts.map { it.filename })
    }

    @Test fun aSingleManualInstallerIsNamedForTheGameAndSanitised() {
        val parts = GogDownloadManager.installerParts(
            downloads("""{"installers":[{"os":"windows","manualUrl":"https://example.invalid/setup","total_size":42}]}"""),
            "../game id",
        )
        assertEquals(1, parts.size)
        assertEquals("setup_.._game_id.exe", parts[0].filename)
        assertEquals(42L, parts[0].size)
    }

    @Test fun installerPartsStayInsideTheGameFolder() {
        val root = kotlin.io.path.createTempDirectory("gog-installer").toFile()
        try {
            assertEquals(
                File(root, "setup_1.exe").canonicalFile,
                GogDownloadManager.installerDestination(root, "setup_1.exe"),
            )
            assertThrows(IOException::class.java) {
                GogDownloadManager.installerDestination(root, "../outside.exe")
            }
            val outside = File(root.parentFile, "outside-${System.nanoTime()}.bin").apply { writeText("x") }
            try {
                Files.createSymbolicLink(File(root, "setup_1-1.bin").toPath(), outside.toPath())
                assertThrows(IOException::class.java) {
                    GogDownloadManager.installerDestination(root, "setup_1-1.bin")
                }
            } finally {
                outside.delete()
            }
        } finally {
            root.deleteRecursively()
        }
    }

    @Test fun anotherPlatformOrNoDownloadsAddsNothing() {
        assertEquals(0, GogDownloadManager.installerParts(downloads("""{"installers":[{"os":"linux","manualUrl":"https://example.invalid/x"}]}"""), "1").size)
        assertEquals(0, GogDownloadManager.installerParts(downloads("{}"), "1").size)
    }
}
