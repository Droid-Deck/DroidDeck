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
import java.net.SocketTimeoutException
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.TimeUnit

class SessionTextInput(context: Context, private val hasFocus: () -> Boolean) {
    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val lock = Any()
    private var generation = 0L
    @Volatile private var active = false
    private var executor: ScheduledThreadPoolExecutor? = null
    private var socket: LocalSocket? = null
    private var pendingToken = 0
    private var runningToken = 0
    private var failedToken = 0
    private val guestListener = object : WaylandCompositor.TextInputFallbackListener {
        override fun onTextInputFallback(text: String, token: Int): Boolean {
            synchronized(lock) {
                if (!active || pendingToken != 0 || failedToken != 0 || token <= 0) return false
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
        val queue = ScheduledThreadPoolExecutor(1) { runnable ->
            Thread(runnable, "session-text-input").apply { isDaemon = true }
        }
        queue.executeExistingDelayedTasksAfterShutdownPolicy = false
        synchronized(lock) {
            active = true
            executor = queue
            if (failedToken != 0) scheduleRetryLocked(failedToken, INITIAL_DELAY_MS)
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
            val token = if (runningToken == 0 && failedToken == 0) pendingToken else 0
            executor = null
            socket = null
            Triple(queue, open, token)
        }
        WaylandCompositor.clearTextInputFallbackListener(guestListener)
        handler.removeCallbacksAndMessages(null)
        detached.second?.let { requestCancel(it) }
        detached.first?.shutdown()
        if (detached.third != 0) complete(detached.third)
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
                    if (send(bytes, received, token)) complete(token) else recover(token)
                } finally {
                    releaseRunning(token)
                }
            }
        } catch (e: RejectedExecutionException) {
            complete(token)
        }
    }

    private fun recover(token: Int) {
        if (probe()) {
            complete(token)
            return
        }
        val marked = synchronized(lock) {
            if (pendingToken == token && failedToken == 0) {
                failedToken = token
                WaylandCompositor.completeTextInputFallback(-token)
                scheduleRetryLocked(token, INITIAL_DELAY_MS)
                true
            } else {
                false
            }
        }
        if (marked) Log.w(TAG, "text injection could not be confirmed")
    }

    private fun scheduleRetryLocked(token: Int, delayMs: Long) {
        if (!active || failedToken != token || pendingToken != token) return
        val queue = executor ?: return
        val current = generation
        try {
            queue.schedule({ retryProbe(current, token, delayMs) }, delayMs, TimeUnit.MILLISECONDS)
        } catch (e: RejectedExecutionException) {
        }
    }

    private fun retryProbe(received: Long, token: Int, delayMs: Long) {
        val ready = synchronized(lock) {
            active && generation == received && failedToken == token && pendingToken == token
        }
        if (!ready) return
        val proven = probe()
        val owned = synchronized(lock) {
            if (proven) {
                if (active && generation == received && failedToken == token && pendingToken == token) {
                    pendingToken = 0
                    runningToken = 0
                    failedToken = 0
                    true
                } else {
                    false
                }
            } else {
                if (active && generation == received && failedToken == token && pendingToken == token) {
                    val next = (delayMs * 2).coerceAtMost(MAX_DELAY_MS)
                    scheduleRetryLocked(token, next)
                }
                false
            }
        }
        if (owned) WaylandCompositor.completeTextInputFallback(token)
    }

    private fun probe(): Boolean {
        val live = LocalSocket()
        return try {
            live.soTimeout = TIMEOUT_MS
            live.connect(LocalSocketAddress(endpoint().path, LocalSocketAddress.Namespace.FILESYSTEM))
            val out = live.outputStream
            out.write(lengthPrefix(0))
            out.flush()
            live.inputStream.read() == 0
        } catch (e: IOException) {
            Log.w(TAG, "could not probe guest text input state", e)
            false
        } finally {
            close(live)
        }
    }

    private fun complete(token: Int) {
        val owned = synchronized(lock) {
            if (pendingToken == token) {
                pendingToken = 0
                runningToken = 0
                failedToken = 0
                true
            } else {
                false
            }
        }
        if (owned) WaylandCompositor.completeTextInputFallback(token)
    }

    private fun releaseRunning(token: Int) {
        synchronized(lock) {
            if (runningToken == token) runningToken = 0
        }
    }

    private fun send(bytes: ByteArray, received: Long, token: Int): Boolean {
        val live = LocalSocket()
        if (!claim(live, received, token)) {
            close(live)
            return true
        }
        var requested = false
        try {
            live.soTimeout = TIMEOUT_MS
            live.connect(LocalSocketAddress(endpoint().path, LocalSocketAddress.Namespace.FILESYSTEM))
            if (!isCurrent(received)) return true
            val out = live.outputStream
            out.write(lengthPrefix(bytes.size))
            requested = true
            out.write(bytes)
            out.flush()
            val ack = try {
                live.inputStream.read()
            } catch (e: SocketTimeoutException) {
                requestCancel(live)
                try {
                    live.inputStream.read()
                } catch (e2: SocketTimeoutException) {
                    Log.w(TAG, "guest did not confirm text injection")
                    return false
                }
            }
            if (ack != 0 && ack != 1 && isCurrent(received)) Log.w(TAG, "guest did not accept the text")
            return ack == 0 || ack == 1
        } catch (e: IOException) {
            if (!requested) return true
            if (isCurrent(received)) Log.w(TAG, "could not hand text to the guest", e)
            return false
        } finally {
            release(live)
            close(live)
        }
    }

    private fun requestCancel(live: LocalSocket) {
        try {
            live.shutdownOutput()
        } catch (e: IOException) {
            Log.w(TAG, "could not request guest text input cancellation", e)
        }
    }

    private fun isCurrent(received: Long) = synchronized(lock) { active && generation == received }

    private fun claim(live: LocalSocket, received: Long, token: Int) = synchronized(lock) {
        if (!active || generation != received || pendingToken != token || runningToken != 0 || failedToken != 0) {
            false
        } else {
            runningToken = token
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

    private fun lengthPrefix(size: Int): ByteArray {
        val flagged = if (size == 0) size else size or Int.MIN_VALUE
        return byteArrayOf(
            (flagged ushr 24).toByte(),
            (flagged ushr 16).toByte(),
            (flagged ushr 8).toByte(),
            flagged.toByte(),
        )
    }

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
        const val INITIAL_DELAY_MS = 500L
        const val MAX_DELAY_MS = 5000L
    }
}
