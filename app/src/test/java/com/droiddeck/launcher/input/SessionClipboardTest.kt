package com.droiddeck.launcher.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SessionClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun clearGuestQueueRemovesOtherSessionsAndKeepsTheCurrentOne() {
        val previous = SessionClipboard.guestFile(context, "old").parentFile!!
        val current = SessionClipboard.guestFile(context, "new").parentFile!!
        previous.mkdirs()
        current.mkdirs()
        File(previous, "text.queue.1.ready").writeText("old")
        File(current, "text.queue.1.ready").writeText("same session")
        SessionClipboard.clearGuestQueue(context, "new")
        assertFalse(previous.exists())
        assertEquals(listOf("text.queue.1.ready"), current.listFiles()!!.map { it.name })
    }

    @Test fun guestQueuesOfDifferentSessionsAreSeparateDirectories() {
        assertNotEquals(
            SessionClipboard.guestFile(context, "a").parentFile,
            SessionClipboard.guestFile(context, "b").parentFile,
        )
    }

    @Test fun readyFilesPresentAtStartupAreFoundInOrder() {
        val guestFile = SessionClipboard.guestFile(context, "session")
        val dir = guestFile.parentFile!!
        dir.mkdirs()
        listOf("text.queue.2.1.ready", "text.queue.1.1.ready", "text.queue.3.1.pending", "text.queue.0.1.ready.5.claimed", "other.queue.1.ready")
            .forEach { File(dir, it).writeText("x") }
        assertEquals(
            listOf("text.queue.1.1.ready", "text.queue.2.1.ready"),
            SessionClipboard.readyGuestFiles(guestFile).map { it.name },
        )
    }
}
