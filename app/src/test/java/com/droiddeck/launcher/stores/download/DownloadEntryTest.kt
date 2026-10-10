package com.droiddeck.launcher.stores.download

import com.droiddeck.launcher.stores.Store
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The Downloads row reads the active stage's own progress, and which stages are behind it. */
class DownloadEntryTest {
    private val base = DownloadEntry(Store.EPIC, "doomblade", "DOOMBLADE", state = DownloadState.RUNNING)

    @Test fun downloadRunsOnBytesAndInstallOnItsOwnCount() {
        val dl = base.copy(stage = DownloadStage.DOWNLOAD, bytesDone = 50, bytesTotal = 200)
        assertEquals(0.25f, dl.stageFraction, 0.001f)
        // Past Download the bytes no longer drive the bar; the stage's own count does.
        val inst = dl.copy(stage = DownloadStage.INSTALL, stageDone = 30, stageTotal = 40, stageItems = 3, stageItemsTotal = 4)
        assertEquals(0.75f, inst.stageFraction, 0.001f)
        assertEquals(75, inst.percent)
        // Nothing counted yet: unknown, not empty and not full.
        assertEquals(-1f, base.copy(stage = DownloadStage.INSTALL).stageFraction, 0f)
    }

    @Test fun passedStagesComeFromTheBitsNotTheOrder() {
        // Epic checks files before it fetches: Verify is behind Download there.
        val e = base.copy(stage = DownloadStage.DOWNLOAD, stagesPassed = (1 shl DownloadStage.MANIFEST.ordinal) or (1 shl DownloadStage.VERIFY.ordinal))
        assertTrue(e.passed(DownloadStage.VERIFY))
        assertFalse(e.passed(DownloadStage.INSTALL))
        assertTrue(e.copy(state = DownloadState.INSTALLED).passed(DownloadStage.INSTALL))
    }
}
