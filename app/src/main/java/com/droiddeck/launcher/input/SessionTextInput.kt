package com.droiddeck.launcher.input

import android.content.Context
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.droiddeck.launcher.runtime.LinuxRuntime
import com.droiddeck.launcher.wayland.WaylandCompositor
import java.io.File
import java.io.IOException
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.RejectedExecutionException

class SessionTextInput(context: Context, private val hasFocus: () -> Boolean) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var generation = 0L
    @Volatile private var active = false
    private var executor: ExecutorService? = null
    private var socket: LocalSocket? = null
    private var pendingToken = 0
    private val guestListener = object : WaylandCompositor.TextInputFallbackListener {
        override fun onTextInputFallback(text: String, token: Int): Boolean {
            synchronized(lock) {
                if (!active || pendingToken != 0 || token <= 0) return false
                val received = generation
                pendingToken = token
                val posted = try {
                    handler.post { enqueue(text, received, token) }
                } catch (e: RuntimeException) {
                    false
                }
                if (!posted) pendingToken = 0
                return posted
            }
        }
    }

    fun start() {
        if (active) return
        val queue = Executors.newSingleThreadExecutor { runnable ->
            Thread(runnable, "session-text-input").apply { isDaemon = true }
        }
        synchronized(lock) {
            active = true
            executor = queue
        }
        WaylandCompositor.setTextInputFallbackListener(guestListener)
    }

    fun stop() {
        val detached = synchronized(lock) {
            if (!active) return
            active = false
            generation += 1
            val queue = executor
            val open = socket
            val token = pendingToken
            executor = null
            socket = null
            pendingToken = 0
            Triple(queue, open, token)
        }
        WaylandCompositor.clearTextInputFallbackListener(guestListener)
        handler.removeCallbacksAndMessages(null)
        detached.second?.let { close(it) }
        detached.first?.shutdownNow()
        if (detached.third != 0) WaylandCompositor.completeTextInputFallback(detached.third)
    }

    private fun enqueue(text: String, received: Long, token: Int) {
        if (!active || !hasFocus() || !WaylandCompositor.isTextInputFallbackListener(guestListener)) {
            complete(token)
            return
        }
        val bytes = text.toByteArray(Charsets.UTF_8)
        if (bytes.isEmpty() || bytes.size > MAX_BYTES) {
            complete(token)
            return
        }
        val queue = synchronized(lock) {
            if (active && generation == received) executor else null
        }
        if (queue == null) {
            complete(token)
            return
        }
        try {
            queue.execute {
                try {
                    send(bytes, received)
                } finally {
                    complete(token)
                }
            }
        } catch (e: RejectedExecutionException) {
            complete(token)
        }
    }

    private fun complete(token: Int) {
        val owned = synchronized(lock) {
            if (pendingToken == token) {
                pendingToken = 0
                true
            } else {
                false
            }
        }
        if (owned) WaylandCompositor.completeTextInputFallback(token)
    }

    private fun send(bytes: ByteArray, received: Long) {
        val live = LocalSocket()
        if (!claim(live, received)) {
            close(live)
            return
        }
        try {
            live.soTimeout = TIMEOUT_MS
            live.connect(LocalSocketAddress(endpoint().path, LocalSocketAddress.Namespace.FILESYSTEM))
            if (!isCurrent(received)) return
            val out = live.outputStream
            out.write(lengthPrefix(bytes.size))
            out.write(bytes)
            out.flush()
            if (live.inputStream.read() != 1 && isCurrent(received)) Log.w(TAG, "guest did not accept the text")
        } catch (e: IOException) {
            if (isCurrent(received)) Log.w(TAG, "could not hand text to the guest", e)
        } finally {
            release(live)
            close(live)
        }
    }

    private fun isCurrent(received: Long) = synchronized(lock) { active && generation == received }

    private fun claim(live: LocalSocket, received: Long) = synchronized(lock) {
        if (!active || generation != received) {
            false
        } else {
            socket = live
            true
        }
    }

    private fun release(live: LocalSocket) {
        synchronized(lock) {
            if (socket === live) socket = null
        }
    }

    private fun endpoint(): File = File(LinuxRuntime.sessionRoot(appContext), "text-input.sock")

    private fun lengthPrefix(size: Int) = byteArrayOf(
        (size ushr 24).toByte(),
        (size ushr 16).toByte(),
        (size ushr 8).toByte(),
        size.toByte(),
    )

    private fun close(sock: LocalSocket) {
        try {
            sock.close()
        } catch (e: IOException) {
        }
    }

    private companion object {
        const val TAG = "SessionTextInput"
        const val MAX_BYTES = 65536
        const val TIMEOUT_MS = 3000
    }
}
