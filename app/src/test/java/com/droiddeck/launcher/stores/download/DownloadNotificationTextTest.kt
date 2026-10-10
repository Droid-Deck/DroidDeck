package com.droiddeck.launcher.stores.download

import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Test

/** The notification's line: what the Downloads row says, on one line. */
class DownloadNotificationTextTest {
    private val base = DownloadEntry(Store.EPIC, "m", "Metalstorm", state = DownloadState.RUNNING)

    @Test fun downloadingShowsPercentSizesSpeedAndTime() {
        val d = base.copy(stage = DownloadStage.DOWNLOAD, bytesDone = 1_288_490_189, bytesTotal = 2_684_354_560, speedBps = 12_897_484, etaSeconds = 185)
        assertEquals("Metalstorm · 48% · 1.2 GB/2.5 GB · 12.3 MB/s · 3 min", DownloadNotificationText.line(d, "Downloading", "Paused", "Queued"))
    }

    @Test fun installShowsTheStageNotTheSpeed() {
        val d = base.copy(stage = DownloadStage.INSTALL, stageDone = 42, stageTotal = 100, speedBps = 50_000_000)
        assertEquals("Metalstorm · Installing 42%", DownloadNotificationText.line(d, "Installing", "Paused", "Queued"))
        assertEquals("Metalstorm · Paused", DownloadNotificationText.line(d.copy(state = DownloadState.PAUSED), "Installing", "Paused", "Queued"))
    }
}
