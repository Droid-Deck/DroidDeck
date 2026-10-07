package com.droiddeck.launcher.session

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.nio.file.Files

class SessionLogMigrationTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun source(name: String = "2026-10-06-01-steam"): File = tmp.newFolder(name).apply {
        File(this, "steam").mkdir()
        File(this, "session.log").writeText("Using JWT 25484942796017334\n")
        File(this, "steam/console_log.txt").writeText("password=private\n")
        File(this, ".complete").writeText("done")
    }

    @Test fun legacySessionsMoveIntoPrivateStorage() {
        val source = source()
        val target = tmp.newFolder("private")
        SessionLogMigration.move(source, target)
        assertFalse(source.exists())
        val moved = File(target, source.name)
        assertTrue(SessionPaths.isSessionFolder(moved))
        assertTrue(File(moved, "steam/console_log.txt").readText().contains("password=private"))
        assertTrue(File(moved, ".complete").isFile)
    }

    @Test fun crossFilesystemFallbackCopiesEverythingBeforeRemovingTheOriginal() {
        val original = source()
        val source = object : File(original.path) {
            override fun renameTo(dest: File): Boolean = false
        }
        val target = tmp.newFolder("private")
        SessionLogMigration.move(source, target)
        assertFalse(original.exists())
        assertEquals("Using JWT 25484942796017334\n", File(target, "${original.name}/session.log").readText())
        assertEquals(listOf(original.name), target.list()!!.toList())
    }

    @Test fun aCopyFailurePreservesAllOriginalFiles() {
        val original = source()
        val source = object : File(original.path) {
            override fun renameTo(dest: File): Boolean = false
        }
        val target = tmp.newFolder("private")
        target.setWritable(false)
        try {
            assertThrows(java.io.IOException::class.java) { SessionLogMigration.move(source, target) }
        } finally {
            target.setWritable(true)
        }
        assertTrue(File(original, "session.log").isFile)
        assertTrue(File(original, "steam/console_log.txt").isFile)
        assertTrue(target.list()!!.isEmpty())
    }

    @Test fun collisionsPreserveBothSessionsAndKeepLegacyNamesRecognizable() {
        val original = source("session-20260930-180642-2")
        val target = tmp.newFolder("private")
        File(target, original.name).mkdir()
        SessionLogMigration.move(original, target)
        assertEquals(2, target.listFiles()!!.size)
        assertTrue(target.listFiles()!!.all { SessionPaths.isSessionFolder(it) })
    }

    @Test fun linksAreRejectedWithoutReadingOrDeletingTheirTargets() {
        val source = source()
        val outside = tmp.newFile("outside").apply { writeText("outside data") }
        Files.createSymbolicLink(File(source, "steam/connection_log.txt").toPath(), outside.toPath())
        val target = tmp.newFolder("private")
        assertThrows(IllegalStateException::class.java) { SessionLogMigration.move(source, target) }
        assertTrue(File(source, "session.log").exists())
        assertTrue(target.list()!!.isEmpty())
        assertEquals("outside data", outside.readText())
    }

    @Test fun cleanupDoesNotFollowNestedDirectoryLinks() {
        val source = source()
        val outside = tmp.newFolder("outside")
        val preserved = File(outside, "session.log").apply { writeText("outside data") }
        Files.createSymbolicLink(File(source, "linked").toPath(), outside.toPath())
        SessionLogFiles.deleteTree(source)
        assertFalse(source.exists())
        assertEquals("outside data", preserved.readText())
    }
    @Test fun aCopiedCollisionKeepsTheNewerSessionAsTheLatest() {
        val original = source()
        original.setLastModified(1000L)
        val source = object : File(original.path) {
            override fun renameTo(dest: File): Boolean = false
        }
        val target = tmp.newFolder("private")
        val newer = File(target, original.name).apply { mkdir(); setLastModified(2000L) }
        SessionLogMigration.move(source, target)
        assertEquals(newer, target.listFiles()!!.sortedWith(SessionPaths.chronological).last())
    }
}
