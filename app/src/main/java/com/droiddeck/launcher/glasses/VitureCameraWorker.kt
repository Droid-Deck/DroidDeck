package com.droiddeck.launcher.glasses

import java.util.concurrent.Executors

internal class VitureCameraWorker : AutoCloseable {
    @Volatile private var closed = false

    fun execute(capture: () -> Unit) {
        executor.execute { if (!closed) capture() }
    }

    override fun close() { closed = true }

    private companion object {
        // The native camera stream is process-wide; replacement capture waits for prior cleanup.
        val executor = Executors.newSingleThreadExecutor { Thread(it, "viture-camera").apply { isDaemon = true } }
    }
}
