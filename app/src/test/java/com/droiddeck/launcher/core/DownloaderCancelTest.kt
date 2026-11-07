package com.droiddeck.launcher.core

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.InputStream
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit

class DownloaderCancelTest {
    private val previousHandler = Thread.getDefaultUncaughtExceptionHandler()
    private val uncaught = CopyOnWriteArrayList<Throwable>()

    @After fun tearDown() {
        Thread.setDefaultUncaughtExceptionHandler(previousHandler)
    }

    private val cancelExecutor: ExecutorService
        get() = Downloader::class.java.getDeclaredField("CANCEL_EXECUTOR")
            .apply { isAccessible = true }
            .get(null) as ExecutorService

    private fun cancelWhileClosingThrows(failure: RuntimeException): Thread {
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
        return closer!!
    }

    private fun survivesItsTask(closer: Thread): Boolean {
        val pool = cancelExecutor as ThreadPoolExecutor
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        while (System.nanoTime() < deadline) {
            if (!closer.isAlive) return false
            if (pool.activeCount == 0 && closer.state == Thread.State.TIMED_WAITING) return true
            Thread.sleep(5)
        }
        error("the cancel thread neither ended nor went back to waiting for work")
    }

    @Test fun unbalancedTimeoutOnCancelDoesNotCrash() {
        val closer = cancelWhileClosingThrows(IllegalStateException("Unbalanced enter/exit"))

        assertTrue(survivesItsTask(closer))
        assertEquals(emptyList<Throwable>(), uncaught.toList())
    }

    @Test fun otherCloseFailuresStillSurface() {
        val failure = UnsupportedOperationException("close")
        val closer = cancelWhileClosingThrows(failure)

        closer.join(TimeUnit.SECONDS.toMillis(5))
        assertFalse(closer.isAlive)
        assertEquals(listOf<Throwable>(failure), uncaught.toList())
    }
}
