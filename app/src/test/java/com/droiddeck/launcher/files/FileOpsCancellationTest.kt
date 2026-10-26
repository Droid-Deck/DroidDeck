package com.droiddeck.launcher.files

import java.io.File
import java.util.concurrent.CancellationException
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
class FileOpsCancellationTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun cancellingCopyRemovesAnIncompleteNewFile() {
        val source = tmp.newFile("source").apply { writeBytes(ByteArray(5 * 1024 * 1024)) }
        val destination = File(tmp.root, "destination")
        var cancelled = false

        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(source, destination, { _, _ -> cancelled = true }) { cancelled }
        }

        assertTrue(source.exists())
        assertFalse(destination.exists())
    }

    @Test fun cancellingMoveFallbackPreservesTheSourceAndExistingDestination() {
        val source = tmp.newFile("source").apply { writeText("replacement") }
        val destination = tmp.newFile("destination").apply { writeText("original") }
        var cancelled = false

        assertThrows(CancellationException::class.java) {
            FileOps.moveWithProgress(source, destination, { _, _ -> cancelled = true }) { cancelled }
        }

        assertEquals("replacement", source.readText())
        assertEquals("original", destination.readText())
    }

    @Test fun aCommittedMoveFinishesRemovingTheSourceDespiteCancellation() {
        val source = tmp.newFile("source").apply { writeText("replacement") }
        val destination = tmp.newFile("destination").apply { writeText("original") }
        val isCancelled = { destination.readText() == "replacement" }

        assertTrue(FileOps.moveWithProgress(source, destination, null, isCancelled))

        assertTrue(isCancelled())
        assertEquals("replacement", destination.readText())
        assertFalse(source.exists())
        assertEquals(setOf("destination"), tmp.root.list()!!.toSet())
    }

    @Test fun cancellingTheSizeScanStopsBeforeCreatingTheDestination() {
        val source = tmp.newFolder("source")
        repeat(3) { File(source, "file-$it").writeText("content") }
        val destination = File(tmp.root, "destination")
        var checks = 0

        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(source, destination, null) {
                assertFalse(destination.exists())
                ++checks > 2
            }
        }

        assertEquals(3, checks)
        assertFalse(destination.exists())
        assertEquals(3, source.listFiles()?.size)
    }

    @Test fun cancellingCopyRemovesANewDestinationDirectory() {
        val source = tmp.newFolder("source")
        val payload = File(source, "nested/payload").apply {
            parentFile!!.mkdirs()
            writeText("content")
        }
        val destination = File(tmp.root, "destination")

        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(source, destination, { _, _ -> throw CancellationException("cancel") })
        }

        assertEquals("content", payload.readText())
        assertFalse(destination.exists())
    }

    @Test fun cancellingCopyPreservesAnExistingDestinationDirectory() {
        val source = tmp.newFolder("source")
        File(source, "nested/payload").apply {
            parentFile!!.mkdirs()
            writeText("replacement")
        }
        val destination = tmp.newFolder("destination")
        val existing = File(destination, "keep").apply { writeText("original") }

        assertThrows(CancellationException::class.java) {
            FileOps.copyWithProgress(source, destination, { _, _ -> throw CancellationException("cancel") })
        }

        assertEquals("original", existing.readText())
        assertEquals(setOf("keep"), destination.list()!!.toSet())
    }

    @Test fun cancellingRecursiveDeleteStopsBeforeTheNextFile() {
        val directory = tmp.newFolder("directory")
        repeat(3) { File(directory, "file-$it").writeText("content") }
        var checks = 0

        assertThrows(CancellationException::class.java) {
            FileOps.delete(directory) { ++checks > 3 }
        }

        assertTrue(directory.exists())
        assertEquals(2, directory.listFiles()?.size)
    }
}
