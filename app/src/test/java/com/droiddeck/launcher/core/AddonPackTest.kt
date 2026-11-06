package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.luben.zstd.ZstdOutputStream
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AddonPackTest {
    private val context: Context = ApplicationProvider.getApplicationContext()
    private val root get() = File(context.filesDir, "addons/sample")

    @Before fun clean() {
        root.deleteRecursively()
    }

    private fun installed(version: String, complete: Boolean = true, modified: Long = 1_000L): File =
        File(root, version).apply {
            mkdirs()
            File(this, "tool").writeText("x")
            if (complete) File(this, AddonPack.COMPLETE).writeText(version)
            setLastModified(modified)
        }

    @Test fun unpinnedLocalBuildReusesTheNewestCompletePack() {
        installed("a".repeat(64), modified = 1_000L)
        installed("b".repeat(64), modified = 2_000L)
        installed("c".repeat(64), complete = false, modified = 3_000L)
        val pack = AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = true)

        assertTrue(pack.installed(context))
        assertEquals("b".repeat(64), pack.dir(context).name)
    }

    @Test fun unpinnedLocalBuildWithNothingInstalledHasNoPack() {
        assertFalse(AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = true).installed(context))
    }

    @Test fun pinnedBuildIgnoresOtherInstalledVersions() {
        installed("b".repeat(64))
        val pack = AddonPack("sample", "a".repeat(64), 1L, listOf("tool"))

        assertFalse(pack.installed(context))
        assertEquals("a".repeat(64), pack.dir(context).name)
    }

    @Test fun ciBuildWithoutAPinNeverReuses() {
        installed("b".repeat(64))
        assertFalse(AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = false).installed(context))
    }

    @Test fun localBuildInstallsTheBundledPackWithoutDownloading() {
        val archive = tarZst("tool" to "x")
        val pack = AddonPack("sample", Hashes.sha256(archive), archive.length(), listOf("tool"), bundle = { archive.inputStream() })

        assertTrue(pack.await(context))
        assertEquals("x", pack.file(context, "tool").readText())
    }

    @Test fun bundledPackWithTheWrongDigestIsRejected() {
        val archive = tarZst("tool" to "x")
        val pack = AddonPack("sample", "a".repeat(64), archive.length(), listOf("tool"),
            bundle = { archive.inputStream() })

        val copy = File(context.cacheDir, "copy.tar.zst")

        assertFalse(pack.bundled(context, copy))
        assertFalse(copy.exists())
    }

    @Test fun manualPreviewTriesItsExactSourceReleaseAfterTheOfficialPack() {
        val commit = "1".repeat(40)
        val digest = "abcdef0123456789" + "2".repeat(48)
        val pack = AddonPack(
            "sample", digest, 1L, listOf("tool"), componentRepository = "wallentx/DroidDeck",
            sourceCommit = commit, prNumber = 0,
        )

        assertEquals(listOf(
            "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/" +
                "pack-sample-abcdef0123456789/sample.tar.zst",
            "https://github.com/wallentx/DroidDeck/releases/download/main-$commit/" +
                "pack-sample-abcdef0123456789.tar.zst",
        ), pack.downloadUrls)
    }

    @Test fun pullRequestPackRemainsAheadOfTheExactSourceReleaseFallback() {
        val commit = "3".repeat(40)
        val digest = "4".repeat(64)
        val pack = AddonPack(
            "powervr", digest, 1L, listOf("tool"), componentRepository = "wallentx/DroidDeck",
            sourceCommit = commit, prNumber = 7,
        )

        assertEquals(listOf(
            "https://github.com/Droid-Deck/DroidDeck-Components/releases/download/" +
                "pack-powervr-4444444444444444/powervr.tar.zst",
            "https://github.com/Droid-Deck/DroidDeck-CI/releases/download/pr-7/" +
                "pack-powervr-4444444444444444.tar.zst",
            "https://github.com/wallentx/DroidDeck/releases/download/main-$commit/" +
                "pack-powervr-4444444444444444.tar.zst",
        ), pack.downloadUrls)
    }

    @Test fun malformedPreviewIdentityDoesNotCreateASourceReleaseFallback() {
        val validDigest = "5".repeat(64)
        val cases = listOf(
            AddonPack("sample", validDigest, 1L, listOf("tool"), componentRepository = "bad repo", sourceCommit = "6".repeat(40)),
            AddonPack("sample", validDigest, 1L, listOf("tool"), componentRepository = "wallentx/DroidDeck", sourceCommit = "short"),
            AddonPack("sample", validDigest, 1L, listOf("tool"), componentRepository = "wallentx/DroidDeck", sourceCommit = "A".repeat(40)),
            AddonPack("../sample", validDigest, 1L, listOf("tool"), componentRepository = "wallentx/DroidDeck", sourceCommit = "7".repeat(40)),
            AddonPack("sample", "A".repeat(64), 1L, listOf("tool"), componentRepository = "wallentx/DroidDeck", sourceCommit = "8".repeat(40)),
        )

        for (pack in cases) assertEquals(1, pack.downloadUrls.size)
    }

    private fun tarZst(vararg files: Pair<String, String>): File =
        File(context.cacheDir, "sample-${System.nanoTime()}.tar.zst").apply {
            TarArchiveOutputStream(ZstdOutputStream(outputStream())).use { tar ->
                for ((path, text) in files) {
                    val bytes = text.toByteArray()
                    tar.putArchiveEntry(TarArchiveEntry(path).apply { size = bytes.size.toLong() })
                    tar.write(bytes)
                    tar.closeArchiveEntry()
                }
            }
        }
}
