package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class LoadingStateTest {
    @Test fun clientDownloadCountsMegabytesWhenTheRuntimeSaysThem() {
        assertEquals(
            "45 / 360 MB" to 12,
            LoadingState.clientDownload("== STEP 17:38:20 downloading Steam: bins_linuxarm64_linuxarm64 (2/17), 45 of 360 MB"),
        )
    }

    @Test fun aStalledDownloadStillShowsWhereItIs() {
        assertEquals(
            "45 / 360 MB" to 12,
            LoadingState.clientDownload(
                "== STEP 17:39:20 downloading Steam: bins_linuxarm64_linuxarm64 (2/17), 45 of 360 MB" +
                    " - interrupted (curl exit 28), trying again in 5 s",
            ),
        )
    }

    @Test fun anOlderRuntimeShowsThePartAndItsNumber() {
        assertEquals(
            "bins_linuxarm64_linuxarm64" to 11,
            LoadingState.clientDownload("== STEP 17:38:17 downloading Steam: bins_linuxarm64_linuxarm64 (2/17)"),
        )
        assertEquals("a_all" to 50, LoadingState.clientDownload("== STEP 00:00:00 downloading Steam: a_all (1/2), 0 of 0 MB"))
    }

    @Test fun otherStepsAreNotClientDownloads() {
        assertNull(LoadingState.clientDownload("== STEP 17:38:14 checking the Steam client (a first run downloads it)"))
    }
}
