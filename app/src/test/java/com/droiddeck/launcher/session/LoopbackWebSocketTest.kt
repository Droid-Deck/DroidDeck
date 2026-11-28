package com.droiddeck.launcher.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test
import java.io.DataInputStream
import java.io.IOException
import java.net.ServerSocket
import kotlin.concurrent.thread

class LoopbackWebSocketTest {
    @Test
    fun talksToALoopbackWebSocketServer() {
        val server = ServerSocket(0).apply { soTimeout = 5_000 }
        var received: String? = null
        val worker = thread {
            server.accept().use { client ->
                client.soTimeout = 5_000
                val input = DataInputStream(client.getInputStream())
                val out = client.getOutputStream()
                out.write(upgrade(handshakeKey(input)).toByteArray())
                for (text in listOf("""{"id":1}""", "x".repeat(300))) {
                    val bytes = text.toByteArray()
                    out.write(0x81)
                    if (bytes.size < 126) out.write(bytes.size) else { out.write(126); out.write(bytes.size shr 8); out.write(bytes.size and 0xFF) }
                    out.write(bytes)
                }
                out.flush()
                val b0 = input.read()
                val len = input.read() and 0x7F
                val mask = ByteArray(4).also(input::readFully)
                val payload = ByteArray(len).also(input::readFully)
                for (i in payload.indices) payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                if (b0 == 0x81) received = String(payload)
            }
        }
        try {
            LoopbackWebSocket(server.localPort, readTimeoutMs = 5_000).use { ws ->
                assertEquals("""{"id":1}""", ws.receive())
                assertEquals(300, ws.receive()!!.length)
                ws.send("""{"id":2}""")
            }
            worker.join(5_000)
        } finally {
            server.close()
        }
        assertEquals("""{"id":2}""", received)
    }

    @Test
    fun rejectsAnUpgradeWithoutTheRightAcceptHeader() {
        for (reply in listOf(
            "HTTP/1.1 101 Switching Protocols\r\n\r\n",
            "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: wrong\r\n\r\n",
            "HTTP/1.1 1010 Nope\r\n\r\n",
        )) {
            val server = ServerSocket(0)
            val worker = thread {
                server.accept().use { client ->
                    handshakeKey(DataInputStream(client.getInputStream()))
                    client.getOutputStream().apply { write(reply.toByteArray()); flush() }
                    Thread.sleep(200)
                }
            }
            assertThrows(IOException::class.java) { LoopbackWebSocket(server.localPort).close() }
            worker.join(5_000)
            server.close()
        }
    }

    private fun upgrade(key: String) = "HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
        "Sec-WebSocket-Accept: ${LoopbackWebSocket.accept(key)}\r\n\r\n"

    private fun handshakeKey(input: DataInputStream): String =
        generateSequence { readLine(input) }.takeWhile { it.isNotEmpty() }.toList()
            .first { it.startsWith("Sec-WebSocket-Key:") }.substringAfter(':').trim()

    private fun readLine(input: DataInputStream): String? {
        val sb = StringBuilder()
        while (true) {
            val c = input.read()
            if (c < 0) return null
            if (c == '\n'.code) return sb.toString().trimEnd('\r')
            sb.append(c.toChar())
        }
    }
}
