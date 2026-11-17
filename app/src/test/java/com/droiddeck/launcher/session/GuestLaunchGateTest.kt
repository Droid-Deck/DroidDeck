package com.droiddeck.launcher.session

import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class GuestLaunchGateTest {
    private fun finishingThread(gate: GuestLaunchGate, finished: AtomicBoolean, done: CountDownLatch) =
        Thread {
            gate.await()
            finished.set(true)
            done.countDown()
        }.apply { start() }

    @Test fun stopFinishesImmediatelyWhenNoLaunchIsPending() {
        val finished = AtomicBoolean()
        val done = CountDownLatch(1)
        finishingThread(GuestLaunchGate(), finished, done)
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(finished.get())
    }

    @Test fun stopDuringGuestStartWaitsForTheLateGuestTeardown() {
        val gate = GuestLaunchGate()
        val finished = AtomicBoolean()
        val done = CountDownLatch(1)
        gate.begin()
        finishingThread(gate, finished, done)
        assertFalse(done.await(300, TimeUnit.MILLISECONDS))
        assertFalse(finished.get())
        gate.end()
        assertTrue(done.await(5, TimeUnit.SECONDS))
        assertTrue(finished.get())
    }

    @Test fun stopWaitsForEveryPendingLaunch() {
        val gate = GuestLaunchGate()
        val finished = AtomicBoolean()
        val done = CountDownLatch(1)
        gate.begin()
        gate.begin()
        finishingThread(gate, finished, done)
        gate.end()
        assertFalse(done.await(300, TimeUnit.MILLISECONDS))
        gate.end()
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }

    @Test fun aLaunchThatEndedBeforeTheStopDoesNotBlockIt() {
        val gate = GuestLaunchGate()
        gate.begin()
        gate.end()
        val finished = AtomicBoolean()
        val done = CountDownLatch(1)
        finishingThread(gate, finished, done)
        assertTrue(done.await(5, TimeUnit.SECONDS))
    }
}
