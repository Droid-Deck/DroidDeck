package com.droiddeck.launcher.core

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.github.luben.zstd.ZstdOutputStream
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
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

    @Test fun localBuildInstallsItsBundledPack() {
        val payload = "bundled pack".toByteArray()
        val tar = ByteArrayOutputStream()
        TarArchiveOutputStream(tar).use { output ->
            val entry = TarArchiveEntry("tool")
            entry.size = payload.size.toLong()
            output.putArchiveEntry(entry)
            output.write(payload)
            output.closeArchiveEntry()
        }
        val compressed = ByteArrayOutputStream()
        ZstdOutputStream(compressed).use { it.write(tar.toByteArray()) }
        val archive = compressed.toByteArray()
        val sha = MessageDigest.getInstance("SHA-256").digest(archive)
            .joinToString("") { "%02x".format(it.toInt() and 0xff) }
        val pack = AddonPack("sample", sha, archive.size.toLong(), listOf("tool"), reuseDeviceCopy = false) {
            ByteArrayInputStream(archive)
        }

        assertTrue(pack.await(context))
        assertEquals("bundled pack", pack.file(context, "tool").readText())
    }

    @Test fun ciBuildWithoutAPinNeverReuses() {
        installed("b".repeat(64))
        assertFalse(AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = false).installed(context))
    }
}
