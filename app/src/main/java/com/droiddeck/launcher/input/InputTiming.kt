package com.droiddeck.launcher.input

import java.util.Locale

object InputTiming {
    @JvmField @Volatile var enabled = false
    private val samples = LongArray(4096)
    private var count = 0L
    private var next = 0

    @JvmStatic fun begin(): Long = if (enabled) System.nanoTime() else 0L

    @JvmStatic fun end(start: Long) {
        if (start == 0L) return
        val elapsed = System.nanoTime() - start
        synchronized(this) {
            samples[next] = elapsed
            next = (next + 1) % samples.size
            count++
        }
    }

    @Synchronized fun reset() { count = 0; next = 0; samples.fill(0) }

    @Synchronized fun report(): String {
        val size = minOf(count, samples.size.toLong()).toInt()
        if (size == 0) return "No input samples"
        val values = samples.copyOf(size).sortedArray()
        fun percentile(p: Double) = values[((size - 1) * p).toInt()] / 1000.0
        return String.format(Locale.ROOT, "Dispatch to publication (µs): p50 %.1f, p95 %.1f, p99 %.1f; %d samples, last %d retained",
            percentile(.5), percentile(.95), percentile(.99), count, size)
    }
}
