package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionLogCaptureTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun captureStartsFromTheSessionFolderTime() {
        assertEquals(
            "/system/bin/logcat -v threadtime -T 1731100000.007 --pid=42",
            SessionLogCapture.command(42, 1_731_100_000_007L),
        )
    }

    @Test fun withoutAStartTimeOnlyNewLinesAreCaptured() {
        assertEquals("/system/bin/logcat -v threadtime -T 1 --pid=42", SessionLogCapture.command(42, 0L))
    }

    @Test fun aReplacedCaptureCannotWriteIntoTheNextSessionsLog() {
        val first = tmp.newFolder("a").resolve("app.log")
        val second = tmp.newFolder("b").resolve("app.log")
        SessionLogCapture.start(first, 0L)
        val old = SessionLogCapture.capture
        SessionLogCapture.append(old, "first session line")
        SessionLogCapture.start(second, 0L)
        SessionLogCapture.append(old, "late line from the first logcat")
        SessionLogCapture.append(SessionLogCapture.capture, "second session line")
        SessionLogCapture.stop()

        assertTrue(first.readText().contains("first session line"))
        val log = second.readText()
        assertFalse(log.contains("late line from the first logcat"))
        assertTrue(log.contains("second session line"))
    }
}
