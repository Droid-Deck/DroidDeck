package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SessionSteamLogsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun names(files: List<File>) = files.map { it.name }.sorted()

    @Test fun onlyWhatTheSessionWroteIsCollected() {
        val logs = tmp.newFolder("logs")
        val session = tmp.newFolder("session")
        val out = tmp.newFolder("out")
        File(logs, "console_log.txt").writeText("old line\n")
        File(logs, "cef_log.txt").writeText("old cef\n")
        File(logs, "cef_log.previous.txt").writeText("older cef\n")
        File(logs, "shrunk.txt").writeText("a long old line\n")
        File(logs, "untouched.txt").writeText("nothing new\n")
        SessionArtifacts.markSteamLogs(session, logs)

        File(logs, "console_log.txt").appendText("new line\n")
        File(logs, "cef_log.previous.txt").delete()
        File(logs, "cef_log.txt").renameTo(File(logs, "cef_log.previous.txt"))
        File(logs, "cef_log.txt").writeText("this session's cef\n")
        File(logs, "shrunk.txt").writeText("short\n")
        File(logs, "content_log.txt").writeText("created this session\n")

        val copied = SessionArtifacts.copySessionLogs(logs, session, out)
        assertEquals(listOf("cef_log.txt", "console_log.txt", "content_log.txt", "shrunk.txt"), names(copied))
        assertEquals("new line\n", File(out, "console_log.txt").readText())
        assertEquals("this session's cef\n", File(out, "cef_log.txt").readText())
        assertEquals("short\n", File(out, "shrunk.txt").readText())
        assertFalse(File(out, "cef_log.previous.txt").exists())
        assertFalse(File(out, "untouched.txt").exists())
    }

    @Test fun aLineCutByTheMarkIsLeftOut() {
        val logs = tmp.newFolder("logs")
        val session = tmp.newFolder("session")
        val out = tmp.newFolder("out")
        File(logs, "web.txt").writeText("old\nGET /x?sessionid=")
        SessionArtifacts.markSteamLogs(session, logs)
        File(logs, "web.txt").appendText("abcdef123456 200\nnext line\n")
        SessionArtifacts.copySessionLogs(logs, session, out)
        assertEquals("next line\n", File(out, "web.txt").readText())
    }

    @Test fun withoutMarksEveryLogUnderTheCapIsCollectedAndRedacted() {
        val logs = tmp.newFolder("logs")
        val out = tmp.newFolder("out")
        File(logs, "web.txt").writeText("GET /x?sessionid=abcdef123456 200\n")
        File(logs, "big.txt").outputStream().use { it.write(ByteArray(8 * 1024 * 1024)) }
        val copied = SessionArtifacts.copySessionLogs(logs, tmp.newFolder("session"), out)
        assertEquals(listOf("web.txt"), names(copied))
        assertFalse(File(out, "web.txt").readText().contains("abcdef123456"))
    }
}
