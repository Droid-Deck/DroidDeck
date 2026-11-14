package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class GraphicsReportTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun deviceNumbersSplitAsLinuxDoes() {
        // /dev/mali0 on the testers' phones: 10:103 (misc); a DRM render node: 226:128.
        assertEquals(10L, GraphicsReport.major(10L shl 8 or 103L))
        assertEquals(103L, GraphicsReport.minor(10L shl 8 or 103L))
        assertEquals(226L, GraphicsReport.major(226L shl 8 or 128L))
        assertEquals(128L, GraphicsReport.minor(226L shl 8 or 128L))
    }

    @Test fun gameLogsAreCutPerFileAndOldestLaunchesGoFirst() {
        val games = tmp.newFolder("games")
        fun launch(name: String, bytes: Int) = File(games, name).apply { mkdirs() }.also { File(it, "steam-1.log").writeBytes(ByteArray(bytes)) }
        val old = launch("20261012-100000-1", 60)
        val mid = launch("20261012-110000-2", 60)
        val new = launch("20261012-120000-3", 200)
        GraphicsReport.capGames(games, fileMax = 100, totalMax = 170)
        assertEquals(100L, File(new, "steam-1.log").length())
        assertFalse(old.exists())
        assertTrue(mid.exists())
        assertTrue(new.exists())
    }

    @Test fun aMissingGamesFolderIsFine() {
        GraphicsReport.capGames(File(tmp.root, "none"))
        GraphicsReport.finish(tmp.newFolder("session"))
    }
}
