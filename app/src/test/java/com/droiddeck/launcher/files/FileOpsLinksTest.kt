package com.droiddeck.launcher.files

import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class FileOpsLinksTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun link(at: File, target: String): Boolean =
        runCatching { Files.createSymbolicLink(at.toPath(), File(target).toPath()) }.isSuccess

    @Test fun aMovedFolderKeepsItsLinks() {
        val src = tmp.newFolder("game")
        File(src, "data.bin").writeText("x")
        assumeTrue("symlinks unavailable here", link(File(src, "lib.so"), "data.bin"))
        val dst = File(tmp.root, "other/game")
        File(dst, "keep").mkdirs()
        assertTrue(FileOps.moveWithProgress(src, dst, null))
        assertFalse(src.exists())
        assertTrue(Files.isSymbolicLink(File(dst, "lib.so").toPath()))
        assertEquals("data.bin", Files.readSymbolicLink(File(dst, "lib.so").toPath()).toString())
    }

    @Test fun aCopiedLinkPointsWhereTheOriginalDoes() {
        val src = tmp.newFolder("a")
        assumeTrue("symlinks unavailable here", link(File(src, "l"), "/nowhere/target"))
        val dst = File(tmp.root, "b")
        assertTrue(FileOps.copyWithProgress(src, dst, null))
        assertTrue(Files.isSymbolicLink(File(src, "l").toPath()))
        assertTrue(Files.isSymbolicLink(File(dst, "l").toPath()))
    }
}
