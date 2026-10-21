package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SteamClientSetupFailureTest {
    private fun find(vararg lines: String) = SteamClientSetupFailure.find(lines.asSequence())

    @Test fun aBlockedDownloadCarriesTheLastCurlError() {
        val failure = find(
            "== STEP 12:35:22 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)",
            "curl: (7) Failed to connect to client-update.steamstatic.com:443 after 3 ms: Could not connect to server",
            "curl: (35) Recv failure: Connection reset by peer",
            "droiddeck-steam-install: no Steam client CDN answered for steam_client_steamdeck_publicbeta_linuxarm64",
            "== steam: the Steam client could not be set up (status 1)",
            "[gamescope] [Info]  launch: Primary child shut down!",
        )!!
        assertTrue(failure.download)
        assertEquals("curl: (35) Recv failure: Connection reset by peer", failure.curlError)
    }

    @Test fun aFailureWithoutCurlErrorIsNotCalledADownloadProblem() {
        val failure = find(
            "== STEP 12:35:22 checking the Steam client (a first run downloads it)",
            "== STEP 12:35:30 downloading Steam: bins_linuxarm64_linuxarm64 (2/17)",
            "error:  cannot create /root/.local/share/Steam/ubuntu12_32/steam-runtime.tar.xz",
            "== steam: the Steam client could not be set up (status 50)",
        )!!
        assertFalse(failure.download)
        assertNull(failure.curlError)
    }

    @Test fun aReadyClientIsNoFailure() {
        assertNull(find(
            "== STEP 12:35:22 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)",
            "curl: (35) Recv failure: Connection reset by peer",
            "== STEP 12:35:40 Steam client ready",
        ))
    }

    @Test fun onlyTheLatestCheckCounts() {
        assertNull(find(
            "== STEP 10:00:00 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)",
            "curl: (6) Could not resolve host: client-update.steamstatic.com",
            "== steam: the Steam client could not be set up (status 1)",
            "== STEP 10:05:00 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)",
            "== STEP 10:06:00 Steam client ready",
        ))
        val failure = find(
            "== STEP 10:00:00 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)",
            "curl: (6) Could not resolve host: client-update.steamstatic.com",
            "== steam: the Steam client could not be set up (status 1)",
            "== STEP 10:05:00 checking the Steam client (first run downloads it, ~1-2 min with nothing on screen)",
            "== steam: the Steam client could not be set up (status 9)",
        )!!
        assertNull(failure.curlError)
    }

    @Test fun anArm64SyncErrorIsNotBlamedForTheX64Client() {
        val failure = find(
            "== STEP 10:00:00 checking the Steam client's arm64 parts (first run downloads them, ~1-2 min)",
            "curl: (6) Could not resolve host: client-update.steamstatic.com",
            "== STEP 10:00:05 could not update the arm64 client's parts; the x86-64 client keeps its own web helper",
            "== STEP 10:00:05 checking the x86-64 Steam client (FEX, experimental; the first run downloads ~2 GB)",
            "== steam: the Steam client could not be set up (status 3)",
        )!!
        assertNull(failure.curlError)
    }
}
