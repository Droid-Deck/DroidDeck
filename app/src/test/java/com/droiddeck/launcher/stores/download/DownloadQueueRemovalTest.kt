package com.droiddeck.launcher.stores.download

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
class DownloadQueueRemovalTest {
    @Test fun aRemovalHoldIsExclusiveUntilReleased() {
        val key = "gog:removal-hold-test"
        assertTrue(DownloadQueue.holdForRemoval(key))
        assertFalse(DownloadQueue.holdForRemoval(key))
        DownloadQueue.releaseRemoval(key)
        assertTrue(DownloadQueue.holdForRemoval(key))
        DownloadQueue.releaseRemoval(key)
    }
}
