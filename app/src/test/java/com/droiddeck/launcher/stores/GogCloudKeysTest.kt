package com.droiddeck.launcher.stores

import java.io.ByteArrayOutputStream
import java.util.zip.GZIPOutputStream
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class GogCloudKeysTest {
    @Test fun defaultLocationIsStrippedLikeAnyNamedLocation() {
        val files = listOf(CloudFile("__default/savegame0.sav", 1L), CloudFile("saves/slot0.sav", 2L))
        val (chosen, relative) = gogRelativeNames(files, "__default")
        assertEquals("__default", chosen)
        assertEquals(listOf("savegame0.sav"), relative.map { it.name })
    }

    @Test fun gzipGogPutMatchesPythonGzipCompressLevel6Mtime0() {
        val raw = "savegame0".toByteArray()
        val expected = "1f8b08000000000000032b4e2c4b4d4fcc4d35000065fc333209000000".hex()
        assertArrayEquals(expected, gzipGogPut(raw))
        assertArrayEquals(raw, gunzip(expected))
    }

    @Test fun contentEncodingGzipIsPeeledOnce() {
        val raw = "savegame0".toByteArray()
        val gz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(raw) } }.toByteArray()
        assertArrayEquals(raw, gogDecodeCloudBody(gz, "gzip"))
        assertArrayEquals(raw, gogDecodeCloudBody(raw, "gzip"))
    }

    @Test fun corruptDeclaredGzipIsRejected() {
        val corrupt = gzipGogPut("savegame0".toByteArray()).dropLast(2).toByteArray()
        assertTrue(isGzip(corrupt))
        assertTrue(runCatching { gogDecodeCloudBody(corrupt, "gzip") }.isFailure)
    }

    @Test fun isoDateHonorsOffsetAndFractionalSeconds() {
        val expected = java.time.Instant.parse("2024-01-02T01:04:05.123Z").toEpochMilli()
        assertEquals(expected, isoDate("2024-01-02T03:04:05.123+02:00"))
        assertEquals(expected, isoDate("2024-01-02T01:04:05.123"))
    }

    @Test fun gogPutWrappedBodyWithoutEncodingIsPeeled() {
        val raw = "savegame0".toByteArray()
        assertArrayEquals(raw, gogDecodeCloudBody(gzipGogPut(raw), null))
        assertArrayEquals(raw, gogDecodeCloudBody(raw, null))
    }

    @Test fun gzipThatIsTheSaveIsLeftIntact() {
        val raw = "savegame0".toByteArray()
        val gameGz = ByteArrayOutputStream().also { GZIPOutputStream(it).use { z -> z.write(raw) } }.toByteArray()
        assertTrue(isGzip(gameGz))
        assertFalse(gzipGogPut(raw).contentEquals(gameGz))
        assertArrayEquals(gameGz, gogDecodeCloudBody(gameGz, null))
    }

    @Test fun putUploadsTheGogGzipBodyWithEncodingAndCompressedHash() {
        val raw = "savegame0".toByteArray()
        val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
        val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
        val worker = Thread {
            repeat(2) {
                server.accept().use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream()
                    val line = readLine(input)
                    val parts = line.split(' ')
                    val headers = LinkedHashMap<String, String>()
                    while (true) {
                        val h = readLine(input)
                        if (h.isEmpty()) break
                        val colon = h.indexOf(':')
                        headers[h.substring(0, colon).trim().lowercase()] = h.substring(colon + 1).trim()
                    }
                    val length = headers["content-length"]?.toIntOrNull() ?: 0
                    val body = ByteArray(length)
                    var offset = 0
                    while (offset < length) {
                        val n = input.read(body, offset, length - offset)
                        if (n < 0) break
                        offset += n
                    }
                    requests += Request(parts[0], parts[1], headers, body)
                    val response = if (parts[0] == "PUT") {
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                    } else {
                        "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n" +
                            "Etag: \"${CloudSaves.md5(body)}\"\r\n" +
                            "Last-Modified: Tue, 20 Oct 2026 10:00:00 GMT\r\nConnection: close\r\n\r\n"
                    }
                    client.getOutputStream().apply { write(response.toByteArray()); flush() }
                }
            }
        }.apply { isDaemon = true; start() }
        try {
            val cloud = GogCloud("user", "client", "token", "saves", "http://127.0.0.1:${server.localPort}/v1/user/client")
            val source = java.io.File.createTempFile("save", ".sav").apply { writeBytes(raw) }
            val result = try {
                cloud.putFiles(mapOf("save.sav" to source), mapOf("save.sav" to 1_793_490_825_000L)).getValue("save.sav")
            } finally {
                source.delete()
            }
            assertTrue(result.modifiedMs > 0)
            assertEquals(CloudSaves.md5(raw), result.md5)
            val put = requests[0]
            assertEquals("PUT", put.method)
            assertEquals("/v1/user/client/saves%2Fsave.sav", put.path)
            assertEquals("gzip", put.headers["content-encoding"])
            assertEquals("2026-10-31T23:53:45+00:00", put.headers["x-object-meta-locallastmodified"])
            assertArrayEquals(gzipGogPut(raw), put.body)
            assertEquals(CloudSaves.md5(put.body), put.headers["etag"])
            assertArrayEquals(raw, gogDecodeCloudBody(put.body, put.headers["content-encoding"]))
            assertEquals("HEAD", requests[1].method)
        } finally {
            server.close()
            worker.join(2_000)
        }
    }
}

private class Request(val method: String, val path: String, val headers: Map<String, String>, val body: ByteArray)

private fun readLine(input: java.io.InputStream): String {
    val out = ByteArrayOutputStream()
    while (true) {
        val b = input.read()
        if (b < 0 || b == '\n'.code) break
        if (b != '\r'.code) out.write(b)
    }
    return out.toString("UTF-8")
}

private fun String.hex() = chunked(2).map { it.toInt(16).toByte() }.toByteArray()
