package com.droiddeck.launcher.sync

import android.net.LocalSocket
import android.net.LocalSocketAddress
import java.io.Closeable
import java.io.File
import java.io.FilterInputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.security.MessageDigest
import java.util.Collections
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Semaphore

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
    private val workers = Semaphore(MAX_WORKERS)
    private val open: MutableSet<Closeable> = Collections.newSetFromMap(ConcurrentHashMap())

    init {
        Thread(::accept, "syncthing-proxy").apply { isDaemon = true }.start()
    }

    override fun close() {
        runCatching { server.close() }
        open.forEach { runCatching { it.close() } }
    }

    private fun track(c: Closeable) {
        open += c
        if (server.isClosed) runCatching { c.close() }
    }

    private fun accept() {
        while (true) {
            val client = runCatching { server.accept() }.getOrNull() ?: return
            if (!workers.tryAcquire()) {
                runCatching { client.close() }
                continue
            }
            val worker = runCatching {
                track(client)
                Thread({ try { serve(client) } finally { open -= client; workers.release() } }, "syncthing-proxy-request").apply { isDaemon = true }.start()
            }
            if (worker.isFailure) { open -= client; workers.release(); runCatching { client.close() } }
        }
    }

    private fun serve(client: Socket) = client.use {
        runCatching {
            val deadline = HeadDeadline(client, client.getInputStream(), System.nanoTime() + HEAD_TIMEOUT_MS * 1_000_000L)
            val input = deadline.buffered()
            val request = SyncthingHttp.readLine(input) ?: return@runCatching
            val headers = SyncthingHttp.readHeaders(input)
            if (!authorized(headers)) {
                client.getOutputStream().write(FORBIDDEN)
                return@runCatching
            }
            deadline.lift()
            LocalSocket().use { upstream ->
                track(upstream)
                try { relay(client, input, request, headers, upstream) } finally { open -= upstream }
            }
        }
    }

    private fun relay(client: Socket, input: InputStream, request: String, headers: List<Pair<String, String>>, upstream: LocalSocket) {
        upstream.connect(LocalSocketAddress(socket.path, LocalSocketAddress.Namespace.FILESYSTEM))
        val head = StringBuilder(request).append("\r\n")
        headers.filter { it.first.lowercase() !in DROPPED }.forEach { (name, value) ->
            val kept = if (name.equals("Cookie", ignoreCase = true)) withoutToken(value) else value
            if (kept.isNotEmpty()) head.append(name).append(": ").append(kept).append("\r\n")
        }
        // One request per connection keeps every request behind serve's cookie check.
        head.append("Authorization: ").append(auth).append("\r\nConnection: close\r\n\r\n")
        upstream.outputStream.write(head.toString().toByteArray(Charsets.ISO_8859_1))
        Thread({ runCatching { input.copyTo(upstream.outputStream) } }, "syncthing-proxy-body").apply { isDaemon = true }.start()
        upstream.inputStream.copyTo(client.getOutputStream())
    }

    private fun cookies(header: String) = header.split(';').map { it.trim() }

    private fun authorized(headers: List<Pair<String, String>>): Boolean = headers
        .filter { it.first.equals("Cookie", ignoreCase = true) }
        .flatMap { cookies(it.second) }
        .any { MessageDigest.isEqual(it.toByteArray(), cookie.toByteArray()) }

    private fun withoutToken(header: String) = cookies(header).filter { !it.startsWith("$COOKIE=") }.joinToString("; ")

    /** Bounds the whole request line and headers by one deadline, so a slow client can't hold a worker. */
    private class HeadDeadline(private val client: Socket, input: InputStream, private val until: Long) : FilterInputStream(input) {
        @Volatile private var lifted = false

        fun lift() {
            lifted = true
            client.soTimeout = 0
        }

        override fun read(): Int { arm(); return super.read() }
        override fun read(b: ByteArray, off: Int, len: Int): Int { arm(); return super.read(b, off, len) }

        private fun arm() {
            if (lifted) return
            val left = (until - System.nanoTime()) / 1_000_000L
            if (left <= 0) throw SocketTimeoutException("request head took too long")
            client.soTimeout = left.toInt()
        }
    }

    private companion object {
        const val COOKIE = "droiddeck-proxy"
        const val HEAD_TIMEOUT_MS = 10_000
        const val MAX_WORKERS = 16
        val DROPPED = setOf("authorization", "x-api-key", "connection", "proxy-connection", "keep-alive", "upgrade")
        val FORBIDDEN = "HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".toByteArray()
    }
}
