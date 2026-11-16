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

    @Test fun aLinkedAssetPicksTheSameVariantInEveryRelease() {
        val link = "https://github.com/Djihads80/Eglantine/releases/download/build-129/eglantine-a407703f6d29a5ab73328b4ededb12cf29b2758d-linux-aarch64.AppImage"
        val wanted = UserApps.githubAsset(link)
        assertEquals("eglantine-a407703f6d29a5ab73328b4ededb12cf29b2758d-linux-aarch64.AppImage", wanted)
        assertEquals("Djihads80/Eglantine", UserApps.githubRepo(link))
        val build = listOf("eglantine-a407703f6d29a5ab73328b4ededb12cf29b2758d-linux-aarch64-pgo.AppImage",
            "eglantine-a407703f6d29a5ab73328b4ededb12cf29b2758d-linux-aarch64.AppImage")
        assertEquals(build[1], UserApps.pickAsset(build, wanted))
        val daily = listOf("eglantine-linux-aarch64-pgo.AppImage", "eglantine-linux-aarch64.AppImage", "eglantine-linux-x86_64.AppImage")
        assertEquals("eglantine-linux-aarch64.AppImage", UserApps.pickAsset(daily, wanted))
        assertEquals("eglantine-linux-aarch64-pgo.AppImage", UserApps.pickAsset(daily, "eglantine-0f3c2a91-linux-aarch64-pgo.AppImage"))
        assertEquals("eglantine-linux-aarch64-pgo.AppImage", UserApps.pickAsset(daily, "Other-v2.1-x86_64.AppImage"))
        assertEquals("eglantine-linux-aarch64-pgo.AppImage", UserApps.pickAsset(daily))
    }

    @Test fun onlyReleaseDownloadLinksNameAnAsset() {
        assertNull(UserApps.githubAsset("https://github.com/Djihads80/Eglantine"))
        assertNull(UserApps.githubAsset("Djihads80/Eglantine"))
        assertNull(UserApps.githubAsset("https://github.com/o/r/releases/download/v1/notes.txt"))
        assertEquals("My App-1.0-arm64.AppImage", UserApps.githubAsset("https://github.com/o/r/releases/download/v1.0/My%20App-1.0-arm64.AppImage"))
        assertEquals("App-arm64.AppImage", UserApps.githubAsset("https://github.com/o/r/releases/latest/download/App-arm64.AppImage"))
        assertEquals("o/r", UserApps.githubRepo("https://github.com/o/r/releases/latest/download/App-arm64.AppImage"))
        assertNull(UserApps.githubAsset("https://github.com/o/r/releases/download/v1/bad%ZZ.AppImage"))
    }

    @Test fun theLinkedArchitectureWidthIsKept() {
        val release = listOf("App-2.0-x86-32.AppImage", "App-2.0-x86_64.AppImage", "App-2.0-i686.AppImage")
        assertEquals("App-2.0-x86_64.AppImage", UserApps.pickAsset(release, "App-1.0-x86_64.AppImage"))
        assertEquals("App-2.0-x86-32.AppImage", UserApps.pickAsset(release, "App-1.0-x86-32.AppImage"))
        assertEquals("App-2.0-amd64.AppImage", UserApps.pickAsset(listOf("App-2.0-i386.AppImage", "App-2.0-amd64.AppImage"), "App-1.0-x86_64.AppImage"))
    }
}
