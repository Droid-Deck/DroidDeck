package com.droiddeck.launcher.files

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.junit.rules.TemporaryFolder

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class FileOpsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun tree(): File {
        val root = tmp.newFolder("src")
        File(root, "a.txt").writeText("a".repeat(100))
        val sub = File(root, "sub").apply { mkdirs() }
        File(sub, "b.txt").writeText("b".repeat(10 * 1024))
        val deep = File(sub, "deep").apply { mkdirs() }
        File(deep, "c.txt").writeText("c".repeat(1024))
        File(root, "empty").mkdirs()
        return root
    }

    @Test fun copyWithKnownTotalPreservesTheTreeAndReportsProgress() {
        val src = tree()
        val dst = File(tmp.root, "dst-${System.nanoTime()}")
        val total = FileOps.totalSize(src)
        val seen = ArrayList<Pair<Long, Long>>()

        val ok = FileOps.copyWithProgress(src, dst, total, { done, t -> seen.add(done to t) })

        assertTrue(ok)
        assertEquals("a".repeat(100), File(dst, "a.txt").readText())
        assertEquals("b".repeat(10 * 1024), File(dst, "sub/b.txt").readText())
        assertEquals("c".repeat(1024), File(dst, "sub/deep/c.txt").readText())
        assertTrue(File(dst, "empty").isDirectory)
        assertTrue(src.isDirectory)
        assertTrue(seen.isNotEmpty())
        assertTrue(seen.all { it.second == total })
        assertEquals(total, seen.maxOf { it.first })
    }

    @Test fun copyIntoItselfIsRefused() {
        val src = tree()

        assertFalse(FileOps.copyWithProgress(src, File(src, "nested"), null))
        assertFalse(File(src, "nested").exists())
    }

    @Test fun moveWithKnownTotalMovesAndCountsOnce() {
        val src = tree()
        val total = FileOps.totalSize(src)
        val dst = File(tmp.root, "moved-${System.nanoTime()}")
        var last = 0L to 1L

        val ok = FileOps.moveWithProgress(src, dst, total, { done, t -> last = done to t })

        assertTrue(ok)
        assertFalse(src.exists())
        assertEquals("b".repeat(10 * 1024), File(dst, "sub/b.txt").readText())
    }

    @Test fun anUnreadableDirectoryFailsInsteadOfSilentlySkipping() {
        val root = tmp.newFolder("unreadable-root")
        val bad = object : File(root, "bad") {
            override fun isDirectory(): Boolean = true
            override fun exists(): Boolean = true
            override fun listFiles(): Array<File>? = null
        }
        val dst = File(tmp.root, "dst-${System.nanoTime()}")

        assertFalse(FileOps.copyWithProgress(bad, dst, 1L, null))
        assertTrue(dst.listFiles()?.isEmpty() != false)
    }
}
