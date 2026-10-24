package com.droiddeck.launcher.sync

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream

/** Just enough HTTP/1.1 to talk to Syncthing over its Unix socket, one request per connection. */
internal object SyncthingHttp {
    private const val LINE_LIMIT = 16 shl 10
    private const val HEADER_LIMIT = 100

    fun writeRequest(out: OutputStream, method: String, path: String, headers: List<Pair<String, String>>, body: ByteArray?) {
        val head = StringBuilder("$method $path HTTP/1.1\r\nHost: localhost\r\nConnection: close\r\n")
        headers.forEach { (name, value) -> head.append(name).append(": ").append(value).append("\r\n") }
        head.append("Content-Length: ").append(body?.size ?: 0).append("\r\n\r\n")
        out.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        body?.let(out::write)
        out.flush()
    }

    fun readResponse(input: InputStream): Pair<Int, String> {
        val status = readLine(input) ?: throw IOException("no response")
        val code = status.split(' ').getOrNull(1)?.toIntOrNull() ?: throw IOException("bad status line: $status")
        val headers = readHeaders(input)
        fun header(name: String) = headers.lastOrNull { it.first.equals(name, ignoreCase = true) }?.second
        val body = when {
            header("Transfer-Encoding")?.contains("chunked", ignoreCase = true) == true -> chunked(input)
            header("Content-Length") != null -> exactly(input, header("Content-Length")!!.trim().toInt())
            else -> input.readBytes()
        }
        return code to String(body, Charsets.UTF_8)
    }

    /** Reads one CRLF-terminated line, or null at end of stream. */
    fun readLine(input: InputStream): String? {
        val line = ByteArrayOutputStream()
        while (true) {
            val b = input.read()
            if (b == -1) return if (line.size() == 0) null else throw IOException("truncated line")
            if (b == '\n'.code) break
            if (line.size() >= LINE_LIMIT) throw IOException("line too long")
            line.write(b)
        }
        return line.toString("ISO-8859-1").removeSuffix("\r")
    }

    fun readHeaders(input: InputStream): List<Pair<String, String>> {
        val headers = mutableListOf<Pair<String, String>>()
        while (true) {
            val line = readLine(input) ?: throw IOException("truncated headers")
            if (line.isEmpty()) return headers
            if (headers.size >= HEADER_LIMIT) throw IOException("too many headers")
            val colon = line.indexOf(':')
            if (colon <= 0) throw IOException("bad header: $line")
            headers += line.substring(0, colon).trim() to line.substring(colon + 1).trim()
        }
    }

    private fun chunked(input: InputStream): ByteArray {
        val out = ByteArrayOutputStream()
        while (true) {
            val size = readLine(input)?.substringBefore(';')?.trim()?.toIntOrNull(16) ?: throw IOException("bad chunk size")
            if (size == 0) break
            out.write(exactly(input, size))
            readLine(input)
        }
        while (readLine(input)?.isNotEmpty() == true) Unit
        return out.toByteArray()
    }

    private fun exactly(input: InputStream, size: Int): ByteArray {
        val bytes = ByteArray(size)
        var read = 0
        while (read < size) {
            val n = input.read(bytes, read, size - read)
            if (n == -1) throw IOException("truncated body")
            read += n
        }
        return bytes
    }
}
