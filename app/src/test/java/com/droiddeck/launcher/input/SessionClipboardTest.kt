package com.droiddeck.launcher.input

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

@RunWith(RobolectricTestRunner::class)
class SessionClipboardTest {
    private val context = ApplicationProvider.getApplicationContext<Context>()

    @Test fun clearGuestQueueRemovesEveryPreviousSessionEntry() {
        val dir = SessionClipboard.guestFile(context).parentFile!!
        dir.mkdirs()
        listOf("queue.1.ready", "queue.2.pending", "queue.3.ready.9.claimed").forEach {
            File(dir, "text.$it").writeText("old")
        }
        SessionClipboard.clearGuestQueue(context)
        assertEquals(0, dir.listFiles()!!.size)
    }

    @Test fun readyFilesPresentAtStartupAreFoundInOrder() {
        val guestFile = SessionClipboard.guestFile(context)
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
