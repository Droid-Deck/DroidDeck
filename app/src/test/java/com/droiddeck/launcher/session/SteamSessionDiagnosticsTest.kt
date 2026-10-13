package com.droiddeck.launcher.session

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

class SteamSessionDiagnosticsTest {
    @Test fun identifiesAnExplicitSteamSessionReplacementMarker() {
        assertTrue(SteamSessionDiagnostics.containsSessionReplaced(sequenceOf(
            "LogOn successful",
            "Session Replaced",
        )))
        assertTrue(SteamSessionDiagnostics.containsSessionReplaced(sequenceOf(
            "session_replaced by another connection",
        )))
    }

    @Test fun networkAndAuthenticationMessagesAloneDoNotDiagnoseSessionReplacement() {
        assertFalse(SteamSessionDiagnostics.containsSessionReplaced(sequenceOf(
            "Connection timed out while contacting Steam",
            "Authentication failed: retrying",
            "Could not establish a connection",
        )))
    }

    @Test fun nearbyNetworkMessagesDoNotChangeAnExplicitReplacementMarker() {
        assertTrue(SteamSessionDiagnostics.containsSessionReplaced(sequenceOf(
            "Authentication successful",
            "Connection dropped",
            "Session Replaced",
            "Reconnecting",
        )))
    }

    @Test fun scansCopiedSteamLogsForTheExplicitMarker() {
        val logs = kotlin.io.path.createTempDirectory("steam-session-logs").toFile()
        try {
            File(logs, "connection_log.txt").writeText(
                "Authentication successful\nSession Replaced\nConnection closed\n",
            )
            assertTrue(SteamSessionDiagnostics.hasSessionReplaced(logs))
        } finally {
            logs.deleteRecursively()
        }
    }
}
