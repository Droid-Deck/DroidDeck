package com.droiddeck.launcher.runtime

import android.content.ContextWrapper
import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class LinuxRuntimeStandInTest {
    private val root = Files.createTempDirectory("guest-stand-ins").toFile()
    private val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
        override fun getFilesDir() = File(root, "files").apply { mkdirs() }
        override fun getCacheDir() = File(root, "cache").apply { mkdirs() }
        override fun getNoBackupFilesDir() = File(root, "no_backup").apply { mkdirs() }
    }
    private val guestRoot get() = LinuxRuntime.rootDir(context)

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun aBindUnderTheThermalStandInLeavesThePciStandInEmpty() {
        val thermal = LinuxRuntime.standIn(guestRoot, "/sys/class/thermal")
        val pci = LinuxRuntime.standIn(guestRoot, "/sys/bus/pci/devices")
        File(thermal, "thermal_zone28").mkdirs()
        File(thermal, "thermal_zone26").mkdirs()

        assertTrue(pci.isDirectory)
        assertEquals(emptyList<String>(), pci.list()!!.toList())
        assertTrue(File(thermal, "thermal_zone28").isDirectory)
    }

    @Test fun standInsAreSeparateFromEachOtherAndFromTheSharedEmptyDirectory() {
        val empty = File(guestRoot, "etc/droiddeck/empty").canonicalFile
        val thermal = LinuxRuntime.standIn(guestRoot, "/sys/class/thermal").canonicalFile
        val pci = LinuxRuntime.standIn(guestRoot, "/sys/bus/pci/devices").canonicalFile

        assertNotEquals(thermal, pci)
        for (dir in listOf(thermal, pci)) {
            assertNotEquals(empty, dir)
            assertFalse(dir.startsWith(empty))
        }
        assertFalse(pci.startsWith(thermal))
        assertFalse(thermal.startsWith(pci))
        assertEquals(thermal, LinuxRuntime.standIn(guestRoot, "/sys/class/thermal").canonicalFile)
    }

    @Test fun selinuxKeepsTheEmptyDirectoryAndNoSourceDirectoryIsSharedByTwoTargets() {
        val specs = LinuxRuntime.binds(context, null, null, null, null)
        val empty = File(guestRoot, "etc/droiddeck/empty").path

        assertTrue(specs.contains("$empty:/sys/fs/selinux"))
        val guestDirs = specs.map { it.split(":", limit = 2) }
            .filter { it.size == 2 && it[0].startsWith(File(guestRoot, "etc/droiddeck").path) && File(it[0]).isDirectory }
            .groupBy({ it[0] }, { it[1] })
        for ((source, targets) in guestDirs) assertEquals(source, 1, targets.size)
    }
}
