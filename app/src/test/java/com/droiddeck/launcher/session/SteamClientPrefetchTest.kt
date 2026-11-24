package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class SteamClientPrefetchTest {
    @Test fun readsPercentAndTotalFromTheInstallersStepLine() {
        val line = "== STEP 14:21:07 downloading Steam: bins_linuxarm64.zip (2/5), 120 of 480 MB"
        assertEquals(25 to 480, SteamClientPrefetch.parse(line))
    }

    @Test fun aRetryNoteDoesNotChangeTheReading() {
        val line = "== STEP 14:21:07 downloading Steam: x.zip (1/3), 10 of 40 MB - retrying in 5s"
        assertEquals(25 to 40, SteamClientPrefetch.parse(line))
    }

    @Test fun otherLinesAreIgnored() {
        assertNull(SteamClientPrefetch.parse("== STEP 14:21:07 the Steam client now follows steamdeck_stable"))
        assertNull(SteamClientPrefetch.parse("downloading Steam: x (1/1), 5 of 0 MB"))
    }
}
