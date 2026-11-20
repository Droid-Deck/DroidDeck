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
        File(context.filesDir, "addons/xr-model-tests").deleteRecursively()
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

    @Test fun labBuildReusesInstalledPackDespiteDifferentSourcePin() {
        installed("b".repeat(64))
        val pack = AddonPack("sample", "a".repeat(64), 1L, listOf("tool"), labBuild = true)

        assertTrue(pack.installed(context))
        assertEquals("b".repeat(64), pack.dir(context).name)
    }

    @Test fun labBuildWithoutCompleteInstalledPackKeepsSourcePin() {
        installed("b".repeat(64), complete = false)
        val pack = AddonPack("sample", "a".repeat(64), 1L, listOf("tool"), labBuild = true)

        assertFalse(pack.installed(context))
        assertEquals("a".repeat(64), pack.dir(context).name)
    }

    @Test fun aBuildWithoutThePackSaysSoWhenAwaited() {
        val pack = AddonPack("sample", "", 0L, listOf("tool"), reuseDeviceCopy = false)

        assertFalse(pack.await(context))
        assertEquals("this build has no pack", pack.failure)
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

    @Test fun aCompletionMarkerDoesNotHideMissingRequiredFiles() {
        val version = "a".repeat(64)
        val directory = installed(version)
        File(directory, "tool").delete()
        assertFalse(AddonPack("sample", version, 1L, listOf("tool")).installed(context))
    }

    @Test fun aVerifiedArchiveStillNeedsValidModelResourcesBeforePublication() {
        val archive = tarZst("tool" to "x")
        val pack = AddonPack("sample", Hashes.sha256(archive), archive.length(), listOf("tool"),
            bundle = { archive.inputStream() }, validateFiles = { false }, detailedState = true)
        assertFalse(pack.await(context))
        assertFalse(pack.installed(context))
        assertFalse(File(pack.dir(context), AddonPack.COMPLETE).exists())
        assertTrue(pack.failure.orEmpty().contains("invalid resources"))
    }

    @Test fun invalidCachedResourcesAreNotReadyDespiteTheCompletionMarker() {
        val version = "a".repeat(64)
        installed(version)
        assertFalse(AddonPack("sample", version, 1L, listOf("tool"), validateFiles = { false }).installed(context))
    }

    @Test fun isolatedNetworkDownloadDoesNotReuseOrReplaceTheProductionCache() {
        val archive = tarZst("tool" to "network")
        val sha = Hashes.sha256(archive)
        val original = installed(sha)
        serve(archive.readBytes()) { url ->
            val pack = AddonPack("sample", sha, archive.length(), listOf("tool"),
                reuseDeviceCopy = false, labBuild = false, bundle = { throw java.io.FileNotFoundException() },
                downloadUrls = listOf(url), storageName = "xr-model-tests/round/sample", detailedState = true)
            val deadline = System.nanoTime() + 5_000_000_000
            assertTrue(pack.await(context, running = { System.nanoTime() < deadline }))
            assertEquals("network", pack.file(context, "tool").readText())
            assertEquals("x", File(original, "tool").readText())
            assertTrue(pack.dir(context).path.contains("xr-model-tests/round/sample"))
        }
    }

    @Test fun corruptedNetworkDownloadCannotPublishACompleteTestCache() {
        val archive = tarZst("tool" to "network")
        val bytes = archive.readBytes().also { it[0] = (it[0].toInt() xor 1).toByte() }
        serve(bytes) { url ->
            val pack = AddonPack("sample", Hashes.sha256(archive), archive.length(), listOf("tool"),
                reuseDeviceCopy = false, labBuild = false, bundle = { throw java.io.FileNotFoundException() },
                downloadUrls = listOf(url), storageName = "xr-model-tests/corrupt/sample")
            val deadline = System.nanoTime() + 5_000_000_000
            assertFalse(pack.await(context, running = { System.nanoTime() < deadline }))
            assertEquals(AddonPack.State.Failed, pack.state.value)
            assertFalse(pack.installed(context))
            assertFalse(File(pack.dir(context), AddonPack.COMPLETE).exists())
        }
    }

    private fun serve(bytes: ByteArray, run: (String) -> Unit) {
        java.net.ServerSocket(0, 1, java.net.InetAddress.getByName("127.0.0.1")).use { server ->
            server.soTimeout = 5000
            val requests = java.util.concurrent.atomic.AtomicInteger()
            val worker = Thread {
                runCatching {
                    server.accept().use { socket ->
                        socket.soTimeout = 5000
                        val reader = socket.getInputStream().bufferedReader()
                        while (!reader.readLine().isNullOrEmpty()) {}
                        requests.incrementAndGet()
                        socket.getOutputStream().use { output ->
                            output.write("HTTP/1.1 200 OK\r\nContent-Length: ${bytes.size}\r\nConnection: close\r\n\r\n".toByteArray())
                            output.write(bytes)
                        }
                    }
                }
            }.apply { isDaemon = true; start() }
            try {
                run("http://127.0.0.1:${server.localPort}/sample.tar.zst")
                assertEquals(1, requests.get())
            } finally {
                server.close()
                worker.join(1000)
            }
        }
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
