package com.droiddeck.launcher.input

import android.content.ClipboardManager
import android.content.Context
import android.os.Looper
import com.droiddeck.launcher.session.SessionService
import com.droiddeck.launcher.session.SessionState
import com.droiddeck.launcher.wayland.WaylandCompositor
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], manifest = Config.NONE, shadows = [SessionClipboardTest.CompositorShadow::class])
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

    @Test fun resumeBeforeSessionIdAssignedBindsTheQueueOnceTheIdArrives() {
        val sessionId = "bound-later"
        val queued = File(SessionClipboard.guestFile(context, sessionId).parentFile!!, "text.queue.1.1.ready")
        queued.parentFile!!.mkdirs()
        queued.writeText("from desktop")
        SessionState.sessionId = null
        SessionState.mode = SessionService.MODE_DESKTOP
        val clipboard = SessionClipboard(context) { true }
        clipboard.start()
        try {
            assertTrue(queued.exists())
            SessionState.sessionId = sessionId
            shadowOf(Looper.getMainLooper()).idle()
            val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val deadline = System.currentTimeMillis() + 5000
            while (manager.primaryClip?.getItemAt(0)?.text?.toString() != "from desktop" && System.currentTimeMillis() < deadline) {
                shadowOf(Looper.getMainLooper()).idle()
                Thread.sleep(20)
            }
            assertFalse(queued.exists())
            assertEquals("from desktop", manager.primaryClip?.getItemAt(0)?.text?.toString())
        } finally {
            clipboard.stop()
            SessionState.sessionId = null
        }
    }

    private fun awaitClaimed(dir: File) {
        val deadline = System.currentTimeMillis() + 5000
        while (dir.listFiles()!!.none { it.name.endsWith(".claimed") } && System.currentTimeMillis() < deadline) {
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(20)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun runDeferredCopy(androidChangesWhileAway: Boolean): String? {
        val sessionId = if (androidChangesWhileAway) "focus-newer" else "focus-same"
        val dir = SessionClipboard.guestFile(context, sessionId).parentFile!!
        dir.mkdirs()
        File(dir, "text.queue.1.1.ready").writeText("from desktop")
        SessionState.sessionId = sessionId
        SessionState.mode = SessionService.MODE_DESKTOP
        val manager = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        manager.setPrimaryClip(android.content.ClipData.newPlainText("t", "phone"))
        var focus = false
        val clipboard = SessionClipboard(context) { focus }
        clipboard.start()
        try {
            awaitClaimed(dir)
            assertEquals("phone", manager.primaryClip?.getItemAt(0)?.text?.toString())
            assertTrue(dir.listFiles()!!.any { it.name.endsWith(".claimed") })
            if (androidChangesWhileAway) manager.setPrimaryClip(android.content.ClipData.newPlainText("t", "newer"))
            focus = true
            clipboard.refresh()
            assertTrue(dir.listFiles()!!.none { it.name.endsWith(".claimed") })
            return manager.primaryClip?.getItemAt(0)?.text?.toString()
        } finally {
            clipboard.stop()
            SessionState.sessionId = null
        }
    }

    @Test fun copyArrivingWithoutFocusIsDeliveredWhenFocusReturns() {
        assertEquals("from desktop", runDeferredCopy(androidChangesWhileAway = false))
    }

    @Test fun copyArrivingWithoutFocusDoesNotOverwriteANewerAndroidCopy() {
        assertEquals("newer", runDeferredCopy(androidChangesWhileAway = true))
    }

    @Implements(value = WaylandCompositor::class, isInAndroidSdk = false)
    class CompositorShadow {
        companion object {
            @JvmStatic @Implementation fun __staticInitializer__() {}
            @JvmStatic @Implementation fun nativeSetClipboardText(utf8: ByteArray?) {}
        }
    }
}
