package com.droiddeck.launcher.sync

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.File
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest

/**
 * Bridges the in-app WebView to Syncthing's Unix socket while the web UI is open. Any app can connect to a
 * loopback port, so requests without this session's cookie are refused before they reach Syncthing.
 */
class SyncthingProxy(private val socket: File, private val auth: String) : Closeable {
    // Android's getLoopbackAddress() is ::1, but the WebView is pointed at 127.0.0.1.
    private val server = ServerSocket(0, 16, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val token = SyncPrefs.randomToken()
    val url = "http://127.0.0.1:${server.localPort}/"
    val cookie = "$COOKIE=$token"

    init {
        Thread(::accept, "syncthing-proxy").apply { isDaemon = true }.start()
    }

    override fun close() { runCatching { server.close() } }

    private fun accept() {
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: return
            Thread({ serve(client) }, "syncthing-proxy-request").apply { isDaemon = true }.start()
        }
    }

    private fun serve(client: Socket) = client.use {
        runCatching {
            client.soTimeout = HEAD_TIMEOUT_MS
            val input = client.getInputStream().buffered()
            val request = SyncthingHttp.readLine(input) ?: return@runCatching
            val headers = SyncthingHttp.readHeaders(input)
            if (!authorized(headers)) {
                client.getOutputStream().write(FORBIDDEN)
                return@runCatching
            }
            client.soTimeout = 0
            LocalSocket().use { upstream ->
                upstream.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
                val head = StringBuilder(request).append("\r\n")
                headers.filter { it.first.lowercase() !in DROPPED }.forEach { (name, value) ->
                    val kept = if (name.equals("Cookie", ignoreCase = true)) withoutToken(value) else value
                    if (kept.isNotEmpty()) head.append(name).append(": ").append(kept).append("\r\n")
                }
                // One request per connection keeps every request behind the cookie check above.
                head.append("Authorization: ").append(auth).append("\r\nConnection: close\r\n\r\n")
                upstream.outputStream.write(head.toString().toByteArray(Charsets.ISO_8859_1))
                Thread({ runCatching { input.copyTo(upstream.outputStream) } }, "syncthing-proxy-body").apply { isDaemon = true }.start()
                upstream.inputStream.copyTo(client.getOutputStream())
            }
        }
    }

    private fun cookies(header: String) = header.split(';').map { it.trim() }

    private fun authorized(headers: List<Pair<String, String>>): Boolean = headers
        .filter { it.first.equals("Cookie", ignoreCase = true) }
        .flatMap { cookies(it.second) }
        .any { MessageDigest.isEqual(it.toByteArray(), cookie.toByteArray()) }

    private fun withoutToken(header: String) = cookies(header).filter { !it.startsWith("$COOKIE=") }.joinToString("; ")

    private companion object {
        const val COOKIE = "droiddeck-proxy"
        const val HEAD_TIMEOUT_MS = 30_000
        val DROPPED = setOf("authorization", "x-api-key", "connection", "proxy-connection", "keep-alive", "upgrade")
        val FORBIDDEN = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
    }
}
