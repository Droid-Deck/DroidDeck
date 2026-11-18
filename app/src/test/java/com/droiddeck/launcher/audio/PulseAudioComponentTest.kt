package com.droiddeck.launcher.audio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.Executors

class PulseAudioComponentTest {
    @Test
    fun stopCancelsSetupAndAcceptedRestart() {
        val killed = mutableListOf<Int>()
        val host = PulseAudioComponent.HostLifecycle { killed.add(it) }
        val generation = host.begin()
        assertTrue(host.launch(generation) { 31463 })
        assertTrue(host.exited(generation))
        host.stop()
        assertFalse(host.launch(generation) { error("cancelled restart launched") })
        val next = host.begin()
        host.stop()
        assertFalse(host.launch(next) { error("cancelled setup launched") })
        assertEquals(-1, host.pid)
    }

    @Test
    fun explicitStartStillWorksAndStaleExitCannotRestartIt() {
        val killed = mutableListOf<Int>()
        val host = PulseAudioComponent.HostLifecycle { killed.add(it) }
        val first = host.begin()
        host.launch(first) { 31463 }
        val second = host.begin()
        host.launch(second) { 31464 }
        assertEquals(listOf(31463), killed)
        assertFalse(host.exited(first))
        assertEquals(31464, host.pid)
        host.stop()
        assertEquals(listOf(31463, 31464), killed)
        assertFalse(host.exited(second))
    }

    @Test
    fun anOvertakenRestartIsNoLongerCurrent() {
        val host = PulseAudioComponent.HostLifecycle { }
        val first = host.begin()
        host.launch(first) { 31463 }
        assertTrue(host.exited(first))
        assertTrue(host.current(first))
        val second = host.begin()
        assertFalse(host.current(first))
        assertTrue(host.current(second))
    }

    @Test
    fun aSinkGuardOnlyActsOnItsOwnLaunch() {
        val host = PulseAudioComponent.HostLifecycle { }
        val generation = host.begin()
        host.launch(generation) { 31463 }
        val first = host.token()
        assertTrue(host.live(first))
        assertTrue(host.exited(generation))
        assertFalse(host.live(first))
        host.launch(generation) { 31464 }
        assertFalse(host.live(first))
        assertTrue(host.live(host.token()))
        host.stop()
        assertFalse(host.live(host.token()))
    }

    @Test
    fun aRestartWaitsForAnInFlightSinkGuardCommand() {
        val killed = mutableListOf<Int>()
        val host = PulseAudioComponent.HostLifecycle { killed.add(it) }
        val generation = host.begin()
        host.launch(generation) { 31463 }
        val first = host.token()
        val checked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pool = Executors.newFixedThreadPool(2)
        try {
            val command = pool.submit<String> {
                host.command(first) {
                    checked.countDown()
                    resume.await()
                    "old host"
                }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            assertTrue(host.exited(generation))
            val launched = CountDownLatch(1)
            val restart = pool.submit<Boolean> {
                host.launch(generation) {
                    launched.countDown()
                    31464
                }
            }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
            while (!host.commands.hasQueuedThreads() && !restart.isDone && System.nanoTime() < deadline) Thread.yield()
            assertTrue(host.commands.hasQueuedThreads())
            assertEquals(1L, launched.count)
            assertFalse(restart.isDone)
            resume.countDown()
            assertEquals("old host", command.get(5, TimeUnit.SECONDS))
            assertTrue(restart.get(5, TimeUnit.SECONDS))
            assertEquals(31464, host.pid)
            assertEquals(null, host.command(first) { error("stale guard reached the replacement host") })
            assertEquals("new host", host.command(host.token()) { "new host" })
            assertTrue(killed.isEmpty())
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun stopIsNotHeldUpByAnInFlightSinkGuardCommand() {
        val killed = mutableListOf<Int>()
        val host = PulseAudioComponent.HostLifecycle { killed.add(it) }
        host.launch(host.begin()) { 31463 }
        val token = host.token()
        val checked = CountDownLatch(1)
        val resume = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            val command = pool.submit<String> {
                host.command(token) {
                    checked.countDown()
                    resume.await()
                    "done"
                }
            }
            assertTrue(checked.await(5, TimeUnit.SECONDS))
            host.stop()
            assertEquals(listOf(31463), killed)
            assertFalse(host.live(token))
            resume.countDown()
            assertEquals("done", command.get(5, TimeUnit.SECONDS))
        } finally {
            resume.countDown()
            pool.shutdownNow()
        }
    }

    @Test
    fun failedLaunchDoesNotRestartOrTerminateAnInvalidPid() {
        val host = PulseAudioComponent.HostLifecycle { error("invalid pid terminated") }
        val generation = host.begin()
        assertTrue(host.launch(generation) { -1 })
        assertFalse(host.exited(generation))
        host.stop()
    }

    @Test
    fun earlyExitWaitsForPidPublication() {
        val host = PulseAudioComponent.HostLifecycle { }
        val generation = host.begin()
        val entered = CountDownLatch(1)
        val pool = Executors.newSingleThreadExecutor()
        try {
            lateinit var exit: java.util.concurrent.Future<Boolean>
            assertTrue(host.launch(generation) {
                exit = pool.submit<Boolean> {
                    entered.countDown()
                    host.exited(generation)
                }
                assertTrue(entered.await(5, TimeUnit.SECONDS))
                assertFalse(exit.isDone)
                31463
            })
            assertTrue(exit.get(5, TimeUnit.SECONDS))
            assertEquals(-1, host.pid)
            assertTrue(host.launch(generation) { 31464 })
            assertEquals(31464, host.pid)
        } finally {
            pool.shutdownNow()
        }
    }
}
