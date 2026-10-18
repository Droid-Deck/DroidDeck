package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class SessionClientRecordTest {
    @get:Rule val tmp = TemporaryFolder()

    @Test fun aSessionFolderKeepsTheClientItRan() {
        val dir = tmp.newFolder("2026-10-18-01-steam")
        assertNull(SessionArtifacts.recordedClient(dir))
        SessionArtifacts.recordClient(dir, "x86_64")
        assertEquals("x86_64", SessionArtifacts.recordedClient(dir))
        java.io.File(dir, "steam-client.txt").writeText("something else\n")
        assertNull(SessionArtifacts.recordedClient(dir))
    }
}
