package com.droiddeck.launcher.core

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.BufferedInputStream
import java.io.File
import java.net.ServerSocket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class DownloaderCancellationTest {
    @Test fun cancellationDisconnectsAStalledDownload() {
        val server = ServerSocket(0)
        val firstByteSent = CountDownLatch(1)
        val keepOpen = CountDownLatch(1)
        val cancelled = AtomicBoolean()
        val destination = File.createTempFile("download-cancel", ".part")
        val executor = Executors.newSingleThreadExecutor()
        val serverThread = Thread {
            server.accept().use { socket ->
                val input = BufferedInputStream(socket.getInputStream())
                var matched = 0
                while (matched < 4) {
                    val next = input.read()
                    matched = when {
                        matched == 0 && next == '\r'.code -> 1
                        matched == 1 && next == '\n'.code -> 2
                        matched == 2 && next == '\r'.code -> 3
                        matched == 3 && next == '\n'.code -> 4
                        next == '\r'.code -> 1
                        else -> 0
                    }
                }
                socket.getOutputStream().apply {
                    write("HTTP/1.1 200 OK\r\nContent-Length: 10\r\nConnection: close\r\n\r\nx".toByteArray())
                    flush()
                }
                firstByteSent.countDown()
                keepOpen.await(5, TimeUnit.SECONDS)
            }
        }.apply { isDaemon = true; start() }
        try {
            val result = executor.submit<Boolean> {
                Downloader.downloadFile(
                    "http://127.0.0.1:${server.localPort}/stalled",
                    destination,
                    false,
                    null,
                    java.util.function.BooleanSupplier { cancelled.get() },
                )
            }
            assertTrue(firstByteSent.await(5, TimeUnit.SECONDS))
            cancelled.set(true)
            assertFalse(result.get(3, TimeUnit.SECONDS))
        } finally {
            cancelled.set(true)
            keepOpen.countDown()
            server.close()
            serverThread.join(5000)
            executor.shutdownNow()
            destination.delete()
        }
    }
}
