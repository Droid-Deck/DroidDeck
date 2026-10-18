package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class VacDiagnosticsTest {
    @Test fun tailReadsOnlyWhatThisSessionAppended() {
        val log = java.io.File.createTempFile("console", ".log")
        try {
            log.writeText("You are in insecure mode.\nConnected to 1.2.3.4\n")
            val armed = log.length()
            log.appendText("Connecting to 5.6.7.8\n")
            assertEquals(listOf("Connecting to 5.6.7.8", ""), VacDiagnostics.tail(log, armed))
        } finally {
            log.delete()
        }
    }

    @Test fun theSameGameInTwoLibrariesGetsTwoConsoleNames() {
        val a = java.io.File("/lib1/steamapps/common/Team Fortress 2/tf/console.log")
        val b = java.io.File("/lib2/steamapps/common/Team Fortress 2/tf/console.log")
        val c = java.io.File("/lib1/steamapps/common/Half-Life 2/hl2/console.log")
        val names = VacDiagnostics.consoleNames(listOf(a, b, c))
        assertEquals("Team Fortress 2-tf", names[0])
        assertTrue(names[1].startsWith("Team Fortress 2-tf-") && names[1] != names[0])
        assertEquals("Half-Life 2-hl2", names[2])
        assertEquals(names, VacDiagnostics.consoleNames(listOf(a, b, c)))
    }

    @Test fun tailReadsAllOfAReplacedFile() {
        val log = java.io.File.createTempFile("console", ".log")
        try {
            log.writeText("new\n")
            assertEquals(listOf("new", ""), VacDiagnostics.tail(log, 1000L))
        } finally {
            log.delete()
        }
    }

    @Test fun onlyConsoleLogsThisSessionWroteAreReported() {
        val log = java.io.File.createTempFile("console", ".log")
        try {
            log.writeText("Connected to 1.2.3.4\n")
            val since = log.lastModified() - 5_000
            val armed = mapOf(log.canonicalPath to log.length())
            assertFalse(VacDiagnostics.writtenThisSession(log, since, armed))
            log.appendText("Connecting to 5.6.7.8\n")
            assertTrue(VacDiagnostics.writtenThisSession(log, since, armed))
            assertTrue(VacDiagnostics.writtenThisSession(log, since, emptyMap()))
            assertFalse(VacDiagnostics.writtenThisSession(log, log.lastModified() + 1_000, emptyMap()))
        } finally {
            log.delete()
        }
    }
}
