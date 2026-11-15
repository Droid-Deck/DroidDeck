package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Test

class SessionLogCaptureTest {
    @Test fun captureStartsFromTheSessionFolderTime() {
        assertEquals(
            "/system/bin/logcat -v threadtime -T 1731100000.007 --pid=42",
            SessionLogCapture.command(42, 1_731_100_000_007L),
        )
    }

    @Test fun withoutAStartTimeOnlyNewLinesAreCaptured() {
        assertEquals("/system/bin/logcat -v threadtime -T 1 --pid=42", SessionLogCapture.command(42, 0L))
    }
}
