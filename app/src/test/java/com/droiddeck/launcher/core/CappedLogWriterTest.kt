package com.droiddeck.launcher.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class CappedLogWriterTest {
    @get:Rule val folder = TemporaryFolder()

    @Test fun aLogUnderTheLimitIsWrittenAsIs() {
        val file = folder.newFile("app.log")
        CappedLogWriter(file, false, 1024, 3).use { log ->
            assertTrue(log.line("first"))
            assertTrue(log.line("second"))
        }
        assertEquals(listOf("first", "second"), file.readLines())
    }

    @Test fun aReconnectStormStopsAtTheLimitAndKeepsTheLastLines() {
        val file = folder.newFile("audio.log")
        val limit = 4096L
        val log = CappedLogWriter(file, true, limit, 3)
        var written = 0
        for (n in 0 until 10_000) {
            if (log.line("I: [linker64] client.c: Created $n \"Native client (UNIX socket client)\"")) written++
        }
        val capped = file.length()
        assertTrue("file grew to $capped bytes", capped <= limit + 200)
        assertFalse(log.line("after the limit"))
        log.close()
        val lines = file.readLines()
        assertEquals(written, lines.count { it.startsWith("I: [linker64] client.c: Created ") } - 2)
        assertTrue(lines.any { it.startsWith("== this log reached its") })
        assertEquals("== ${10_000 + 1 - written - 3} lines dropped after the limit; the last 3 follow", lines[lines.size - 4])
        assertEquals(
            listOf(
                "I: [linker64] client.c: Created 9998 \"Native client (UNIX socket client)\"",
                "I: [linker64] client.c: Created 9999 \"Native client (UNIX socket client)\"",
                "after the limit",
            ),
            lines.takeLast(3),
        )
        assertTrue(file.length() < limit + 1024)
    }

    @Test fun twoWritersOnOneFileShareItsLimit() {
        val file = folder.newFile("audio.log")
        val daemon = CappedLogWriter(file, true, 2048, 2)
        val relay = CappedLogWriter(file, true, 2048, 2)
        repeat(2_000) { daemon.line("I: [linker64] protocol-native.c: Connection died.") }
        assertFalse(relay.line("DA-Relay: mic enabled=1"))
        assertTrue(file.length() <= 2048 + 200)
        daemon.close()
        relay.close()
        assertEquals("DA-Relay: mic enabled=1", file.readLines().last())
    }

    @Test fun appendKeepsWhatTheFileHeldAndOverwriteStartsFresh() {
        val file = folder.newFile("audio.log")
        file.writeText("== PulseAudio daemon starting\n")
        CappedLogWriter(file, true, 1024, 2).use { it.line("restarted") }
        assertEquals(listOf("== PulseAudio daemon starting", "restarted"), file.readLines())
        CappedLogWriter(file, false, 1024, 2).use { it.line("new session") }
        assertEquals(listOf("new session"), file.readLines())
    }

    @Test fun linesAfterCloseAreIgnored() {
        val file = folder.newFile("app.log")
        val log = CappedLogWriter(file, false, 1024, 2)
        log.line("kept")
        log.close()
        assertFalse(log.line("late"))
        log.close()
        assertEquals(listOf("kept"), file.readLines())
    }
}
