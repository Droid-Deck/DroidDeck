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
