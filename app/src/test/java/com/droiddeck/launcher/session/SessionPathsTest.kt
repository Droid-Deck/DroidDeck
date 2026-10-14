package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionPathsTest {
    @get:Rule val tmp = TemporaryFolder()

    private fun dir(name: String) = tmp.newFolder(name)

    @Test fun bothNamingsAreSessionFolders() {
        assertTrue(SessionPaths.isSessionFolder(dir("2026-10-01-03-steam")))
        assertTrue(SessionPaths.isSessionFolder(dir("2026-10-01-12-retroarch")))
        assertTrue(SessionPaths.isSessionFolder(dir("session-20260930-180642")))
        assertTrue(SessionPaths.isSessionFolder(dir("session-20260930-180642-2")))
    }

    @Test fun otherFoldersAndFilesAreNot() {
        assertFalse(SessionPaths.isSessionFolder(dir("tools")))
        assertFalse(SessionPaths.isSessionFolder(dir("backup-test")))
        assertFalse(SessionPaths.isSessionFolder(dir("2026-10-01")))
        assertFalse(SessionPaths.isSessionFolder(tmp.newFile("2026-10-01-01-steam.zip")))
    }

    @Test fun foldersSortByDayThenNumber() {
        val folders = listOf(
            dir("2026-10-01-10-desktop"), dir("session-20260930-180642"), dir("2026-10-01-02-steam"),
            dir("session-20261001-063000"), dir("2026-09-30-01-steam"), dir("2026-10-01-01-steam"),
        )
        assertEquals(
            listOf(
                "session-20260930-180642", "2026-09-30-01-steam", "session-20261001-063000",
                "2026-10-01-01-steam", "2026-10-01-02-steam", "2026-10-01-10-desktop",
            ),
            folders.sortedWith(SessionPaths.chronological).map { it.name },
        )
    }

    @Test fun labelsBecomeShortLowercaseWords() {
        assertEquals("steam", SessionPaths.slug("steam"))
        assertEquals("dolphin-emulator", SessionPaths.slug("Dolphin Emulator"))
        assertEquals("org-telegram-desktop", SessionPaths.slug("org.telegram.desktop"))
        assertEquals("a-very-long-program-name", SessionPaths.slug("A very long program name that keeps going"))
        assertEquals("session", SessionPaths.slug("!!!"))
    }
}
