package com.droiddeck.launcher.files

import java.io.File
import java.util.concurrent.CancellationException
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import org.junit.Assume.assumeTrue
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileOpsCopyTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cancellingAnOverwritePreservesTheDestinationAndSource() {
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFile("dest").apply { writeText("original user data") }
        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(src, dst, { _, _ -> throw CancellationException("cancel") })
        }
        assertTrue("original destination was removed", dst.exists())
        assertEquals("original user data", dst.readText())
        assertEquals("replacement", src.readText())
        assertEquals(setOf("source", "dest"), tmp.root.list()!!.toSet())
    }

    @Test fun cancellingANewCopyLeavesNoPartialDestination() {
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = File(tmp.root, "dest")
        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(src, dst, { _, _ -> throw CancellationException("cancel") })
        }
        assertFalse(dst.exists())
        assertEquals("replacement", src.readText())
        assertEquals(setOf("source"), tmp.root.list()!!.toSet())
    }

    @Test fun cancellingAMoveFallbackPreservesBothFiles() {
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFile("dest").apply { writeText("original user data") }
        assertThrows(CancellationException::class.java) {
            FileOps.moveWithProgress(src, dst, { _, _ -> throw CancellationException("cancel") })
        }
        assertTrue("original destination was removed", dst.exists())
        assertEquals("original user data", dst.readText())
        assertEquals("replacement", src.readText())
    }

    @Test fun cancellingStopsATreeOfEmptyFiles() {
        val src = tmp.newFolder("source")
        for (name in listOf("a", "b", "c")) File(src, name).createNewFile()
        val dst = File(tmp.root, "dest")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(src, dst, null) { checks++ > 1 }
        }
        assertEquals(1, dst.list()!!.size)
        assertEquals(3, src.list()!!.size)
    }

    @Test fun cancellingStopsATreeOfEmptyFolders() {
        val src = tmp.newFolder("source")
        for (name in listOf("a", "b", "c")) File(src, name).mkdir()
        val dst = File(tmp.root, "dest")
        var checks = 0
        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(src, dst, null) { checks++ > 1 }
        }
        assertEquals(1, dst.list()!!.size)
    }

    @Test fun aCompletedCopyReplacesTheDestination() {
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFile("dest").apply { writeText("original user data") }
        assertTrue(FileOps.copyWithProgress(src, dst, null))
        assertEquals("replacement", dst.readText())
        assertEquals("replacement", src.readText())
        assertEquals(setOf("source", "dest"), tmp.root.list()!!.toSet())
    }

    @Test fun aLongDestinationNameDoesNotOverflowTheStagingName() {
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = File(tmp.root, "d".repeat(240))
        assertTrue(FileOps.copyWithProgress(src, dst, null))
        assertEquals("replacement", dst.readText())
    }

    @Test fun anOverwritePreservesTheTargetsExecutablePermissions() {
        assumeTrue(Files.getFileStore(tmp.root.toPath()).supportsFileAttributeView("posix"))
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFile("dest").apply { writeText("original") }
        val permissions = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(dst.toPath(), permissions)
        assertTrue(FileOps.copyWithProgress(src, dst, null))
        assertEquals("replacement", dst.readText())
        assertEquals(permissions, Files.getPosixFilePermissions(dst.toPath()))
    }

    @Test fun aMoveFallbackPreservesTheTargetsExecutablePermissions() {
        assumeTrue(Files.getFileStore(tmp.root.toPath()).supportsFileAttributeView("posix"))
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFile("dest").apply { writeText("original") }
        val permissions = PosixFilePermissions.fromString("rwxr-x---")
        Files.setPosixFilePermissions(dst.toPath(), permissions)
        assertTrue(FileOps.moveWithProgress(src, dst, null))
        assertFalse(src.exists())
        assertEquals("replacement", dst.readText())
        assertEquals(permissions, Files.getPosixFilePermissions(dst.toPath()))
    }

    @Test fun anOverwriteStillRefusesAReadOnlyDestination() {
        assumeTrue(Files.getFileStore(tmp.root.toPath()).supportsFileAttributeView("posix"))
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFile("dest").apply { writeText("original") }
        Files.setPosixFilePermissions(dst.toPath(), PosixFilePermissions.fromString("r--r--r--"))
        try {
            assertFalse(FileOps.copyWithProgress(src, dst, null))
            assertEquals("original", dst.readText())
            assertEquals("replacement", src.readText())
        } finally {
            dst.setWritable(true)
        }
    }

    @Test fun anEmptyFileCanReplaceAnExistingFile() {
        val src = tmp.newFile("source")
        val dst = tmp.newFile("dest").apply { writeText("original user data") }
        var callbacks = 0
        assertTrue(FileOps.copyWithProgress(src, dst, { _, _ -> callbacks++ }))
        assertEquals(0, callbacks)
        assertEquals(0L, dst.length())
        assertTrue(src.exists())
    }

    @Test fun aFailedPublicationPreservesTheDestinationAndCleansStaging() {
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFolder("dest")
        File(dst, "keep").writeText("original user data")
        assertFalse(FileOps.copyWithProgress(src, dst, null))
        assertEquals("original user data", File(dst, "keep").readText())
        assertEquals("replacement", src.readText())
        assertEquals(setOf("source", "dest"), tmp.root.list()!!.toSet())
    }

    @Test fun aSuccessfulMoveFallbackDeletesTheSourceOnlyAfterCopying() {
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val dst = tmp.newFile("dest").apply { writeText("original user data") }
        assertTrue(FileOps.moveWithProgress(src, dst, null))
        assertFalse(src.exists())
        assertEquals("replacement", dst.readText())
        assertEquals(setOf("dest"), tmp.root.list()!!.toSet())
    }

    @Test fun overwritingThroughASymlinkKeepsTheLinkAndUpdatesItsTarget() {
        assumeTrue(Files.getFileStore(tmp.root.toPath()).supportsFileAttributeView("posix"))
        val src = tmp.newFile("source").apply { writeText("replacement") }
        val realTarget = tmp.newFile("real-target").apply { writeText("original user data") }
        val link = File(tmp.root, "dest-link")
        Files.createSymbolicLink(link.toPath(), realTarget.toPath())

        assertTrue(FileOps.copyWithProgress(src, link, null))
        assertTrue("destination symlink was replaced instead of written through", Files.isSymbolicLink(link.toPath()))
        assertEquals("replacement", realTarget.readText())
    }
}
