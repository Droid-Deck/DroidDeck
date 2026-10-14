package com.droiddeck.launcher.core

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test

class ArchivePathsTest {
    private lateinit var root: File
    private lateinit var dest: File

    @Before fun setUp() {
        root = Files.createTempDirectory("archive-paths").toFile()
        dest = File(root, "dest").apply { mkdirs() }
    }

    @After fun tearDown() {
        root.deleteRecursively()
    }

    @Test fun plainEntryLandsInside() {
        assertEquals(File(dest, "usr/bin/sh").canonicalPath, ArchivePaths.inside(dest, "usr/bin/sh")!!.canonicalPath)
    }

    @Test fun dotEntryIsTheDestination() {
        assertEquals(dest.canonicalPath, ArchivePaths.inside(dest, "./")!!.canonicalPath)
    }

    @Test fun parentTraversalIsRefused() {
        assertNull(ArchivePaths.inside(dest, "../escape"))
        assertNull(ArchivePaths.inside(dest, "usr/../../escape"))
    }

    @Test fun siblingWithSharedPrefixIsRefused() {
        File(root, "dest-other").mkdirs()
        assertNull(ArchivePaths.inside(dest, "../dest-other/x"))
    }

    @Test fun symlinkOutOfTheTreeIsRefused() {
        Files.createSymbolicLink(File(dest, "link").toPath(), root.toPath())
        assertNull(ArchivePaths.inside(dest, "link/escape"))
    }
}
