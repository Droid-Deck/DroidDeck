package com.droiddeck.launcher.runtime

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LinuxRuntimeArchiveNameTest {
    private fun release(version: String, sha: String) =
        LinuxRuntimeInstaller.Release(version, "https://example.invalid/runtime.tar.zst", sha, 1L)

    @Test fun sameReleaseKeepsTheSameName() {
        val a = LinuxRuntimeInstaller.archiveName(release("r9", "ab".repeat(32)))
        val b = LinuxRuntimeInstaller.archiveName(release("r9", "ab".repeat(32)))
        assertEquals(a, b)
    }

    @Test fun anotherVersionOrChecksumGetsAnotherName() {
        val base = LinuxRuntimeInstaller.archiveName(release("r9", "ab".repeat(32)))
        assertNotEquals(base, LinuxRuntimeInstaller.archiveName(release("r10", "ab".repeat(32))))
        assertNotEquals(base, LinuxRuntimeInstaller.archiveName(release("r9", "cd".repeat(32))))
    }

    @Test fun nameIsASafeFileNameThatTheCleanupRecognises() {
        val name = LinuxRuntimeInstaller.archiveName(release("r9/../x y", "AB/CD ".repeat(10)))
        assertFalse(name.contains('/'))
        assertFalse(name.contains(' '))
        assertTrue(name.startsWith("linuxfs"))
        assertTrue(name.endsWith(".tar.zst"))
    }

    @Test fun shortOrEmptyChecksumStillMakesAName() {
        assertEquals("linuxfs-r9-.tar.zst", LinuxRuntimeInstaller.archiveName(release("r9", "")))
    }
}
