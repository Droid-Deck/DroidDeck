package com.droiddeck.launcher.core

import java.io.File
import java.net.ServerSocket
import java.nio.file.Files
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class DownloaderFailureTest {
    private lateinit var dir: File

    @Before fun setUp() {
        dir = Files.createTempDirectory("downloader-failure").toFile()
    }

    @After fun tearDown() {
        dir.deleteRecursively()
    }

    private fun serve(status: String, body: ByteArray = ByteArray(0)): ServerSocket {
        val server = ServerSocket(0)
        Thread {
            runCatching {
                server.accept().use { socket ->
                    val input = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
                    while (true) {
                        val line = input.readLine() ?: break
                        if (line.isEmpty()) break
                    }
                    socket.getOutputStream().apply {
                        write("HTTP/1.1 $status\r\nContent-Length: ${body.size}\r\nConnection: close\r\n\r\n".toByteArray())
                        write(body)
                        flush()
                    }
                }
            }
        }.apply { isDaemon = true; start() }
        return server
    }

    private fun download(url: String): Boolean =
        Downloader.downloadCandidates(url, listOf(url), File(dir, "file"), false, null, null)

    @Test fun anHttpRefusalIsReportedWithTheHostAndCode() {
        serve("403 Forbidden").use { server ->
            assertFalse(download("http://127.0.0.1:${server.localPort}/pack.tar.zst"))
        }
        assertEquals("127.0.0.1: HTTP 403", Downloader.lastFailure())
    }

    @Test fun aRefusedConnectionIsReportedWithItsException() {
        val port = ServerSocket(0).use { it.localPort }
        assertFalse(download("http://127.0.0.1:$port/pack.tar.zst"))
        val failure = Downloader.lastFailure().orEmpty()
        assertTrue(failure, failure.startsWith("127.0.0.1: ConnectException"))
    }

    @Test fun aSuccessfulDownloadClearsTheEarlierFailure() {
        serve("500 Internal Server Error").use { server ->
            assertFalse(download("http://127.0.0.1:${server.localPort}/pack.tar.zst"))
        }
        serve("200 OK", "data".toByteArray()).use { server ->
            assertTrue(download("http://127.0.0.1:${server.localPort}/pack.tar.zst"))
        }
        assertNull(Downloader.lastFailure())
    }

    @Test fun everyFailedSourceIsListedInOrder() {
        val first = serve("403 Forbidden")
        val second = serve("503 Service Unavailable")
        val urls = listOf(first, second).map { "http://127.0.0.1:${it.localPort}/pack.tar.zst" }
        try {
            assertFalse(Downloader.downloadCandidates(urls[0], urls, File(dir, "file"), false, null, null))
        } finally {
            first.close()
            second.close()
        }
        assertEquals("127.0.0.1: HTTP 403; 127.0.0.1: HTTP 503", Downloader.lastFailure())
    }
}
