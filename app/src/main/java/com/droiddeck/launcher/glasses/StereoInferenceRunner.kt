package com.droiddeck.launcher.glasses

import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.Future

internal class StereoInferenceRunner : AutoCloseable {
    private val worker = Executors.newSingleThreadExecutor { task ->
        Thread(task, "viture-right-hand").apply { isDaemon = true }
    }

    fun <T> onRight(action: () -> T): T = worker.submit<T> { action() }.completed()

    fun <T> pair(left: () -> T, right: () -> T): Pair<T, T> {
        val pending = worker.submit<T> { right() }
        val result = try { left() } catch (error: Throwable) {
            try { pending.completed() } catch (other: Throwable) { if (other !== error) error.addSuppressed(other) }
            throw error
        }
        return result to pending.completed()
    }

    override fun close() { worker.shutdown() }

    private fun <T> Future<T>.completed(): T {
        var interrupted = false
        try {
            while (true) {
                try { return get() }
                catch (e: InterruptedException) { interrupted = true }
                catch (e: ExecutionException) { throw e.cause ?: e }
            }
        } finally {
            // Native inference must finish before either camera's pixels or detector can be reused.
            if (interrupted) Thread.currentThread().interrupt()
        }
    }
}
