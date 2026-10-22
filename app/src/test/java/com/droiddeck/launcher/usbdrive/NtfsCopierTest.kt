package com.droiddeck.launcher.usbdrive

import java.io.File
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test

class NtfsCopierTest {
    private lateinit var destination: File

    @Before fun setUp() {
        destination = Files.createTempDirectory("ntfs-copy").toFile()
    }

    @After fun tearDown() {
        destination.deleteRecursively()
    }

    private class FakeSource(
        private val files: Map<String, ByteArray>,
        private val directories: Set<String>,
        private val chunk: Int = 7,
        private val failAt: Pair<String, Long>? = null,
    ) : NtfsSource {
        override fun list(path: String): List<NtfsEntry> {
            val prefix = if (path == NtfsPaths.ROOT) "/" else "$path/"
            val children = (files.keys + directories).filter { it.startsWith(prefix) && it.length > prefix.length }
                .map { it.removePrefix(prefix).substringBefore('/') }.distinct()
            return children.map { name ->
                val full = prefix + name
                val data = files[full]
                NtfsEntry(name, data == null, hidden = false, size = data?.size?.toLong() ?: 0L, modified = 1_600_000_000_000L)
            }
        }

        override fun open(path: String): NtfsReadable {
            val data = files.getValue(path)
            return object : NtfsReadable {
                override val size: Long = data.size.toLong()
                override fun read(offset: Long, buffer: ByteArray, start: Int, length: Int): Int {
                    if (failAt != null && failAt.first == path && offset >= failAt.second) throw UsbDriveException("read error")
                    if (offset >= data.size) return 0
                    val n = minOf(length, chunk, data.size - offset.toInt())
                    System.arraycopy(data, offset.toInt(), buffer, start, n)
                    return n
                }
                override fun close() {}
            }
        }
    }

    private fun bytes(size: Int, seed: Int) = ByteArray(size) { ((it * 31 + seed) and 0xFF).toByte() }

    private fun item(source: NtfsSource, path: String): NtfsItem {
        val parent = NtfsPaths.parent(path) ?: NtfsPaths.ROOT
        return NtfsItem(path, source.list(parent).first { it.name == NtfsPaths.name(path) })
    }

    private fun leftovers() = destination.walk().filter { it.name.endsWith(".droiddeck-part") }.toList()

    @Test fun copies_a_folder_tree_byte_for_byte() {
        val a = bytes(100, 1)
        val b = bytes(53, 2)
        val c = bytes(0, 3)
        val source = FakeSource(
            files = mapOf("/Games/Foo/a.bin" to a, "/Games/Foo/sub/b.bin" to b, "/Games/Foo/empty.txt" to c),
            directories = setOf("/Games", "/Games/Foo", "/Games/Foo/sub", "/Games/Foo/nothing"),
        )
        val copier = NtfsCopier(source, bufferSize = 16)
        val plan = copier.plan(listOf(item(source, "/Games/Foo")))
        assertEquals(153L, plan.bytes)
        assertEquals(3, plan.files.size)

        val outcome = copier.copy(plan, destination, { false }) {}

        assertEquals(NtfsCopier.Outcome(copied = 3, skipped = 0, bytes = 153, cancelled = false), outcome)
        assertArrayEquals(a, File(destination, "Foo/a.bin").readBytes())
        assertArrayEquals(b, File(destination, "Foo/sub/b.bin").readBytes())
        assertEquals(0L, File(destination, "Foo/empty.txt").length())
        assertTrue(File(destination, "Foo/nothing").isDirectory)
        assertEquals(1_600_000_000_000L, File(destination, "Foo/a.bin").lastModified())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun a_second_run_skips_what_is_already_there() {
        val source = FakeSource(mapOf("/x.bin" to bytes(40, 1), "/y.bin" to bytes(41, 2)), emptySet())
        val copier = NtfsCopier(source, bufferSize = 16)
        val selection = listOf(item(source, "/x.bin"), item(source, "/y.bin"))
        copier.copy(copier.plan(listOf(selection[0])), destination, { false }) {}

        val plan = copier.plan(selection)
        assertEquals(41L, copier.bytesNeeded(plan, destination))
        val outcome = copier.copy(plan, destination, { false }) {}

        assertEquals(1, outcome.copied)
        assertEquals(1, outcome.skipped)
        assertEquals(listOf("x.bin", "y.bin"), destination.list()!!.sorted())
    }

    @Test fun a_different_file_with_the_same_name_is_kept() {
        File(destination, "save.dat").writeText("mine")
        val incoming = bytes(30, 9)
        val source = FakeSource(mapOf("/save.dat" to incoming), emptySet())
        val copier = NtfsCopier(source, bufferSize = 16)

        copier.copy(copier.plan(listOf(item(source, "/save.dat"))), destination, { false }) {}

        assertEquals("mine", File(destination, "save.dat").readText())
        assertArrayEquals(incoming, File(destination, "save (1).dat").readBytes())
    }

    @Test fun cancelling_keeps_finished_files_and_removes_the_partial_one() {
        val source = FakeSource(mapOf("/d/a.bin" to bytes(100, 1), "/d/b.bin" to bytes(100, 2)), setOf("/d"))
        val copier = NtfsCopier(source, bufferSize = 10)
        var cancel = false

        val outcome = copier.copy(copier.plan(listOf(item(source, "/d"))), destination, { cancel }) { progress ->
            if (progress.current == "d/b.bin" && progress.bytesDone > 130) cancel = true
        }

        assertTrue(outcome.cancelled)
        assertEquals(1, outcome.copied)
        assertTrue(File(destination, "d/a.bin").isFile)
        assertFalse(File(destination, "d/b.bin").exists())
        assertTrue(leftovers().isEmpty())
    }

    @Test fun a_read_error_fails_without_leaving_a_partial_file() {
        val source = FakeSource(mapOf("/broken.bin" to bytes(100, 1)), emptySet(), failAt = "/broken.bin" to 50L)
        val copier = NtfsCopier(source, bufferSize = 10)

        try {
            copier.copy(copier.plan(listOf(item(source, "/broken.bin"))), destination, { false }) {}
            fail("expected the read error to propagate")
        } catch (expected: UsbDriveException) {
        }

        assertFalse(File(destination, "broken.bin").exists())
        assertTrue(leftovers().isEmpty())
    }
}
