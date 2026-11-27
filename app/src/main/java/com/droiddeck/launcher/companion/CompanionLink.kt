package com.droiddeck.launcher.companion

import com.droiddeck.launcher.session.LoopbackWebSocket
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.suspendCancellableCoroutine
import java.util.concurrent.atomic.AtomicReference
import kotlin.concurrent.thread
import kotlin.coroutines.resume

class CompanionLink(private val port: Int, private val path: String = "/") {
    private val socket = AtomicReference<LoopbackWebSocket?>(null)

    val connected: Boolean get() = socket.get() != null

    private val sender = java.util.concurrent.Executors.newSingleThreadExecutor { r -> Thread(r, "companion-send-$port").apply { isDaemon = true } }

    fun send(text: String): Boolean {
        val ws = socket.get() ?: return false
        sender.execute { runCatching { ws.send(text) } }
        return true
    }

    fun close() {
        sender.shutdown()
    }

    suspend fun run(onConnected: (Boolean) -> Unit, onText: (String) -> Unit, hello: String? = null) {
        while (currentCoroutineContext().isActive) {
            val ws = runCatching { LoopbackWebSocket(port, path) }.getOrNull()
            if (ws == null) {
                delay(RETRY_MS)
                continue
            }
            socket.set(ws)
            onConnected(true)
            try {
                hello?.let { runCatching { ws.send(it) } }
                readUntilClosed(ws, onText)
            } finally {
                socket.set(null)
                runCatching { ws.close() }
                onConnected(false)
            }
            currentCoroutineContext().ensureActive()
            delay(RETRY_MS)
        }
    }

    private suspend fun readUntilClosed(ws: LoopbackWebSocket, onText: (String) -> Unit) = suspendCancellableCoroutine { cont ->
        val reader = thread(name = "companion-$port", isDaemon = true) {
            try {
                while (true) onText(ws.receive() ?: break)
            } catch (_: Exception) {
            }
            if (cont.isActive) cont.resume(Unit)
        }
        cont.invokeOnCancellation {
            runCatching { ws.close() }
            reader.interrupt()
        }
    }

    private companion object {
        const val RETRY_MS = 3_000L
    }
}
