package com.droiddeck.launcher.runtime

import android.app.Application
import android.content.Context
import android.content.ContextWrapper
import com.droiddeck.launcher.BuildConfig
import com.droiddeck.launcher.core.AddonPack
import java.io.ByteArrayOutputStream
import java.io.File
import java.net.ServerSocket
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean
import java.util.function.Predicate
import org.apache.commons.compress.archivers.tar.TarArchiveEntry
import org.apache.commons.compress.archivers.tar.TarArchiveOutputStream
import org.apache.commons.compress.compressors.zstandard.ZstdCompressorOutputStream
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class RuntimeInstallRollbackTest {
    @get:Rule val tmp = TemporaryFolder()
    @After fun resetFaults() { RuntimeFileTree.refuseForTest = null }

    private fun put(root: File, rel: String, data: String) = File(root, rel).apply {
        checkNotNull(parentFile).mkdirs()
        writeText(data)
    }

    private data class Attempt(val root: File, val staging: File, val old: File, val result: Boolean)

    private fun packedContext(): Context {
        val files = tmp.newFolder("files")
        val cache = tmp.newFolder("cache")
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getFilesDir(): File = files
            override fun getCacheDir(): File = cache
            override fun getApplicationContext(): Context = this
        }
        for ((pack, pin, required) in listOf(
            Triple(AddonPack.PROOT, BuildConfig.PACK_PROOT_SHA256, listOf("libproot.so", "libproot-loader.so")),
            Triple(AddonPack.DIRECTAUDIO, BuildConfig.PACK_DIRECTAUDIO_SHA256,
                listOf("linux-wine11/version.txt", "linux-wine11-systhread/version.txt")),
        )) {
            val version = pin.ifEmpty { "0".repeat(64) }
            val directory = File(files, "addons/${pack.name}/$version")
            required.forEach { put(directory, it, "test pack") }
            put(directory, AddonPack.COMPLETE, version)
        }
        return context
    }

    private fun serveOnce(server: ServerSocket, head: String, body: ByteArray = ByteArray(0)) = Thread {
        runCatching {
            server.accept().use { socket ->
                socket.soTimeout = 5000
                val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                while (true) {
                    val line = input.readLine() ?: break
                    if (line.isEmpty()) break
                }
                val out = socket.getOutputStream()
                out.write(("$head\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n").toByteArray())
                out.write(body)
                out.flush()
            }
        }
    }.apply { isDaemon = true; start() }

    private fun attempt(failRollback: Boolean): Attempt {
        val context = packedContext()
        val files = context.filesDir
        val root = LinuxRuntime.rootDir(context)
        val staging = File(files, LinuxRuntime.DIR + ".new")
        val old = File(files, LinuxRuntime.DIR + ".old")
        put(root, "root/save.dat", "original user data")
        put(root, "opt/appimages/user/game/app.AppImage", "original app")
        put(root, "usr/bin/usable", "previous runtime")

        val payload = ByteArrayOutputStream().also { bytes ->
            ZstdCompressorOutputStream(bytes).use { zstd ->
                TarArchiveOutputStream(zstd).use { tar ->
                    for ((name, value) in listOf("root/.bashrc" to "shipped home", "usr/bin/new" to "new runtime",
                            "opt/appimages/user/block" to "shipped app")) {
                        val data = value.toByteArray()
                        tar.putArchiveEntry(TarArchiveEntry(name).apply { size = data.size.toLong() })
                        tar.write(data)
                        tar.closeArchiveEntry()
                    }
                }
            }
        }.toByteArray()
        val digest = MessageDigest.getInstance("SHA-256").digest(payload).joinToString("") { "%02x".format(it) }
        val faultSeen = AtomicBoolean()
        RuntimeFileTree.refuseForTest = Predicate { path ->
            if (path == File(staging, "opt/appimages/user/block").toPath() && faultSeen.compareAndSet(false, true)) {
                if (failRollback) put(old, "root/rollback-block", "concurrent data")
                true
            } else failRollback && path == File(old, "root/rollback-block").toPath()
        }
        val server = ServerSocket(0)
        val thread = serveOnce(server, "HTTP/1.1 200 OK", payload)
        try {
            val release = LinuxRuntimeInstaller.Release("synthetic", "http://127.0.0.1:${server.localPort}/runtime.tar.zst", digest, payload.size.toLong())
            val result = LinuxRuntimeInstaller.install(context, release, null)
            assertTrue("the intended carry fault was not reached", faultSeen.get())
            return Attempt(root, staging, old, result)
        } finally {
            server.close()
            thread.join(1000)
            RuntimeFileTree.refuseForTest = null
        }
    }

    @Test fun aFailedCarryAndFailedRollbackKeepTheOnlyStagedCopy() {
        val attempt = attempt(failRollback = true)
        assertFalse(attempt.result)
        assertTrue("the only staged copy was deleted", File(attempt.staging, "root/save.dat").isFile)
        assertEquals("original user data", File(attempt.staging, "root/save.dat").readText())
        assertEquals("original app", File(attempt.root, "opt/appimages/user/game/app.AppImage").readText())
        assertEquals("previous runtime", File(attempt.root, "usr/bin/usable").readText())
        repeat(2) {
            assertThrows(java.io.IOException::class.java) {
                LinuxRuntimeInstaller.recoverInterruptedSwap(attempt.root, attempt.staging, attempt.old)
            }
            assertEquals("original user data", File(attempt.staging, "root/save.dat").readText())
        }
    }

    @Test fun aCompleteRollbackStillRestoresAndCleansTheFailedCandidate() {
        val attempt = attempt(failRollback = false)
        assertFalse(attempt.result)
        assertEquals("original user data", File(attempt.root, "root/save.dat").readText())
        assertEquals("original app", File(attempt.root, "opt/appimages/user/game/app.AppImage").readText())
        assertFalse(attempt.staging.exists())
    }

    private fun failedInstall(context: Context, head: String): String? {
        val server = ServerSocket(0)
        val thread = serveOnce(server, head)
        try {
            val release = LinuxRuntimeInstaller.Release("synthetic", "http://127.0.0.1:${server.localPort}/runtime.tar.zst", "a".repeat(64), 1L)
            assertFalse(LinuxRuntimeInstaller.install(context, release, null))
            return LinuxRuntimeInstaller.installError()
        } finally {
            server.close()
            thread.join(1000)
        }
    }

    @Test fun aRefusedRuntimeDownloadNamesTheStepAndTheServerAnswer() {
        val context = packedContext()
        assertEquals("Cannot download the runtime: 127.0.0.1: HTTP 403", failedInstall(context, "HTTP/1.1 403 Forbidden"))
    }

    @Test fun eachInstallReportsItsOwnFailureOnly() {
        val context = packedContext()
        failedInstall(context, "HTTP/1.1 403 Forbidden")
        assertEquals("Cannot download the runtime: 127.0.0.1: HTTP 404", failedInstall(context, "HTTP/1.1 404 Not Found"))
    }

    @Test fun anInstallInFlightKeepsTheLastFailureReadable() {
        val context = packedContext()
        val first = failedInstall(context, "HTTP/1.1 403 Forbidden")
        val server = ServerSocket(0)
        val accepted = java.util.concurrent.CountDownLatch(1)
        val release = java.util.concurrent.CountDownLatch(1)
        val stall = Thread {
            runCatching {
                server.accept().use { socket ->
                    accepted.countDown()
                    release.await()
                    socket.getOutputStream().write("HTTP/1.1 404 Not Found\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray())
                }
            }
        }.apply { isDaemon = true; start() }
        val second = Thread {
            LinuxRuntimeInstaller.install(context,
                LinuxRuntimeInstaller.Release("synthetic", "http://127.0.0.1:${server.localPort}/runtime.tar.zst", "a".repeat(64), 1L), null)
        }.apply { isDaemon = true; start() }
        try {
            assertTrue(accepted.await(10, java.util.concurrent.TimeUnit.SECONDS))
            assertTrue(LinuxRuntimeInstaller.isInstalling())
            assertEquals(first, LinuxRuntimeInstaller.installError())
        } finally {
            release.countDown()
            second.join(10000)
            server.close()
            stall.join(1000)
        }
        assertEquals("Cannot download the runtime: 127.0.0.1: HTTP 404", LinuxRuntimeInstaller.installError())
    }

    @Test fun anInstallRejectedDuringMaintenanceReportsItsOwnReason() {
        val context = packedContext()
        failedInstall(context, "HTTP/1.1 403 Forbidden")
        val maintenance = LinuxRuntimeInstaller.beginMaintenance(context, "maintenance")!!
        try {
            assertFalse(LinuxRuntimeInstaller.install(context,
                LinuxRuntimeInstaller.Release("synthetic", "http://127.0.0.1:1/runtime.tar.zst", "a".repeat(64), 1L), null))
            assertEquals("Runtime busy: maintenance in progress", LinuxRuntimeInstaller.installError())
        } finally {
            assertTrue(maintenance.run(null) { null })
        }
        assertEquals("Cannot download the runtime: 127.0.0.1: HTTP 404", failedInstall(context, "HTTP/1.1 404 Not Found"))
    }

    @Test fun aFinishedInstallKeepsNoReferenceToItsListener() {
        val context = packedContext()
        val server = ServerSocket(0)
        val thread = serveOnce(server, "HTTP/1.1 403 Forbidden")
        try {
            val release = LinuxRuntimeInstaller.Release("synthetic", "http://127.0.0.1:${server.localPort}/runtime.tar.zst", "a".repeat(64), 1L)
            assertFalse(LinuxRuntimeInstaller.install(context, release) { _, _ -> })
        } finally {
            server.close()
            thread.join(1000)
        }
        val job = LinuxRuntimeInstaller::class.java.getDeclaredField("lastInstallJob").apply { isAccessible = true }.get(null)!!
        val listeners = job.javaClass.getDeclaredField("listeners").apply { isAccessible = true }.get(job) as Collection<*>
        assertTrue(listeners.isEmpty())
    }

    @Test fun aFailedSwapNamesTheStep() {
        assertFalse(attempt(failRollback = false).result)
        assertTrue(LinuxRuntimeInstaller.installError().orEmpty().startsWith("Cannot carry user data into the new runtime"))
    }
}
