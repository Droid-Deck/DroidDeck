package com.droiddeck.launcher.stores

import java.io.Closeable
import java.io.IOException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.net.URLDecoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.util.concurrent.atomic.AtomicBoolean

internal class LoopbackListener(
    private val state: String,
    private val answerPage: String,
    private val timeoutMs: Long,
    private val requestTimeoutMs: Int = REQUEST_TIMEOUT_MS,
    private val onOutcome: (Outcome) -> Unit,
) : Closeable {
    sealed interface Outcome {
        class Code(val code: String) : Outcome
        object Denied : Outcome
        object TimedOut : Outcome
    }

    private val server = ServerSocket(0, BACKLOG, InetAddress.getByAddress(byteArrayOf(127, 0, 0, 1)))
    private val finished = AtomicBoolean(false)
    private val lock = Any()
    private val open = HashSet<Socket>()
    @Volatile private var path = ""

    val port: Int get() = server.localPort

    fun start(path: String) {
        this.path = path
        Thread({ acceptLoop() }, "loopback-listener").apply { isDaemon = true }.start()
    }

    override fun close() {
        finished.set(true)
        shutDown()
    }

    private fun shutDown() {
        runCatching { server.close() }
        synchronized(lock) {
            open.forEach { runCatching { it.close() } }
            open.clear()
        }
    }

    private fun admit(socket: Socket): Boolean = synchronized(lock) {
        if (finished.get() || open.size >= MAX_CONNECTIONS) false else open.add(socket)
    }

    private fun release(socket: Socket) {
        synchronized(lock) { open.remove(socket) }
        runCatching { socket.close() }
    }

    private fun acceptLoop() {
        val deadline = System.nanoTime() + timeoutMs * 1_000_000L
        while (!finished.get()) {
            val left = (deadline - System.nanoTime()) / 1_000_000L
            if (left <= 0) { settle(Outcome.TimedOut); return }
            val socket = try {
                server.soTimeout = left.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
                server.accept()
            } catch (e: SocketTimeoutException) {
                continue
            } catch (e: IOException) {
                return
            }
            if (!admit(socket)) { runCatching { socket.close() }; continue }
            Thread({ serve(socket) }, "loopback-request").apply { isDaemon = true }.start()
        }
    }

    private fun settle(outcome: Outcome): Boolean {
        if (!finished.compareAndSet(false, true)) return false
        shutDown()
        onOutcome(outcome)
        return true
    }

    private fun serve(socket: Socket) {
        try {
            socket.soTimeout = requestTimeoutMs
            val request = try { readRequest(socket) } catch (e: IOException) { return }
            val outcome = outcomeOf(request)
            if (outcome == null) { respond(socket, 400, "Bad Request", ""); return }
            if (finished.get()) { respond(socket, 410, "Gone", ""); return }
            respond(socket, 200, "OK", answerPage)
            settle(outcome)
        } finally {
            release(socket)
        }
    }

    private class Request(val method: String, val target: String, val host: String?)

    private fun readRequest(socket: Socket): Request? {
        val input = socket.getInputStream()
        val head = StringBuilder()
        val deadline = System.nanoTime() + requestTimeoutMs * 1_000_000L
        while (head.length < MAX_HEAD) {
            if (System.nanoTime() > deadline) throw SocketTimeoutException()
            val b = input.read()
            if (b < 0) break
            head.append(b.toChar())
            if (head.endsWith("\r\n\r\n") || head.endsWith("\n\n")) break
        }
        val lines = head.toString().split("\r\n", "\n")
        val line = lines.firstOrNull()?.split(' ') ?: return null
        if (line.size != 3 || !line[2].startsWith("HTTP/1.")) return null
        val host = lines.drop(1).firstOrNull { it.startsWith("Host:", ignoreCase = true) }?.substringAfter(':')?.trim()
        return Request(line[0], line[1], host)
    }

    private fun outcomeOf(request: Request?): Outcome? {
        if (request == null || request.method != "GET") return null
        if (request.host != "localhost:$port") return null
        if (request.target.substringBefore('?') != path) return null
        val params = request.target.substringAfter('?', "").split('&').filter { it.isNotEmpty() }.associate { pair ->
            val key = pair.substringBefore('=')
            val value = pair.substringAfter('=', "")
            decode(key) to decode(value)
        }
        val given = params["state"] ?: return null
        if (!MessageDigest.isEqual(given.toByteArray(StandardCharsets.UTF_8), state.toByteArray(StandardCharsets.UTF_8))) return null
        if (params["error"] != null) return Outcome.Denied
        val code = params["code"].orEmpty()
        return if (code.isEmpty()) null else Outcome.Code(code)
    }

    private fun decode(text: String): String = try { URLDecoder.decode(text, "UTF-8") } catch (e: IllegalArgumentException) { "" }

    private fun respond(socket: Socket, status: Int, reason: String, body: String) {
        val bytes = body.toByteArray(StandardCharsets.UTF_8)
        val head = "HTTP/1.1 $status $reason\r\n" +
            "Content-Type: text/html; charset=utf-8\r\n" +
            "Content-Length: ${bytes.size}\r\n" +
            "Cache-Control: no-store\r\n" +
            "Referrer-Policy: no-referrer\r\n" +
            "Content-Security-Policy: default-src 'none'; style-src 'unsafe-inline'\r\n" +
            "Connection: close\r\n\r\n"
        runCatching {
            val out = socket.getOutputStream()
            out.write(head.toByteArray(StandardCharsets.US_ASCII))
            out.write(bytes)
            out.flush()
        }
    }

    companion object {
        private const val BACKLOG = 8
        internal const val MAX_CONNECTIONS = 16
        private const val REQUEST_TIMEOUT_MS = 5_000
        private const val MAX_HEAD = 8192

        fun page(message: String, returnHref: String): String {
            fun escape(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
            return "<!doctype html><html><head><meta charset=\"utf-8\"><meta name=\"viewport\" content=\"width=device-width,initial-scale=1\">" +
                "<style>body{font:18px sans-serif;margin:0;min-height:100vh;display:flex;align-items:center;justify-content:center;background:#0a0b0d;text-align:center}" +
                "a{color:#f2f4f7;padding:24px;text-decoration:none}</style></head><body><a href=\"${escape(returnHref)}\">${escape(message)}</a></body></html>"
        }
    }
}
