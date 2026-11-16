package com.droiddeck.launcher.session

import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SessionRestartNoteTest {
    private val started = 1_731_100_000_000L

    @Test fun aBootAfterTheSessionBeganIsReportedAsARestart() {
        val note = SessionArtifacts.restartNote(started, started + 20 * 60_000L)
        assertNotNull(note)
        assertTrue(note!!.startsWith("The device itself restarted"))
    }

    @Test fun aBootBeforeTheSessionBeganMeansOnlyTheAppEnded() {
        val note = SessionArtifacts.restartNote(started, started - 3 * 3_600_000L)
        assertTrue(note!!.startsWith("The device has not restarted"))
    }

    @Test fun clockCorrectionsWithinAMinuteAreNotARestart() {
        val note = SessionArtifacts.restartNote(started, started + 30_000L)
        assertTrue(note!!.startsWith("The device has not restarted"))
    }

    @Test fun aFolderWithoutAStartTimeSaysNothing() {
        assertNull(SessionArtifacts.restartNote(0L, started))
    }
}
