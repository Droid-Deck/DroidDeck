package com.droiddeck.launcher.storage

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class MoveJournalTest {
    private val app get() = RuntimeEnvironment.getApplication()
    @get:Rule val tmp = TemporaryFolder()

    @Test fun journalRoundTripKeepsItemsAndRegistration() {
        MoveJournal.clear(app)
        val entry = MoveJournal.Entry(
            kind = "steam",
            name = "Game",
            items = listOf("/a/game" to "/b/game", "/a/prefix" to "/b/prefix"),
            registration = MoveJournal.Registration("arm64", "/b", "Card", "/b/libraryfolder.vdf", false),
            startedAt = 123L,
        )
        MoveJournal.write(app, entry)

        val read = MoveJournal.read(app)
        assertNotNull(read)
        assertEquals("steam", read!!.kind)
        assertEquals("Game", read.name)
        assertEquals(listOf("/a/game" to "/b/game", "/a/prefix" to "/b/prefix"), read.items)
        assertEquals("arm64", read.registration!!.arch)
        assertEquals("/b/libraryfolder.vdf", read.registration.marker)
        assertEquals(123L, read.startedAt)

        MoveJournal.clear(app)
        assertNull(MoveJournal.read(app))
    }

    @Test fun discardRemovesOnlyPartialCopies() {
        val src = tmp.newFolder("src")
        File(src, "keep.txt").writeText("x")
        val dst = tmp.newFolder("dst")
        File(dst, "part.txt").writeText("x")
        val finished = File(tmp.root, "finished")
        finished.mkdirs()
        val gone = File(tmp.root, "gone-${System.nanoTime()}")
        MoveJournal.write(app, MoveJournal.Entry(
            kind = "added",
            name = "Game",
            items = listOf(src.path to dst.path, gone.path to finished.path),
            registration = null,
            startedAt = 1L,
        ))

        assertTrue(MoveJournal.discard(app))
        assertFalse(File(dst, "part.txt").exists())
        assertTrue(File(src, "keep.txt").isFile)
        assertTrue(finished.isDirectory)
        assertNull(MoveJournal.read(app))
    }
}
