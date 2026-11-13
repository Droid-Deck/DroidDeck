package com.droiddeck.launcher.glasses

import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class VitureCameraWorkerTest {
    @Test fun replacementWaitsForClosedCameraCaptureAndCleanupWithoutBlockingCaller() {
        val old = VitureCameraWorker()
        val replacement = VitureCameraWorker()
        val captureStarted = CountDownLatch(1)
        val finishCapture = CountDownLatch(1)
        val cleanupStarted = CountDownLatch(1)
        val finishCleanup = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        try {
            old.execute {
                try {
                    events += "open old"
                    captureStarted.countDown()
                    check(finishCapture.await(2, TimeUnit.SECONDS))
                } finally {
                    cleanupStarted.countDown()
                    check(finishCleanup.await(2, TimeUnit.SECONDS))
                    events += "close native old"
                    events += "close USB old"
                }
            }
            assertTrue(captureStarted.await(2, TimeUnit.SECONDS))
            old.close()
            replacement.execute {
                events += "open replacement"
                replacementStarted.countDown()
            }
            assertEquals(1L, replacementStarted.count)
            finishCapture.countDown()
            assertTrue(cleanupStarted.await(2, TimeUnit.SECONDS))
            assertEquals(1L, replacementStarted.count)
            finishCleanup.countDown()
            assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("open old", "close native old", "close USB old", "open replacement"), events)
        } finally {
            finishCapture.countDown()
            finishCleanup.countDown()
            old.close()
            replacement.close()
        }
    }

    @Test fun closedReplacementIsSkippedWhileAnotherCameraFinishes() {
        val old = VitureCameraWorker()
        val canceled = VitureCameraWorker()
        val replacement = VitureCameraWorker()
        val captureStarted = CountDownLatch(1)
        val finishCapture = CountDownLatch(1)
        val replacementStarted = CountDownLatch(1)
        val events = CopyOnWriteArrayList<String>()
        try {
            old.execute {
                captureStarted.countDown()
                check(finishCapture.await(2, TimeUnit.SECONDS))
            }
            assertTrue(captureStarted.await(2, TimeUnit.SECONDS))
            canceled.execute { events += "open canceled" }
            canceled.close()
            replacement.execute {
                events += "open replacement"
                replacementStarted.countDown()
            }
            old.close()
            finishCapture.countDown()
            assertTrue(replacementStarted.await(2, TimeUnit.SECONDS))
            assertEquals(listOf("open replacement"), events)
        } finally {
            finishCapture.countDown()
            old.close()
            canceled.close()
            replacement.close()
        }
    }
}
