package com.droiddeck.launcher.stores.itch

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files

class ItchArchivesTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("itch-archives").toFile()
    }

    @After fun tearDown() {
        Files.walk(dir.toPath()).sorted(Comparator.reverseOrder()).forEach { Files.deleteIfExists(it) }
    }

    private fun file(name: String, vararg head: Int, size: Int = 64): File {
        val f = File(dir, name)
        f.parentFile?.mkdirs()
        val bytes = ByteArray(maxOf(size, head.size))
        head.forEachIndexed { i, b -> bytes[i] = b.toByte() }
        f.writeBytes(bytes)
        return f
    }

    @Test fun kindsComeFromTheContentNotTheName() {
        assertEquals("7z", ItchArchives.kind(file("data.bin", 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C), "data.bin"))
        assertEquals("zip", ItchArchives.kind(file("a.7z", 0x50, 0x4B, 3, 4), "a.7z"))
        assertEquals("rar", ItchArchives.kind(file("b", 0x52, 0x61, 0x72, 0x21), "b"))
        assertEquals("exe", ItchArchives.kind(file("Game.exe", 0x50, 0x4B), "Game.exe"))
    }

    @Test fun theLargestArchiveInsideIsUnpackedNext() {
        file("readme.txt", 0x48, 0x69)
        file("VotV.7z", 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, size = 4096)
        file("extras/art.zip", 0x50, 0x4B, 3, 4, size = 128)
        assertEquals("VotV.7z", ItchArchives.inner(dir)?.name)
    }

    @Test fun archivesDeeperThanTwoFoldersAreLeftAlone() {
        file("a/b/c/deep.zip", 0x50, 0x4B, 3, 4)
        assertNull(ItchArchives.inner(dir))
    }

    @Test fun nothingPackedMeansNothingToUnpack() {
        file("game.pak", 0x00, 0x01)
        file("notes.txt", 0x48, 0x69)
        assertNull(ItchArchives.inner(dir))
    }

    @Test fun aNamedArchiveWithoutASignatureIsSkipped() {
        file("extras.zip", 0x48, 0x69, size = 8192)
        file("game.7z", 0x37, 0x7A, 0xBC, 0xAF, 0x27, 0x1C, size = 128)
        assertEquals("game.7z", ItchArchives.inner(dir)?.name)
    }

    @Test fun symlinksAreNotFollowed() {
        val root = File(dir, "root").apply { mkdirs() }
        val outside = File(dir, "outside").apply { mkdirs() }
        File(outside, "keep.zip").writeBytes(byteArrayOf(0x50, 0x4B, 3, 4))
        Files.createSymbolicLink(File(root, "link").toPath(), outside.toPath())
        Files.createSymbolicLink(File(root, "linked.zip").toPath(), File(outside, "keep.zip").toPath())
        assertNull(ItchArchives.inner(root))
    }
}
