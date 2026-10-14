package com.droiddeck.launcher.runtime

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class LinuxFexTest {
    private lateinit var dir: File

    @Before fun setUp() { dir = Files.createTempDirectory("fex").toFile() }
    @After fun tearDown() { dir.deleteRecursively() }

    private fun elf(machine: Int, bigEndian: Boolean = false): File {
        val b = ByteArray(64)
        b[0] = 0x7f; b[1] = 'E'.code.toByte(); b[2] = 'L'.code.toByte(); b[3] = 'F'.code.toByte()
        b[4] = 2; b[5] = if (bigEndian) 2 else 1
        if (bigEndian) { b[18] = (machine shr 8).toByte(); b[19] = (machine and 0xff).toByte() }
        else { b[18] = (machine and 0xff).toByte(); b[19] = (machine shr 8).toByte() }
        return File(dir, "prog-$machine-$bigEndian").apply { writeBytes(b) }
    }

    @Test fun readsTheMachine() {
        assertEquals(62, LinuxFex.elfMachine(elf(62)))
        assertEquals(3, LinuxFex.elfMachine(elf(3)))
        assertEquals(183, LinuxFex.elfMachine(elf(183)))
        assertEquals(62, LinuxFex.elfMachine(elf(62, bigEndian = true)))
        assertNull(LinuxFex.elfMachine(File(dir, "script").apply { writeText("#!/bin/sh\necho hi\n") }))
        assertNull(LinuxFex.elfMachine(File(dir, "missing")))
        assertTrue(LinuxFex.isX86(62) && LinuxFex.isX86(3))
        assertFalse(LinuxFex.isX86(183) || LinuxFex.isX86(null))
    }

    @Test fun modesAreStoredBesideTheApp() {
        assertEquals(LinuxFex.AUTO, LinuxFex.mode(dir))
        assertTrue(LinuxFex.setMode(dir, LinuxFex.ON))
        assertEquals(LinuxFex.ON, LinuxFex.mode(dir))
        assertTrue(LinuxFex.setMode(dir, LinuxFex.OFF))
        assertEquals(LinuxFex.OFF, LinuxFex.mode(dir))
        assertTrue(LinuxFex.setMode(dir, LinuxFex.AUTO))
        assertFalse(File(dir, "fex").exists())
        assertFalse(LinuxFex.setMode(dir, "sometimes"))
        File(dir, "fex").writeText("garbage")
        assertEquals(LinuxFex.AUTO, LinuxFex.mode(dir))
    }

    @Test fun theArchitectureIsRecorded() {
        LinuxFex.writeArch(dir, 62)
        assertEquals("x86_64", File(dir, "arch").readText().trim())
        LinuxFex.writeArch(dir, 183)
        assertEquals("arm64", File(dir, "arch").readText().trim())
        LinuxFex.writeArch(dir, null)
        assertFalse(File(dir, "arch").exists())
    }

    @Test fun releasesFallBackToTheX86Image() {
        assertEquals("App-aarch64.AppImage", UserApps.pickAsset(listOf("App-x86_64.AppImage", "App-aarch64.AppImage")))
        assertEquals("App.AppImage", UserApps.pickAsset(listOf("App-x86_64.AppImage", "App.AppImage")))
        assertEquals("App-x86_64.AppImage", UserApps.pickAsset(listOf("App-x86_64.AppImage", "App-armhf.AppImage", "notes.txt")))
        assertNull(UserApps.pickAsset(listOf("App-armhf.AppImage", "App-i686.AppImage")))
    }
}
