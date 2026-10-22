package com.droiddeck.launcher.glasses

import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class StereoInferenceRunnerTest {
    @Test fun camerasRunConcurrentlyAndRightDetectorKeepsItsOwnerThread() {
        StereoInferenceRunner().use { runner ->
            val owner=runner.onRight { Thread.currentThread() }
            val rightStarted=CountDownLatch(1)
            val leftStarted=CountDownLatch(1)
            val pair=runner.pair({
                leftStarted.countDown()
                assertTrue(rightStarted.await(2,TimeUnit.SECONDS))
                1
            }, {
                rightStarted.countDown()
                assertTrue(leftStarted.await(2,TimeUnit.SECONDS))
                assertSame(owner,Thread.currentThread())
                2
            })
            assertEquals(1 to 2,pair)
            assertSame(owner,runner.onRight { Thread.currentThread() })
        }
    }

    @Test fun leftFailureDoesNotReleasePixelsWhileRightStillUsesThem() {
        StereoInferenceRunner().use { runner ->
            val finished=CountDownLatch(1)
            val failure=IllegalStateException("left")
            try {
                runner.pair({ throw failure }, { Thread.sleep(25);finished.countDown();1 })
                fail("Expected inference failure")
            } catch(e: IllegalStateException) { assertSame(failure,e) }
            assertEquals(0L,finished.count)
        }
    }

    @Test fun rightFailureReachesOwnerAndWorkerCanStillCloseDetector() {
        StereoInferenceRunner().use { runner ->
            val failure=IllegalStateException("right")
            try {
                runner.pair({ 1 }, { throw failure })
                fail("Expected inference failure")
            } catch(e: IllegalStateException) { assertSame(failure,e) }
            assertTrue(runner.onRight { true })
        }
    }

    @Test fun shutdownInterruptionWaitsForInferenceBeforeReturningBuffers() {
        StereoInferenceRunner().use { runner ->
            val owner=Executors.newSingleThreadExecutor()
            val running=CountDownLatch(1);val release=CountDownLatch(1)
            try {
                val result=owner.submit<Boolean> {
                    Thread.currentThread().interrupt()
                    assertEquals(1 to 2,runner.pair({ 1 }, {
                        running.countDown();check(release.await(2,TimeUnit.SECONDS));2
                    }))
                    Thread.interrupted()
                }
                assertTrue(running.await(2,TimeUnit.SECONDS))
                assertFalse(result.isDone)
                release.countDown()
                assertTrue(result.get(2,TimeUnit.SECONDS))
            } finally { release.countDown();owner.shutdownNow() }
        }
    }
}
