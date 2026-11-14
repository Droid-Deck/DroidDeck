package com.droiddeck.launcher.sync

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

class SyncthingHttpTest {
    private fun response(raw: String) = SyncthingHttp.readResponse(raw.byteInputStream(Charsets.ISO_8859_1))

    @Test
    fun readsContentLengthBody() {
        assertEquals(200 to """{"ping":"pong"}""", response("HTTP/1.1 200 OK\r\nContent-Length: 15\r\n\r\n{\"ping\":\"pong\"}"))
    }

    @Test
    fun readsChunkedBody() {
        assertEquals(403 to "CSRF Error", response("HTTP/1.1 403 Forbidden\r\nTransfer-Encoding: chunked\r\n\r\n4\r\nCSRF\r\n6\r\n Error\r\n0\r\n\r\n"))
    }

    @Test
    fun writesOneShotRequest() {
        val out = ByteArrayOutputStream()
        SyncthingHttp.writeRequest(out, "POST", "/rest/system/pause", listOf("X-API-Key" to "k"), null)
        val text = out.toString("ISO-8859-1")
        assertTrue(text.startsWith("POST /rest/system/pause HTTP/1.1\r\n"))
        assertTrue(text.contains("Connection: close\r\n"))
        assertTrue(text.endsWith("X-API-Key: k\r\nContent-Length: 0\r\n\r\n"))
    }
}
