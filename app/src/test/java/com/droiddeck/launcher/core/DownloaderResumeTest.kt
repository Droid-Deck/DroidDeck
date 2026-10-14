package com.droiddeck.launcher.core

import java.io.File
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.util.concurrent.CopyOnWriteArrayList
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * A download that was cut off keeps its bytes, and a second call with `resume` asks for the rest
 * with a Range header instead of starting again. The server is a plain socket so the first answer
 * can promise the whole file and then hang up halfway.
 */
class DownloaderResumeTest {
    private val data = ByteArray(300_000) { (it % 251).toByte() }
    private val cutAt = 100_000
    private val requests = CopyOnWriteArrayList<String?>()
    private lateinit var server: ServerSocket
    private lateinit var dir: File
    private lateinit var thread: Thread
    @Volatile private var misbehave = false

    @Before fun setUp() {
        dir = Files.createTempDirectory("downloader-resume").toFile()
        server = ServerSocket(0)
        thread = Thread {
            try {
                while (!server.isClosed) {
                    val socket = server.accept()
                    // the client may hang up on a body it refuses; that must not stop the server
                    runCatching { handle(socket) }
                }
            } catch (_: Exception) {
                // closed by tearDown
            }
        }.apply { isDaemon = true; start() }
    }

    @After fun tearDown() {
        server.close()
        dir.deleteRecursively()
    }

    private fun handle(socket: Socket) = socket.use {
        val input = it.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var range: String? = null
        while (true) {
            val line = input.readLine() ?: break
            if (line.isEmpty()) break
            if (line.startsWith("Range:", ignoreCase = true)) range = line.substringAfter(':').trim()
        }
        requests.add(range)
        val out = it.getOutputStream()
        val from = range?.removePrefix("bytes=")?.substringBefore('-')?.toIntOrNull()
        if (misbehave) {
            // answers a Range request with a 206 that starts at 0, and serves plain requests whole
            val start = if (from == null) "200 OK" else "206 Partial Content"
            val extra = if (from == null) "" else "Content-Range: bytes 0-${data.size - 1}/${data.size}\r\n"
            out.write("HTTP/1.1 $start\r\n${extra}Content-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(data)
            out.flush()
        } else if (from == null) {
            out.write("HTTP/1.1 200 OK\r\nContent-Length: ${data.size}\r\nConnection: close\r\n\r\n".toByteArray())
            out.write(data, 0, cutAt)
            out.flush()
            // hang up with most of the promised body still unsent
        } else {
            val rest = data.size - from
            out.write(("HTTP/1.1 206 Partial Content\r\nContent-Range: bytes $from-${data.size - 1}/${data.size}\r\n" +
                "Content-Length: $rest\r\nConnection: close\r\n\r\n").toByteArray())
            out.write(data, from, rest)
            out.flush()
        }
    }

    @Test fun cutOffDownloadResumesFromWhatWasKept() {
        val url = "http://127.0.0.1:${server.localPort}/runtime.tar.zst"
        val target = File(dir, "runtime.tar.zst")

        assertFalse("the cut-off transfer must not report success", Downloader.downloadFile(url, target, true, null))
        val kept = target.length()
        assertTrue("the partial bytes stay on disk (kept=$kept)", kept in 1 until data.size)

        assertTrue(Downloader.downloadFile(url, target, true, null))
        assertEquals(data.size.toLong(), target.length())
        assertArrayEquals(data, target.readBytes())
        assertEquals(listOf<String?>(null, "bytes=$kept-"), requests.toList())
    }

    @Test fun answerFromTheWrongOffsetIsNotAppended() {
        val url = "http://127.0.0.1:${server.localPort}/runtime.tar.zst"
        val target = File(dir, "runtime.tar.zst")

        assertFalse(Downloader.downloadFile(url, target, true, null))
        val kept = target.length()
        assertTrue(kept in 1 until data.size)

        misbehave = true
        assertTrue(Downloader.downloadFile(url, target, true, null))
        assertEquals(data.size.toLong(), target.length())
        assertArrayEquals("the file must not be the kept bytes plus a misplaced body", data, target.readBytes())
        assertEquals(listOf<String?>(null, "bytes=$kept-", null), requests.toList())
    }
}
