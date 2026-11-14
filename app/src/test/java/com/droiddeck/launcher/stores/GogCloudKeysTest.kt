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
        val requests = serve(2, ::okResponse) { url ->
            val cloud = GogCloud("user", "client", "token", "saves", url)
            val source = java.io.File.createTempFile("save", ".sav").apply { writeBytes(raw) }
            val result = try {
                cloud.putFiles(mapOf("save.sav" to source), mapOf("save.sav" to 1_793_490_825_000L)).getValue("save.sav")
            } finally {
                source.delete()
            }
            assertTrue(result.modifiedMs > 0)
            assertEquals(CloudSaves.md5(raw), result.md5)
        }
        val put = requests[0]
        assertEquals("PUT", put.method)
        assertEquals("/v1/user/client/saves%2Fsave.sav", put.path)
        assertEquals("gzip", put.headers["content-encoding"])
        assertEquals("2026-10-31T23:53:45+00:00", put.headers["x-object-meta-locallastmodified"])
        assertArrayEquals(gzipGogPut(raw), put.body)
        assertEquals(CloudSaves.md5(put.body), put.headers["etag"])
        assertArrayEquals(raw, gogDecodeCloudBody(put.body, put.headers["content-encoding"]))
        assertEquals("HEAD", requests[1].method)
    }

    @Test fun putStagesInAMissingTempDirByCreatingIt() {
        val root = java.nio.file.Files.createTempDirectory("gog-cloud-test").toFile()
        val temp = java.io.File(root, "cache")
        val source = java.io.File(root, "save.sav").apply { writeBytes("savegame0".toByteArray()) }
        try {
            val requests = serve(2, ::okResponse) { url ->
                GogCloud("user", "client", "token", "saves", url, tempDir = temp).putFiles(mapOf("save.sav" to source))
            }
            assertEquals("PUT", requests[0].method)
            assertTrue(temp.isDirectory)
            assertEquals(0, temp.listFiles()!!.size)
        } finally {
            temp.delete(); source.delete(); root.delete()
        }
    }

    @Test fun rejectedPutNamesStepStatusAndFileWithoutTheToken() {
        val secret = "eyJhbGciOiJIUzI1NiJ9.c2VjcmV0LXRva2VuLXZhbHVlLWxvbmc.c2lnbmF0dXJl"
        val source = java.io.File.createTempFile("save", ".sav").apply { writeBytes("savegame0".toByteArray()) }
        try {
            val failure = runCatching {
                serve(1, { _ ->
                    val body = "{\"error\":\"invalid_grant\",\"access_token\":\"$secret\"}"
                    "HTTP/1.1 403 Forbidden\r\nContent-Length: ${body.length}\r\nConnection: close\r\n\r\n$body"
                }) { url -> GogCloud("user", "client", secret, "saves", url).putFiles(mapOf("Save 3.ess" to source)) }
            }.exceptionOrNull()
            assertTrue(failure is java.io.IOException)
            val detail = cloudFailureDetail(failure!!)
            assertTrue(detail, detail.contains("GOG cloud put HTTP 403 file=Save 3.ess"))
            assertTrue(detail, detail.contains("invalid_grant"))
            assertFalse(detail, detail.contains(secret))
        } finally {
            source.delete()
        }
    }

    @Test fun failureDetailKeepsTheCauseChainAndDropsCredentials() {
        val e = java.io.IOException(
            "GOG cloud put file=Save12_C2B8E7B0_0_507269736F6E6572_Tamriel_000027.ess bytes=10",
            java.io.IOException("refused https://auth.gog.com/token?client_secret=abc&refresh_token=def Bearer xyz123", java.net.UnknownHostException("cloudstorage.gog.com")),
        )
        val detail = cloudFailureDetail(e)
        assertTrue(detail, detail.startsWith("IOException: GOG cloud put file=Save12_C2B8E7B0_0_507269736F6E6572_Tamriel_000027.ess"))
        assertTrue(detail, detail.contains("IOException: refused auth.gog.com"))
        assertTrue(detail, detail.endsWith("UnknownHostException: cloudstorage.gog.com"))
        for (leak in listOf("abc", "def", "xyz123", "client_secret", "/token")) assertFalse(detail, detail.contains(leak))
        assertEquals("IOException", cloudFailureDetail(java.io.IOException()))
    }

    @Test fun urlCredentialsAreDroppedAndOnlyTheHostIsKept() {
        val scrubbed = scrubCloudText("see https://alice:shortpass@example.com/private?x=1 and http://bob@gog.com:443/a")
        assertEquals("see example.com and gog.com:443", scrubbed)
        assertEquals("cloudstorage.gog.com", scrubCloudText("https://cloudstorage.gog.com/v1/user/saves"))
    }

    @Test fun errorSnippetReadsABoundedPrefixAndDropsATokenCutAtTheBoundary() {
        val read = java.util.concurrent.atomic.AtomicLong()
        val huge = object : java.io.InputStream() {
            override fun read(): Int { read.incrementAndGet(); return 'a'.code }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val prefix = "invalid_grant "
                for (i in 0 until len) {
                    val at = read.getAndIncrement()
                    b[off + i] = (if (at < prefix.length) prefix[at.toInt()] else 'q').code.toByte()
                }
                return len
            }
        }
        val snippet = cloudErrorSnippet(huge)
        assertTrue(read.get() <= 4097)
        assertEquals("invalid_grant", snippet)
        val cut = " ".repeat(4090) + "pw=shortsecretvalue-continues"
        assertEquals("", cloudErrorSnippet(cut.byteInputStream()))
        assertEquals("pw=shortsecretvalue-continues", cloudErrorSnippet((" ".repeat(10) + "pw=shortsecretvalue-continues").byteInputStream()))
        assertEquals("forbidden", cloudErrorSnippet("forbidden".byteInputStream()))
        assertEquals("", cloudErrorSnippet(null))
    }

    @Test fun failedResultLineCarriesTheDetailAndOtherLinesAreUnchanged() {
        assertEquals("result=failed files=0 bytes=0 reason=IOException detail=\"IOException: x\"",
            CloudSaves.Result("failed", 0, 0, "IOException", detail = "IOException: x").line)
        assertEquals("result=ok files=1 bytes=2 reason=uploaded conflicts=3", CloudSaves.Result("ok", 1, 2, "uploaded", 3).line)
    }
}

private fun okResponse(request: Request): String =
    if (request.method == "PUT") "HTTP/1.1 200 OK\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
    else "HTTP/1.1 200 OK\r\nContent-Length: 0\r\n" +
        "Etag: \"${CloudSaves.md5(request.body)}\"\r\n" +
        "Last-Modified: Tue, 20 Oct 2026 10:00:00 GMT\r\nConnection: close\r\n\r\n"

private fun serve(count: Int, respond: (Request) -> String, block: (String) -> Unit): List<Request> {
    val server = java.net.ServerSocket(0, 4, java.net.InetAddress.getByName("127.0.0.1"))
    val requests = java.util.concurrent.CopyOnWriteArrayList<Request>()
    val worker = Thread {
        runCatching {
            repeat(count) {
                server.accept().use { client ->
                    client.soTimeout = 5_000
                    val input = client.getInputStream()
                    val parts = readLine(input).split(' ')
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
                    val request = Request(parts[0], parts[1], headers, body)
                    requests += request
                    client.getOutputStream().apply { write(respond(request).toByteArray()); flush() }
                }
            }
        }
    }.apply { isDaemon = true; start() }
    try {
        block("http://127.0.0.1:${server.localPort}/v1/user/client")
    } finally {
        server.close()
        worker.join(2_000)
    }
    return requests
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
