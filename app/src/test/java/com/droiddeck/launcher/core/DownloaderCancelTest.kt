package com.droiddeck.launcher.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class DownloaderCancelTest {
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private fun cancelWhileClosingThrows(failure: RuntimeException) {
        Thread.setDefaultUncaughtExceptionHandler { _, error -> uncaught += error }
        val closed = CountDownLatch(1)
        var closer: Thread? = null
        val stream = object : InputStream() {
            override fun read(): Int = -1
            override fun close() {
                closer = Thread.currentThread()
                closed.countDown()
                throw failure
            }
        }
        val cancellation = Downloader.Cancellation()
        Downloader.Cancellation::class.java.getDeclaredMethod("attach", InputStream::class.java)
            .apply { isAccessible = true }
            .invoke(cancellation, stream)

        cancellation.cancel()

        assertTrue(closed.await(5, TimeUnit.SECONDS))
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline && uncaught.isEmpty()) {
            val state = closer!!.state
            if (state == Thread.State.TERMINATED || state == Thread.State.TIMED_WAITING || state == Thread.State.WAITING) break
            Thread.sleep(10)
        }
    }

    @Test fun unbalancedTimeoutOnCancelDoesNotCrash() {
        cancelWhileClosingThrows(IllegalStateException("Unbalanced enter/exit"))

        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    @Test fun otherCloseFailuresStillSurface() {
        val failure = UnsupportedOperationException("close")
        cancelWhileClosingThrows(failure)

        assertEquals(listOf<Throwable>(failure), uncaught.toList())
    }
}
