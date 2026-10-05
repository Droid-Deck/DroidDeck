package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test

class RuntimeRemovalTest {
    private lateinit var dir: File
    @Before fun setUp() { dir = Files.createTempDirectory("runtime-removal").toFile() }
    @After fun tearDown() {
        dir.setWritable(true)
        RuntimeFileTree.delete(dir, null)
    }
    private fun runtime() = File(dir, "linuxfs").apply {
        File(this, "usr/bin").mkdirs()
        File(this, "usr/bin/gamescope").writeText("runtime")
        File(this, "root/.local/share/Steam").mkdirs()
        File(this, "root/.local/share/Steam/game").writeText("game")
        File(this, ".version").writeText("r9")
    }

    @Test fun removesReadOnlyTreesWithoutFollowingLinks() {
        val root = runtime()
        val outside = File(dir, "outside").apply { mkdirs() }
        val data = File(outside, "keep").apply { writeText("preserved") }
        Files.createSymbolicLink(File(root, "outside").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "dangling").toPath(), File(dir, "missing").toPath())
        Files.setPosixFilePermissions(outside.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        root.walkBottomUp().filter { !Files.isSymbolicLink(it.toPath()) && it.isDirectory }
            .forEach { Files.setPosixFilePermissions(it.toPath(), PosixFilePermissions.fromString("r-xr-xr-x")) }
        val counts = mutableListOf<Long>()
        RuntimeFileTree.delete(root) { counts.add(it) }
        assertFalse(Files.exists(root.toPath(), NOFOLLOW_LINKS))
        assertEquals("preserved", data.readText())
        assertEquals(PosixFilePermissions.fromString("r-xr-xr-x"), Files.getPosixFilePermissions(outside.toPath()))
        assertTrue(counts.size > 5)
        assertEquals((1L..counts.size.toLong()).toList(), counts)
    }

    @Test fun removesUnreadableDirectories() {
        val root = runtime()
        Files.setPosixFilePermissions(File(root, "usr").toPath(), emptySet())
        RuntimeFileTree.delete(root, null)
        assertFalse(root.exists())
    }

    @Test fun updatesCarryTheHomeAcrossReadOnlyRuntimeRoots() {
        val old = runtime()
        val staging = File(dir, "linuxfs.new").apply { File(this, "root").mkdirs() }
        for (tree in listOf(old, staging)) {
            Files.setPosixFilePermissions(tree.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        }
        RuntimeFileTree.carryHome(old, staging)
        assertEquals("game", File(staging, "root/.local/share/Steam/game").readText())
        assertFalse(File(old, "root").exists())
    }

    @Test fun recoversTheHomeAfterAnInterruptedReadOnlyRuntimeSwap() {
        val root = File(dir, "linuxfs")
        val old = File(dir, "linuxfs.old").apply { File(this, "root").mkdirs() }
        val staging = File(dir, "linuxfs.new").apply { File(this, "root").mkdirs() }
        File(staging, "root/save").writeText("user data")
        for (tree in listOf(old, staging))
            Files.setPosixFilePermissions(tree.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        LinuxRuntimeInstaller.recoverInterruptedSwap(root, staging, old)
        assertEquals("user data", File(root, "root/save").readText())
        assertFalse(File(staging, "root").exists())
    }

    @Test fun reservationBlocksPlayAndInstallBeforeTheWorkerStartsAndFollowersSeeProgress() {
        val root = runtime()
        val removal = LinuxRuntimeInstaller.beginUninstall(root)!!
        assertTrue(LinuxRuntimeInstaller.isBusy())
        assertTrue(LinuxRuntimeInstaller.isRemoving())
        assertFalse(LinuxRuntimeInstaller.isInstalling())
        assertFalse(LinuxRuntime.isInstalled(null))
        assertNull(LinuxRuntimeInstaller.beginUninstall(root))
        assertFalse(LinuxRuntimeInstaller.install(null, null, null))
        val entered = CountDownLatch(1)
        val release = CountDownLatch(1)
        val result = AtomicReference<Boolean>()
        val worker = Thread {
            result.set(removal.run { _, _ -> entered.countDown(); assertTrue(release.await(5, TimeUnit.SECONDS)) })
        }
        worker.start()
        try {
            assertTrue(entered.await(5, TimeUnit.SECONDS))
            val following = CountDownLatch(1)
            val stages = mutableListOf<String>()
            val followerResult = AtomicReference<Boolean>()
            val follower = Thread {
                followerResult.set(LinuxRuntimeInstaller.attach { stage, _ -> stages.add(stage); following.countDown() })
            }
            follower.start()
            assertTrue(following.await(5, TimeUnit.SECONDS))
            release.countDown()
            worker.join(5000)
            follower.join(5000)
            assertEquals(true, result.get())
            assertEquals(true, followerResult.get())
            assertEquals("Linux runtime removed", stages.last())
            assertFalse(root.exists())
            assertFalse(File(dir, "linuxfs.removing").exists())
            assertFalse(LinuxRuntimeInstaller.isBusy())
        } finally { release.countDown(); worker.join(5000) }
    }

    @Test fun retriesAnInterruptedRemovalAndClearsUpdateStaging() {
        val pending = File(dir, "linuxfs.removing").apply { mkdirs() }
        File(pending, "leftover").writeText("old")
        for (name in listOf("linuxfs.new", "linuxfs.old")) {
            val tree = File(dir, name).apply { mkdirs() }
            File(tree, "game").writeText("staged")
        }
        val removal = LinuxRuntimeInstaller.beginUninstall(File(dir, "linuxfs"))!!
        assertTrue(removal.run(null))
        assertTrue(dir.listFiles()!!.isEmpty())
    }

    @Test fun aPartialRemovalStaysQuarantinedAndRetryable() {
        val root = runtime()
        try {
            val removal = LinuxRuntimeInstaller.beginUninstall(root)!!
            assertFalse(removal.run { stage, _ ->
                if (stage.contains("entries"))
                    Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
            })
            assertFalse(root.exists())
            assertTrue(File(dir, "linuxfs.removing").exists())
            assertFalse(LinuxRuntimeInstaller.isBusy())
            assertNotNull(LinuxRuntimeInstaller.removalError())
        } finally { dir.setWritable(true) }
        assertTrue(LinuxRuntimeInstaller.beginUninstall(root)!!.run(null))
        assertFalse(File(dir, "linuxfs.removing").exists())
    }

    @Test fun reportsFailureWhenTheRuntimeCannotBeQuarantinedAndAllowsRetry() {
        val root = runtime()
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("r-xr-xr-x"))
        try {
            val removal = LinuxRuntimeInstaller.beginUninstall(root)!!
            val stages = mutableListOf<String>()
            assertFalse(removal.run { stage, _ -> stages.add(stage) })
            assertTrue(root.exists())
            assertFalse(LinuxRuntimeInstaller.isBusy())
            assertTrue(LinuxRuntimeInstaller.removalError()!!.contains("Retry removal"))
            assertFalse(stages.contains("Linux runtime removed"))
        } finally { dir.setWritable(true) }
        assertTrue(LinuxRuntimeInstaller.beginUninstall(root)!!.run(null))
        assertNull(LinuxRuntimeInstaller.removalError())
    }
}
