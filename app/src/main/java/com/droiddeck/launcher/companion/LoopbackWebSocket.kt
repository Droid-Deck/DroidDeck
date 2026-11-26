package com.droiddeck.launcher.companion

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.security.SecureRandom
import java.util.Base64

class LoopbackWebSocket(port: Int, path: String = "/", connectTimeoutMs: Int = 1_000, readTimeoutMs: Int = 0) : AutoCloseable {
    private val socket = Socket()
    private val out: OutputStream
    private val input: DataInputStream
    private val random = SecureRandom()

    init {
        try {
            socket.connect(InetSocketAddress("127.0.0.1", port), connectTimeoutMs)
            socket.soTimeout = readTimeoutMs
            socket.tcpNoDelay = true
            out = socket.getOutputStream()
            input = DataInputStream(socket.getInputStream().buffered())
            val key = Base64.getEncoder().encodeToString(ByteArray(16).also(random::nextBytes))
            out.write(("GET $path HTTP/1.1\r\nHost: 127.0.0.1:$port\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                "Sec-WebSocket-Key: $key\r\nSec-WebSocket-Version: 13\r\n\r\n").toByteArray())
            out.flush()
            val status = line(input) ?: throw IOException("no upgrade response")
            if (!status.contains(" 101")) throw IOException("upgrade refused: $status")
            while (true) {
                val header = line(input) ?: throw IOException("upgrade cut short")
                if (header.isEmpty()) break
            }
        } catch (e: Exception) {
            runCatching { socket.close() }
            throw e
        }
    }

    @Synchronized
    fun send(text: String) = frame(0x1, text.toByteArray())

    fun receive(): String? {
        val message = ByteArrayOutputStream()
        while (true) {
            val b0 = input.read().takeIf { it >= 0 } ?: return null
            val b1 = input.read().takeIf { it >= 0 } ?: return null
            var length = (b1 and 0x7F).toLong()
            if (length == 126L) length = input.readUnsignedShort().toLong() else if (length == 127L) length = input.readLong()
            if (length < 0 || length > MAX_FRAME) throw IOException("frame too large")
            val mask = if (b1 and 0x80 != 0) ByteArray(4).also(input::readFully) else null
            val data = ByteArray(length.toInt()).also(input::readFully)
            if (mask != null) for (i in data.indices) data[i] = (data[i].toInt() xor mask[i % 4].toInt()).toByte()
            when (b0 and 0x0F) {
                0x8 -> return null
                0x9 -> synchronized(this) { frame(0xA, data) }
                0x0, 0x1, 0x2 -> {
                    message.write(data)
                    if (message.size() > MAX_FRAME) throw IOException("message too large")
                    if (b0 and 0x80 != 0) return message.toString(Charsets.UTF_8.name())
                }
            }
        }
    }

    private fun frame(opcode: Int, payload: ByteArray) {
        val mask = ByteArray(4).also(random::nextBytes)
        val frame = ByteArrayOutputStream(payload.size + 14)
        frame.write(0x80 or opcode)
        when {
            payload.size < 126 -> frame.write(0x80 or payload.size)
            payload.size < 65536 -> {
                frame.write(0x80 or 126)
                frame.write(payload.size ushr 8)
                frame.write(payload.size and 0xFF)
            }
            else -> {
                frame.write(0x80 or 127)
                for (k in 7 downTo 0) frame.write(((payload.size.toLong() ushr (8 * k)) and 0xFF).toInt())
            }
        }
        frame.write(mask)
        for (i in payload.indices) frame.write((payload[i].toInt() xor mask[i % 4].toInt()) and 0xFF)
        out.write(frame.toByteArray())
        out.flush()
    }

    private fun line(s: InputStream): String? {
        val buf = ByteArrayOutputStream()
        while (true) {
            val b = s.read()
            if (b < 0) return if (buf.size() == 0) null else buf.toString(Charsets.UTF_8.name())
            if (b == '\n'.code) return buf.toString(Charsets.UTF_8.name()).trimEnd('\r')
            if (buf.size() > 8192) throw IOException("header line too long")
            buf.write(b)
        }
    }

    override fun close() {
        runCatching { synchronized(this) { frame(0x8, ByteArray(0)) } }
        runCatching { socket.close() }
    }

    private companion object {
        const val MAX_FRAME = 16L * 1024 * 1024
    }
}
